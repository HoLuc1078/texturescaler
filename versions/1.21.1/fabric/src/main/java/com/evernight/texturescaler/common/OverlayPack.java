package com.evernight.texturescaler.common;

import com.evernight.texturescaler.core.ScalerEngine;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 1.21.1 flavour of the shared vanilla {@link PackResources} overlay.
 *
 * <p>The resource-serving half is the shared {@code common/src/main/java}
 * {@code TextureScalingPack} implementation (packId / getNamespaces / getResource /
 * listResources / getMetadataSection), which is shared with {@link OriginalReadGuard}.
 * Only {@link #createPack()} differs: Minecraft 1.21.1 replaced the 1.20.1 factory
 * {@code Pack.readMetaAndCreate(String, Component, boolean, Pack.ResourcesSupplier,
 * PackType, Position, PackSource)} with the record-style
 * {@code Pack.readMetaAndCreate(PackLocationInfo, Pack.ResourcesSupplier, PackType,
 * PackSelectionConfig)}, and {@code Pack.ResourcesSupplier} is no longer a functional
 * interface ({@code openPrimary}/{@code openFull} now take a {@link PackLocationInfo}).
 * The shared module must not be edited, so the incompatible shared file is excluded from
 * this module's source set and this module-local class is compiled instead.</p>
 */
public final class OverlayPack implements PackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PACK_ID = "texturescaler_overlay";

    private final ScalerEngine engine;
    private final OriginalReadGuard readGuard;
    private final PackLocationInfo location;
    private final byte[] packMeta;

    public OverlayPack(ScalerEngine engine, OriginalReadGuard readGuard, int packFormat) {
        this.engine = engine;
        this.readGuard = readGuard;
        this.location = new PackLocationInfo(
                PACK_ID,
                Component.literal("Texture Scaler"),
                PackSource.BUILT_IN,
                Optional.empty());
        this.packMeta = ("{\"pack\":{\"description\":{\"text\":\"Texture Scaler overlay\"},"
                + "\"pack_format\":" + packFormat + "}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Builds the (required, always-selected, top-priority) profile for this overlay — 1.21.1 API. */
    public Pack createPack() {
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo loc) {
                return OverlayPack.this;
            }

            @Override
            public PackResources openFull(PackLocationInfo loc, Pack.Metadata metadata) {
                return OverlayPack.this;
            }
        };
        return Pack.readMetaAndCreate(
                location,
                supplier,
                PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, false));
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public String packId() {
        return PACK_ID;
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return Set.of();
        }
        return engine.getNamespaces();
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        IoSupplier<InputStream> supplier = getRootResource("pack.mcmeta");
        if (supplier == null) {
            return null;
        }
        try (InputStream in = supplier.get()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonObject json = GsonHelper.parse(reader);
            if (!json.has(serializer.getMetadataSectionName())) {
                return null;
            }
            return serializer.fromJson(GsonHelper.getAsJsonObject(json, serializer.getMetadataSectionName()));
        } catch (Exception e) {
            LOGGER.error("[TextureScaler] couldn't load {} metadata", serializer.getMetadataSectionName(), e);
            return null;
        }
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            return () -> new ByteArrayInputStream(packMeta);
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
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
        return () -> new ByteArrayInputStream(png);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        if (!path.startsWith("textures/")) {
            return;
        }
        if (engine.isListing()) {
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
            output.accept(ResourceLocation.fromNamespaceAndPath(namespace, spritePath), () -> new ByteArrayInputStream(bytes));
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
