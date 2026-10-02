package com.evernight.texturescaler.common;

import com.evernight.texturescaler.core.ScalerEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vanilla-only {@link PackResources} adapter for the "Identifier era" (1.21.11 and 26.x).
 */
public final class TextureScalingPack extends AbstractPackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PACK_ID = "texturescaler_overlay";

    /**
     * Avoids backslash escapes in the hand-built pack.mcmeta.
     */
    private static final char Q = '"';

    private final ScalerEngine engine;
    private final OriginalReadGuard readGuard;
    private final byte[] packMeta;

    public TextureScalingPack(ScalerEngine engine, OriginalReadGuard readGuard, int packFormat) {
        super(new PackLocationInfo(PACK_ID, Component.literal("Texture Scaler"), PackSource.BUILT_IN, Optional.empty()));
        this.engine = engine;
        this.readGuard = readGuard;
        String meta = "{" + Q + "pack" + Q + ":{" + Q + "description" + Q + ":{" + Q + "text" + Q + ":"
                + Q + "Texture Scaler overlay" + Q + "}," + Q + "pack_format" + Q + ":" + packFormat + "}}";
        this.packMeta = meta.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the (required, always-selected, top-priority) profile for this overlay.
     */
    public Pack createPack() {
        return Pack.readMetaAndCreate(
                location(),
                new Pack.ResourcesSupplier() {
                    @Override
                    public PackResources openPrimary(PackLocationInfo info) {
                        return TextureScalingPack.this;
                    }

                    @Override
                    public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                        return TextureScalingPack.this;
                    }
                },
                PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, false));
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return Set.of();
        }
        return engine.getNamespaces();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            return () -> new ByteArrayInputStream(packMeta);
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }
        // While the platform is reading an original, step aside so the lower packs win.
        if (readGuard.isReadingOriginal() || !engine.isEnabled()) {
            return null;
        }
        byte[] png = engine.getScaledResource(location.getNamespace(), location.getPath());
        if (png == null) {
            return null;
        }
        return () -> new ByteArrayInputStream(png);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        if (!engine.isEnabled() || engine.isListing()) {
            return;
        }
        if (!path.startsWith("textures/")) {
            return;
        }
        Map<String, byte[]> scaled = engine.getListedScaled();
        if (scaled.isEmpty()) {
            return;
        }
        String prefix = namespace + ":" + path + "/";
        int emitted = 0;
        for (Map.Entry<String, byte[]> e : scaled.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            byte[] bytes = e.getValue();
            String spritePath = key.substring(namespace.length() + 1);
            output.accept(Identifier.fromNamespaceAndPath(namespace, spritePath),
                    () -> new ByteArrayInputStream(bytes));
            emitted++;
        }
        if (emitted > 0) {
            LOGGER.info("[TextureScaler] listResources(ns={}, path={}): emitted {} scaled textures",
                    namespace, path, emitted);
        }
    }

    @Override
    public void close() {
        // singleton per module — nothing to close
    }
}
