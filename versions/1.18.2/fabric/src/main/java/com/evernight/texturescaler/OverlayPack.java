package com.evernight.texturescaler;

import com.evernight.texturescaler.common.OriginalReadGuard;
import com.evernight.texturescaler.core.ScalerEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Version-local {@link PackResources} adapter for Minecraft 1.18.2.
 *
 * <p>The shared {@code common.TextureScalingPack} targets the 1.20.1 API
 * ({@code packId()}, {@code listResources(..., ResourceOutput)}, {@code IoSupplier}).
 * 1.18.2 has none of those: the pack is identified by {@link #getName()}, individual
 * resources are returned as raw {@link InputStream}s and directory enumeration goes
 * through the old {@code getResources(type, namespace, path, maxDepth, filter)} method.
 * The scaling logic is still the shared {@link ScalerEngine}.</p>
 *
 * <p>1.18.2 does not use {@code atlases/*.json} directory sources, so the block atlas
 * fetches every sprite through {@link #getResource}; {@link #getResources} is only there
 * for completeness / other directory listings.</p>
 */
public final class OverlayPack implements PackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PACK_ID = "texturescaler_overlay";

    private final ScalerEngine engine;
    private final OriginalReadGuard readGuard;
    private final byte[] packMeta;
    private final int packFormat;

    public OverlayPack(ScalerEngine engine, OriginalReadGuard readGuard, int packFormat) {
        this.engine = engine;
        this.readGuard = readGuard;
        this.packFormat = packFormat;
        this.packMeta = ("{\"pack\":{\"description\":{\"text\":\"Texture Scaler overlay\"},"
                + "\"pack_format\":" + packFormat + "}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Builds the (required, always-selected, top-priority) profile for this overlay. */
    public Pack createPack() {
        return new Pack(
                PACK_ID,
                true,
                () -> this,
                new TextComponent("Texture Scaler"),
                new TextComponent("Texture Scaler overlay"),
                PackCompatibility.COMPATIBLE,
                Pack.Position.TOP,
                false,
                PackSource.BUILT_IN);
    }

    @Override
    public String getName() {
        return PACK_ID;
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return Collections.emptySet();
        }
        return engine.getNamespaces();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        if (!"pack".equals(serializer.getMetadataSectionName())) {
            return null;
        }
        try {
            return (T) new PackMetadataSection(new TextComponent("Texture Scaler overlay"), packFormat);
        } catch (Exception e) {
            LOGGER.error("[TextureScaler] couldn't load {} metadata", serializer.getMetadataSectionName(), e);
            return null;
        }
    }

    @Override
    public InputStream getRootResource(String path) {
        if ("pack.mcmeta".equals(path)) {
            return new ByteArrayInputStream(packMeta);
        }
        return null;
    }

    @Override
    public InputStream getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }
        // While the platform is reading an original, step aside so the lower packs win.
        if (readGuard.isReadingOriginal()) {
            return null;
        }
        byte[] png = engine.getScaledResource(location.getNamespace(), location.getPath());
        if (png == null) {
            return null;
        }
        return new ByteArrayInputStream(png);
    }

    @Override
    public boolean hasResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || readGuard.isReadingOriginal()) {
            return false;
        }
        return engine.getScaledResource(location.getNamespace(), location.getPath()) != null;
    }

    @Override
    public Collection<ResourceLocation> getResources(PackType type, String namespace, String path,
                                                    int maxDepth, Predicate<String> filter) {
        if (type != PackType.CLIENT_RESOURCES || !path.startsWith("textures")) {
            return Collections.emptySet();
        }
        // Never re-enter our own listing (ScalerEngine.listAllTextures walks this method).
        if (engine.isListing()) {
            return Collections.emptySet();
        }
        Map<String, byte[]> scaled = engine.getListedScaled();
        if (scaled.isEmpty()) {
            return Collections.emptySet();
        }
        String prefix = namespace + ":" + path + "/";
        Set<ResourceLocation> out = new HashSet<>();
        int emitted = 0;
        for (String key : scaled.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String spritePath = key.substring(namespace.length() + 1);
            ResourceLocation rl = new ResourceLocation(namespace, spritePath);
            if (filter == null || filter.test(spritePath)) {
                out.add(rl);
                emitted++;
            }
        }
        if (emitted > 0) {
            LOGGER.info("[TextureScaler] getResources(ns={}, path={}): emitted {} scaled textures",
                    namespace, path, emitted);
        }
        return out;
    }

    @Override
    public void close() {
        // singleton per module - nothing to close
    }
}