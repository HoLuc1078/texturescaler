package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import com.google.gson.JsonObject;
import net.minecraft.resources.IResourcePack;
import net.minecraft.resources.ResourcePackType;
import net.minecraft.resources.data.IMetadataSectionSerializer;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 1.16.1-local {@code IResourcePack} adapter.
 */
public final class LocalTextureScalingPack implements IResourcePack {

    public static final String PACK_ID = "texturescaler_overlay";

    private final ScalerEngine engine;
    private final OriginalReadGuard readGuard;
    private final byte[] packMeta;

    public LocalTextureScalingPack(ScalerEngine engine, OriginalReadGuard readGuard, int packFormat) {
        this.engine = engine;
        this.readGuard = readGuard;
        this.packMeta = ("{\"pack\":{\"description\":{\"text\":\"Texture Scaler overlay\"},"
                + "\"pack_format\":" + packFormat + "}}").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getName() {
        return PACK_ID;
    }

    @Override
    public Set<String> getNamespaces(ResourcePackType type) {
        if (type != ResourcePackType.CLIENT_RESOURCES) {
            return Collections.emptySet();
        }
        return engine.getNamespaces();
    }

    @Override
    public InputStream getRootResource(String name) throws IOException {
        if ("pack.mcmeta".equals(name)) {
            return new ByteArrayInputStream(packMeta);
        }
        return null;
    }

    @Override
    public InputStream getResource(ResourcePackType type, ResourceLocation location) throws IOException {
        if (type != ResourcePackType.CLIENT_RESOURCES) {
            return null;
        }
        if (readGuard.isReadingOriginal() || engine.isListing()) {
            return null;
        }
        byte[] png = engine.getScaledResource(location.getNamespace(), location.getPath());
        return png == null ? null : new ByteArrayInputStream(png);
    }

    @Override
    public boolean hasResource(ResourcePackType type, ResourceLocation location) {
        if (type != ResourcePackType.CLIENT_RESOURCES) {
            return false;
        }
        // While the platform reads an original, or while the engine enumerates the stack,
        // step aside so the lower packs win.
        if (readGuard.isReadingOriginal() || engine.isListing()) {
            return false;
        }
        return engine.getScaledResource(location.getNamespace(), location.getPath()) != null;
    }

    @Override
    public Collection<ResourceLocation> getResources(ResourcePackType type, String namespace,
                                                     String path, int maxDepth, Predicate<String> filter) {
        if (type != ResourcePackType.CLIENT_RESOURCES || engine.isListing()) {
            return Collections.emptyList();
        }
        if (!path.startsWith("textures")) {
            return Collections.emptyList();
        }
        Map<String, byte[]> scaled = engine.getListedScaled();
        if (scaled.isEmpty()) {
            return Collections.emptyList();
        }
        String prefix = namespace + ":" + path + "/";
        String pathPrefix = path + "/";
        List<ResourceLocation> out = new ArrayList<ResourceLocation>();
        for (String key : scaled.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String relative = key.substring(namespace.length() + 1);
            if (!relative.startsWith(pathPrefix)) {
                continue;
            }
            String[] parts = relative.substring(pathPrefix.length()).split("/");
            if (parts.length < maxDepth + 1) {
                continue;
            }
            if (filter != null && !filter.test(parts[parts.length - 1])) {
                continue;
            }
            out.add(new ResourceLocation(namespace, relative));
        }
        if (!out.isEmpty()) {
            TextureScaler.LOGGER.info("[TextureScaler] getResources(ns={}, path={}): {} scaled textures",
                    namespace, path, out.size());
        }
        return out;
    }

    @Override
    public <T> T getMetadataSection(IMetadataSectionSerializer<T> serializer) throws IOException {
        InputStream in = getRootResource("pack.mcmeta");
        if (in == null) {
            return null;
        }
        try (InputStream stream = in) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            JsonObject json = JSONUtils.parse(reader);
            String name = serializer.getMetadataSectionName();
            if (!json.has(name)) {
                return null;
            }
            return serializer.fromJson(JSONUtils.getAsJsonObject(json, name));
        } catch (Exception e) {
            TextureScaler.LOGGER.error("[TextureScaler] couldn't load {} metadata",
                    serializer.getMetadataSectionName(), e);
            return null;
        }
    }

    @Override
    public void close() {
        // singleton per module — nothing to close
    }
}
