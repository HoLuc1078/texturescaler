package com.evernight.texturescaler.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans every block/item model JSON to record, per texture, the largest
 * {@code texture_size} of any model that references it (Blockbench models use
 * absolute-pixel UVs).
 */
public final class ModelScanner {

    private static final int MAX_PARENT_DEPTH = 16;

    private ModelScanner() {
    }

    /**
     * @return map "ns:spritePath" (e.g. {@code citymod:block/laptop}) -> max texture_size
     * edge, 0 meaning "no constraint".
     */
    public static Map<String, Integer> scan(Map<String, String> models) {
        Map<String, Integer> result = new HashMap<String, Integer>();
        if (models == null || models.isEmpty()) {
            return result;
        }
        Map<String, JsonObject> parsed = new HashMap<String, JsonObject>();
        for (Map.Entry<String, String> e : models.entrySet()) {
            try {
                // Instance API: see DiskCache — works on the Gson 2.8.0 shipped by old MC.
                @SuppressWarnings("deprecation")
                JsonParser parser = new JsonParser();
                JsonElement el = parser.parse(new StringReader(e.getValue()));
                if (el != null && el.isJsonObject()) {
                    parsed.put(e.getKey(), el.getAsJsonObject());
                }
            } catch (Exception ignore) {
                // unreadable model - skip, it cannot reference our textures safely
            }
        }
        if (parsed.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, JsonObject> e : parsed.entrySet()) {
            String modelId = e.getKey();
            int colon = modelId.indexOf(':');
            String modelNs = colon >= 0 ? modelId.substring(0, colon) : "minecraft";
            List<JsonObject> chain = buildParentChain(modelId, parsed);
            if (chain.isEmpty()) {
                continue;
            }
            Map<String, String> varToPath = new HashMap<String, String>();
            for (JsonObject m : chain) {
                JsonObject textures = m.getAsJsonObject("textures");
                if (textures != null) {
                    for (Map.Entry<String, JsonElement> te : textures.entrySet()) {
                        if (te.getValue().isJsonPrimitive() && !varToPath.containsKey(te.getKey())) {
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
                    } catch (Exception ignore) {
                        // non-numeric texture_size - ignore
                    }
                }
            }
            for (String path : varToPath.values()) {
                if (path == null || path.isEmpty() || path.charAt(0) == '#') {
                    continue;
                }
                String key;
                if (path.indexOf(':') >= 0) {
                    key = path;
                } else {
                    key = modelNs + ":" + path;
                }
                Integer existing = result.get(key);
                if (existing == null || texSize > existing) {
                    result.put(key, texSize);
                }
            }
        }
        return result;
    }

    /**
     * Walk the parent chain from the given model upward; root first, cycle/depth guarded.
     */
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

    /**
     * Try every spelling of a model id; {@code null} when none is present.
     */
    private static String locate(Map<String, JsonObject> models, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (models.containsKey(id)) {
            return id;
        }
        String ns;
        String path;
        int colon = id.indexOf(':');
        if (colon >= 0) {
            ns = id.substring(0, colon);
            path = id.substring(colon + 1);
        } else {
            ns = "minecraft";
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
                ns + ":" + bare,
                ns + ":" + bare + ".json",
                ns + ":models/" + bare,
                ns + ":models/" + bare + ".json",
                "minecraft:" + bare,
                "minecraft:models/" + bare,
        };
        for (String c : candidates) {
            if (models.containsKey(c)) {
                return c;
            }
        }
        return null;
    }
}
