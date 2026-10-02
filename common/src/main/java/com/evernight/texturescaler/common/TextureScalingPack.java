package com.evernight.texturescaler.common;

import com.evernight.texturescaler.core.ScalerEngine;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
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
import java.util.Set;

/**
 * Loader-independent {@link PackResources} adapter (vanilla API only).
 *
 * <p>The block atlas enumerates textures through {@code listResources} (via the
 * {@code atlases/blocks.json} directory sources), while individual lookups go through
 * {@code getResource}; both delegate to the shared {@link ScalerEngine}. Because this class
 * references only vanilla classes it is shared verbatim by the Forge, NeoForge and Fabric
 * modules (the latter compiled with official Mojang mappings).</p>
 *
 * <p>Not used on 1.16.5, where the atlas still reads sprites one by one and the interface
 * method is named {@code getResources}.</p>
 */
public final class TextureScalingPack implements PackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PACK_ID = "texturescaler_overlay";

    private final ScalerEngine engine;
    private final OriginalReadGuard readGuard;
    private final byte[] packMeta;

    public TextureScalingPack(ScalerEngine engine, OriginalReadGuard readGuard, int packFormat) {
        this.engine = engine;
        this.readGuard = readGuard;
        this.packMeta = ("{\"pack\":{\"description\":{\"text\":\"Texture Scaler overlay\"},"
                + "\"pack_format\":" + packFormat + "}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Builds the (required, always-selected, top-priority) profile for this overlay. */
    public Pack createPack() {
        return Pack.readMetaAndCreate(
                PACK_ID,
                Component.literal("Texture Scaler"),
                true,
                id -> this,
                PackType.CLIENT_RESOURCES,
                Pack.Position.TOP,
                PackSource.BUILT_IN);
    }

    @Override
    public String packId() {
        return PACK_ID;
    }

    // Note: no isHidden() override — it does not exist in vanilla 1.20.1 PackResources
    // (Forge adds it; NeoForge/1.21 vanilla does). The pack is required + BUILT_IN, so it
    // is always active regardless.

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
        if (!engine.isEnabled()) {
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
            output.accept(new ResourceLocation(namespace, spritePath), () -> new ByteArrayInputStream(bytes));
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
