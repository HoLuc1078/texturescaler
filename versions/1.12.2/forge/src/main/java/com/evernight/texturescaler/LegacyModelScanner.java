package com.evernight.texturescaler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1.12.2-local port of the shared {@code com.evernight.texturescaler.core.ModelScanner}.
 *
 * <p>Why a local copy: {@code core.ModelScanner} expects the platform to hand it the
 * model JSONs, and on modern versions the platform obtains them through
 * {@code ResourceManager.listResources}. 1.12.2's {@code IResourceManager} cannot list
 * resources at all, so this class enumerates the mod sources (jar or directory) directly
 * and produces exactly the same map ({@code "ns:spritePath" -> max texture_size edge}),
 * using only Gson entry points that exist since Gson 2.8.0.</p>
 *
 * <p>Model JSON files are enumerated straight from the mod sources (jar or directory),
 * because 1.12.2's {@code IResourceManager} cannot list resources. Vanilla models never
 * carry {@code texture_size} (they use normalised 0..16 UVs), so skipping them cannot
 * lose a constraint.</p>
 */
@SideOnly(Side.CLIENT)
final class LegacyModelScanner {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_PARENT_DEPTH = 16;
    private static final Gson GSON = new Gson();

    private LegacyModelScanner() {
    }

    /** Collects every mod model JSON and returns the sprite -> texture_size map. */
    static Map<String, Integer> scanAllModModels() {
        Map<String, String> models = new HashMap<String, String>();
        List<ModContainer> containers;
        try {
            containers = Loader.instance().getActiveModList();
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
        for (ModContainer container : containers) {
            try {
                File source = container.getSource();
                if (source == null || !source.exists()) {
                    continue;
                }
                if (source.isDirectory()) {
                    readDirectory(source, models);
                } else {
                    readArchive(source, models);
                }
            } catch (Throwable ignored) {
                // a broken mod source must never break the scaler
            }
        }
        return scan(models);
    }

    private static void readDirectory(File source, Map<String, String> out) {
        File assets = new File(source, "assets");
        File[] domains = assets.listFiles();
        if (domains == null) {
            return;
        }
        for (File domain : domains) {
            if (!domain.isDirectory()) {
                continue;
            }
            File modelsDir = new File(domain, "models");
            if (!modelsDir.isDirectory()) {
                continue;
            }
            walk(modelsDir, domain.getName(), "", out);
        }
    }

    private static void walk(File dir, String namespace, String prefix, Map<String, String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                walk(child, namespace, prefix + name + "/", out);
            } else if (name.endsWith(".json")) {
                String json = readFullyQuietly(child);
                if (json != null) {
                    out.put(namespace + ":" + prefix + name.substring(0, name.length() - 5), json);
                }
            }
        }
    }

    private static void readArchive(File source, Map<String, String> out) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(source);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith("assets/") || !name.endsWith(".json")) {
                    continue;
                }
                String rest = name.substring("assets/".length());
                int slash = rest.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                String namespace = rest.substring(0, slash);
                String path = rest.substring(slash + 1);
                if (!path.startsWith("models/")) {
                    continue;
                }
                String modelPath = path.substring("models/".length(), path.length() - ".json".length());
                String json = readEntryQuietly(zip, entry);
                if (json != null) {
                    out.put(namespace + ":" + modelPath, json);
                }
            }
        } catch (Throwable ignored) {
            // unreadable archive - skip
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    // ---- the actual scan (behaviourally identical to core ModelScanner) ----

    static Map<String, Integer> scan(Map<String, String> models) {
        Map<String, Integer> result = new HashMap<String, Integer>();
        if (models == null || models.isEmpty()) {
            return result;
        }
        Map<String, JsonObject> parsed = new HashMap<String, JsonObject>();
        for (Map.Entry<String, String> e : models.entrySet()) {
            // Cheap pre-filter: most models never mention texture_size.
            if (e.getValue() == null || e.getValue().indexOf("texture_size") < 0) {
                continue;
            }
            try {
                JsonElement el = GSON.fromJson(e.getValue(), JsonElement.class);
                if (el != null && el.isJsonObject()) {
                    parsed.put(e.getKey(), el.getAsJsonObject());
                }
            } catch (Throwable ignore) {
                // unreadable model - it cannot safely reference our textures
            }
        }
        if (parsed.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, JsonObject> e : parsed.entrySet()) {
            String modelId = e.getKey();
            int colon = modelId.indexOf(':');
            String modelNamespace = colon >= 0 ? modelId.substring(0, colon) : "minecraft";
            List<JsonObject> chain = buildParentChain(modelId, parsed);
            if (chain.isEmpty()) {
                continue;
            }
            Map<String, String> varToPath = new HashMap<String, String>();
            for (JsonObject m : chain) {
                JsonObject textures = m.getAsJsonObject("textures");
                if (textures != null) {
                    for (Map.Entry<String, JsonElement> te : textures.entrySet()) {
                        if (te.getValue() != null && te.getValue().isJsonPrimitive() && !varToPath.containsKey(te.getKey())) {
                            varToPath.put(te.getKey(), te.getValue().getAsString());
                        }
                    }
                }
            }
            int texSize = 0;
            for (JsonObject m : chain) {
                JsonArray size = m.getAsJsonArray("texture_size");
                if (size != null && size.size() >= 2) {
                    try {
                        texSize = Math.max(texSize, Math.max(size.get(0).getAsInt(), size.get(1).getAsInt()));
                    } catch (Throwable ignore) {
                        // non-numeric texture_size
                    }
                }
            }
            for (String path : varToPath.values()) {
                if (path == null || path.isEmpty() || path.charAt(0) == '#') {
                    continue;
                }
                String key = path.indexOf(':') >= 0 ? path : modelNamespace + ":" + path;
                Integer existing = result.get(key);
                if (existing == null || texSize > existing) {
                    result.put(key, texSize);
                }
            }
        }
        return result;
    }

    private static List<JsonObject> buildParentChain(String modelId, Map<String, JsonObject> models) {
        List<JsonObject> chain = new ArrayList<JsonObject>();
        Set<String> visited = new HashSet<String>();
        Deque<String> stack = new ArrayDeque<String>();
        stack.push(modelId);
        while (!stack.isEmpty() && chain.size() <= MAX_PARENT_DEPTH) {
            String cur = stack.pop();
            if (!visited.add(cur)) {
                continue;
            }
            JsonObject m = models.get(cur);
            if (m == null) {
                String located = locate(models, cur);
                if (located == null || !visited.add(located)) {
                    continue;
                }
                m = models.get(located);
            }
            if (m == null) {
                continue;
            }
            chain.add(m);
            JsonElement parent = m.get("parent");
            if (parent == null || !parent.isJsonPrimitive()) {
                break;
            }
            stack.push(parent.getAsString());
        }
        Collections.reverse(chain);
        return chain;
    }

    private static String locate(Map<String, JsonObject> models, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (models.containsKey(id)) {
            return id;
        }
        String namespace;
        String path;
        int colon = id.indexOf(':');
        if (colon >= 0) {
            namespace = id.substring(0, colon);
            path = id.substring(colon + 1);
        } else {
            namespace = "minecraft";
            path = id;
        }
        String bare = path;
        if (bare.startsWith("models/")) {
            bare = bare.substring("models/".length());
        }
        if (bare.endsWith(".json")) {
            bare = bare.substring(0, bare.length() - ".json".length());
        }
        String[] candidates = new String[]{
                namespace + ":" + bare,
                namespace + ":" + bare + ".json",
                namespace + ":models/" + bare,
                namespace + ":models/" + bare + ".json",
                "minecraft:" + bare,
                "minecraft:models/" + bare,
        };
        for (String candidate : candidates) {
            if (models.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---- io helpers (Java 8 only) ----

    private static String readFullyQuietly(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readFully(in);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String readEntryQuietly(ZipFile zip, ZipEntry entry) {
        InputStream in = null;
        try {
            in = zip.getInputStream(entry);
            return readFully(in);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), UTF8);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
