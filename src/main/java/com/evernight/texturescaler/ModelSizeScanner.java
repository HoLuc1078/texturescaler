package com.evernight.texturescaler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans every block/item model JSON to record, per texture, the largest {@code texture_size}
 * of any model that references it (Blockbench models use absolute-pixel UVs).
 *
 * <p>If a texture is referenced by a model whose {@code texture_size} exceeds the downscaled
 * size, downscaling would push the model's UVs out of bounds and corrupt rendering. The pack
 * skips such textures (doc pitfall #2). Vanilla-style models have no {@code texture_size}
 * (UVs normalized to 16) and therefore never constrain scaling.</p>
 */
final class ModelSizeScanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_PARENT_DEPTH = 16;

    private ModelSizeScanner() {
    }

    /**
     * @return map "ns:spritePath" (e.g. {@code citymod:block/laptop}) -> max texture_size edge,
     *         0 means "no constraint".
     */
    static Map<String, Integer> scan(ResourceManager rm) {
        Map<String, Integer> result = new HashMap<>();
        Map<ResourceLocation, JsonObject> models = new HashMap<>();
        try {
            for (Map.Entry<ResourceLocation, Resource> e :
                    rm.listResources("models", loc -> loc.getPath().endsWith(".json")).entrySet()) {
                try (InputStream in = e.getValue().open()) {
                    JsonElement el = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    if (el.isJsonObject()) {
                        models.put(e.getKey(), el.getAsJsonObject());
                    }
                } catch (Exception ignore) {
                    // unreadable model — skip, it cannot reference our textures safely
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[TextureScaler] model scan failed: {}", e.toString());
            return result;
        }

        for (Map.Entry<ResourceLocation, JsonObject> e : models.entrySet()) {
            ResourceLocation modelLoc = e.getKey();
            String modelNs = modelLoc.getNamespace();
            List<JsonObject> chain = buildParentChain(modelLoc, models);
            if (chain.isEmpty()) {
                continue;
            }
            // Effective textures: parents first, child wins on conflicts.
            Map<String, String> varToPath = new HashMap<>();
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
            // texture_size: the declaring model's own value (parents rarely set it, but take max).
            int texSize = 0;
            for (JsonObject m : chain) {
                JsonArray size = m.getAsJsonArray("texture_size");
                if (size != null && size.size() >= 2) {
                    try {
                        texSize = Math.max(texSize, Math.max(size.get(0).getAsInt(), size.get(1).getAsInt()));
                    } catch (Exception ignore) {
                    }
                }
            }
            for (String path : varToPath.values()) {
                if (path == null || path.isEmpty() || path.charAt(0) == '#') {
                    continue; // "#parent" style reference — resolved via the chain already
                }
                String key;
                try {
                    if (path.indexOf(':') >= 0) {
                        key = ResourceLocation.parse(path).toString();
                    } else {
                        key = modelNs + ":" + path;
                    }
                } catch (Exception ex) {
                    continue;
                }
                result.merge(key, texSize, Math::max);
            }
        }
        return result;
    }

    /** Walk the parent chain from the given model upward; root first, cycle/depth guarded. */
    private static List<JsonObject> buildParentChain(ResourceLocation modelLoc, Map<ResourceLocation, JsonObject> models) {
        List<JsonObject> chain = new ArrayList<>();
        Set<ResourceLocation> visited = new HashSet<>();
        Deque<ResourceLocation> stack = new ArrayDeque<>();
        stack.push(modelLoc);
        while (!stack.isEmpty() && chain.size() <= MAX_PARENT_DEPTH) {
            ResourceLocation cur = stack.pop();
            if (!visited.add(cur)) {
                continue; // cycle
            }
            JsonObject m = models.get(cur);
            if (m == null) {
                continue;
            }
            chain.add(m);
            JsonElement parent = m.get("parent");
            if (parent == null || !parent.isJsonPrimitive()) {
                break;
            }
            String p = parent.getAsString();
            try {
                ResourceLocation parentLoc = p.indexOf(':') >= 0
                        ? ResourceLocation.parse(p)
                        : ResourceLocation.fromNamespaceAndPath("minecraft", p);
                stack.push(parentLoc);
            } catch (Exception ignore) {
                break;
            }
        }
        // parents first, child last
        java.util.Collections.reverse(chain);
        return chain;
    }
}
