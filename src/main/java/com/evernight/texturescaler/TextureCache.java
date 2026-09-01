package com.evernight.texturescaler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Small on-disk cache for downscaled textures.
 *
 * <p>Key = sha256 of "namespace:path|srcWxsrcH|cap". The original dimensions and the
 * cap are part of the key, so any change (new mod version, changed cap, …) produces a
 * different file and stale entries are simply never read again.</p>
 */
final class TextureCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Path cacheDir;

    private TextureCache() {
    }

    /** Name of the size manifest inside the cache dir. */
    private static final String SIZES_FILE = "sizes.json";

    /**
     * Load the persisted "texture path -> [w, h]" manifest, or an empty map when absent,
     * corrupt, or built for a different cap.
     *
     * <p>The manifest lets the atlas scan reuse previously read PNG dimensions instead of
     * opening and reading a 24-byte header from every texture on every launch.</p>
     */
    static Map<String, int[]> loadSizeManifest(int cap) {
        Map<String, int[]> result = new HashMap<>();
        if (!Config.diskCacheEnabled()) {
            return result;
        }
        Path file = dir().resolve(SIZES_FILE);
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("cap") || root.get("cap").getAsInt() != cap) {
                return result; // different scaling cap -> dimensions still valid, but rebuild anyway
            }
            JsonObject files = root.has("files") ? root.getAsJsonObject("files") : null;
            if (files != null) {
                for (Map.Entry<String, JsonElement> e : files.entrySet()) {
                    JsonArray a = e.getValue().getAsJsonArray();
                    if (a.size() >= 2) {
                        result.put(e.getKey(), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()});
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[TextureScaler] size manifest read failed (will rebuild): {}", e.toString());
            return new HashMap<>();
        }
        return result;
    }

    /** Persist the "texture path -> [w, h]" manifest (best effort, atomic rename). */
    static void saveSizeManifest(int cap, Map<String, int[]> sizes) {
        if (!Config.diskCacheEnabled()) {
            return;
        }
        JsonObject files = new JsonObject();
        for (Map.Entry<String, int[]> e : sizes.entrySet()) {
            JsonArray a = new JsonArray();
            a.add(e.getValue()[0]);
            a.add(e.getValue()[1]);
            files.add(e.getKey(), a);
        }
        JsonObject root = new JsonObject();
        root.addProperty("cap", cap);
        root.add("files", files);
        try {
            Path dir = dir();
            Path target = dir.resolve(SIZES_FILE);
            Path tmp = target.resolveSibling(SIZES_FILE + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new Gson().toJson(root, writer);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("[TextureScaler] size manifest write failed: {}", e.toString());
        }
    }

    static synchronized Path dir() {
        if (cacheDir == null) {
            Path base = Minecraft.getInstance().gameDirectory.toPath();
            cacheDir = base.resolve(Config.cacheDir()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException e) {
                LOGGER.warn("[TextureScaler] cannot create cache dir {}: {}", cacheDir, e.toString());
            }
        }
        return cacheDir;
    }

    /** Read a cached entry, or {@code null} when absent/corrupt. */
    static byte[] get(String namespace, String path, int srcW, int srcH, int cap) {
        if (!Config.diskCacheEnabled()) {
            return null;
        }
        Path file = fileFor(namespace, path, srcW, srcH, cap);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("[TextureScaler] cache read failed for {}:{} ({}), will re-scale", namespace, path, e.toString());
            return null;
        }
    }

    /** Store an entry (best effort, atomic rename). */
    static void put(String namespace, String path, int srcW, int srcH, int cap, byte[] png) {
        if (!Config.diskCacheEnabled()) {
            return;
        }
        try {
            Path dir = dir();
            Path target = fileFor(namespace, path, srcW, srcH, cap);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(png);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("[TextureScaler] cache write failed for {}:{}: {}", namespace, path, e.toString());
        }
    }

    private static Path fileFor(String namespace, String path, int srcW, int srcH, int cap) {
        String key = namespace + ":" + path + "|" + srcW + "x" + srcH + "|" + cap;
        return dir().resolve(sha256(key) + ".png");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            // Cannot happen on a standard JVM; fall back to identity-ish key.
            return Integer.toHexString(s.hashCode());
        }
    }
}
