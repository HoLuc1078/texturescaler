package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.util.GsonHelper;

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
import java.util.function.Supplier;

/**
 * 1.16.5-local {@code PackResources} adapter compiled against official Mojang mappings.
 *
 * <p>1.16.5 semantics differ from 1.18+ in three ways that matter to the overlay:</p>
 * <ul>
 *   <li>the directory lister is
 *       {@code getResources(PackType, String, String, int, Predicate<String>)} and it
 *       <em>returns</em> locations instead of receiving an output sink;</li>
 *   <li>{@code hasResource} is abstract and is the gate used by {@code FallbackResourceManager},
 *       so it must answer "yes" for every texture we serve;</li>
 *   <li>resources are plain {@code InputStream}s (no {@code IoSupplier}).</li>
 * </ul>
 */
public final class LocalTextureScalingPack implements PackResources {

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

    /** Builds the (required, always-selected, highest-priority) pack profile for this overlay. */
    public Pack createPack(Pack.PackConstructor constructor) {
        Supplier<PackResources> supplier = new Supplier<PackResources>() {
            @Override public PackResources get() { return LocalTextureScalingPack.this; }
        };
        return Pack.create(PACK_ID, true, supplier, constructor, Pack.Position.TOP, PackSource.BUILT_IN);
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

    @Override
    public InputStream getRootResource(String name) throws IOException {
        if ("pack.mcmeta".equals(name)) {
            return new ByteArrayInputStream(packMeta);
        }
        return null;
    }

    @Override
    public InputStream getResource(PackType type, ResourceLocation location) throws IOException {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }
        if (readGuard.isReadingOriginal() || engine.isListing()) {
            return null;
        }
        byte[] png = engine.getScaledResource(location.getNamespace(), location.getPath());
        return png == null ? null : new ByteArrayInputStream(png);
    }

    @Override
    public boolean hasResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES) {
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
    public Collection<ResourceLocation> getResources(PackType type, String namespace,
                                                     String path, int maxDepth, Predicate<String> filter) {
        if (type != PackType.CLIENT_RESOURCES || engine.isListing()) {
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
            TextureScalerFabric.LOGGER.info("[TextureScaler] getResources(ns={}, path={}): {} scaled textures",
                    namespace, path, out.size());
        }
        return out;
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        InputStream in = getRootResource("pack.mcmeta");
        if (in == null) {
            return null;
        }
        try (InputStream stream = in) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            JsonObject json = GsonHelper.parse(reader);
            String name = serializer.getMetadataSectionName();
            if (!json.has(name)) {
                return null;
            }
            return serializer.fromJson(GsonHelper.getAsJsonObject(json, name));
        } catch (Exception e) {
            TextureScalerFabric.LOGGER.error("[TextureScaler] couldn't load {} metadata",
                    serializer.getMetadataSectionName(), e);
            return null;
        }
    }

    @Override
    public void close() {
        // singleton per module — nothing to close
    }
}
