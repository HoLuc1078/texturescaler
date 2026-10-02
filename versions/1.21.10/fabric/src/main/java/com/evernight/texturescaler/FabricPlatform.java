package com.evernight.texturescaler;

import com.evernight.texturescaler.common.TextureScalingPack;
import com.evernight.texturescaler.core.DiskCache;
import com.evernight.texturescaler.core.ScalerConfig;
import com.evernight.texturescaler.core.ScalerLog;
import com.evernight.texturescaler.core.ScalerPlatform;
import com.evernight.texturescaler.core.TextureHandle;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fabric 1.21.4 implementation of the shared {@link ScalerPlatform}.
 */
final class FabricPlatform implements ScalerPlatform {

    private static final Logger LOGGER = TextureScalerFabric.LOGGER;

    private static final ThreadLocal<Boolean> READING_ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ScalerLog log = new ScalerLog() {
        @Override public void info(String message, Object... args) { LOGGER.info(message, args); }
        @Override public void warn(String message, Object... args) { LOGGER.warn(message, args); }
        @Override public void error(String message, Object... args) { LOGGER.error(message, args); }
    };

    @Override
    public ScalerLog log() {
        return log;
    }

    @Override
    public ScalerConfig config() {
        return FabricConfig.snapshot();
    }

    boolean isReadingOriginal() {
        return READING_ORIGINAL.get();
    }

    @Override
    public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    /**
     * Result of the one successful GL query, or 0 while the GL context is not ready yet.
     */
    private int cachedGpuMaxTextureSize;

    @Override
    public int gpuMaxTextureSize() {
        int cached = cachedGpuMaxTextureSize;
        if (cached > 0) {
            return cached;
        }
        // Never touch a GL entry point before the render backend created the capabilities:
        // LWJGL would call through an uninitialised function table and the JVM dies in
        try {
            if (GL.getCapabilities() == null) {
                return 0;
            }
        } catch (Throwable notReadyYet) {
            return 0;
        }
        // 1.21.5 removed RenderSystem#maxSupportedTextureSize, so the raw GL query is the
        // only portable path left.
        try {
            cachedGpuMaxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        } catch (Throwable t) {
            return 0;
        }
        return cachedGpuMaxTextureSize > 0 ? cachedGpuMaxTextureSize : 0;
    }

    @Override
    public List<TextureHandle> listAllTextures() {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return Collections.emptyList();
        }
        Map<ResourceLocation, Resource> all =
                rm.listResources("textures", loc -> loc.getPath().endsWith(".png"));
        List<TextureHandle> out = new ArrayList<>(all.size());
        for (Map.Entry<ResourceLocation, Resource> e : all.entrySet()) {
            out.add(new Handle(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public Map<String, String> listModels() {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return Collections.emptyMap();
        }
        Map<ResourceLocation, Resource> all =
                rm.listResources("models", loc -> loc.getPath().endsWith(".json"));
        Map<String, String> out = new HashMap<>(all.size() * 2);
        for (Map.Entry<ResourceLocation, Resource> e : all.entrySet()) {
            try (InputStream in = e.getValue().open()) {
                out.put(e.getKey().toString(), new String(DiskCache.readAll(in), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // unreadable model — skipped
            }
        }
        return out;
    }

    @Override
    public byte[] readOriginal(String namespace, String path) {
        if (READING_ORIGINAL.get()) {
            return null;
        }
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return null;
        }
        READING_ORIGINAL.set(Boolean.TRUE);
        try {
            Optional<Resource> resource =
                    rm.getResource(ResourceLocation.fromNamespaceAndPath(namespace, path));
            if (resource.isPresent()) {
                try (InputStream in = resource.get().open()) {
                    return DiskCache.readAll(in);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            READING_ORIGINAL.set(Boolean.FALSE);
        }
    }

    @Override
    public Set<String> claimedNamespaces() {
        Set<String> result = new HashSet<>();
        ResourceManager rm = resourceManager();
        if (rm != null) {
            for (PackResources pack : rm.listPacks().collect(Collectors.toList())) {
                if (pack instanceof TextureScalingPack) {
                    continue;
                }
                try {
                    result.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES));
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        // While the new MultiPackResourceManager is being constructed the ReloadableResourceManager
        // has not assigned it yet, so listPacks() above still returns the previous (first-reload:
        // empty) manager. The pack repository is already populated then, so use it as a fallback;
        // otherwise the overlay claims no namespace and is never consulted (nothing downscales).
        collectRepositoryNamespaces(result);
        return result;
    }

    /**
     * Namespaces discovered from the pack repository (works before the reload manager exists).
     */
    private static void collectRepositoryNamespaces(Set<String> result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourcePackRepository() == null) {
            return;
        }
        try {
            for (Pack pack : mc.getResourcePackRepository().getSelectedPacks()) {
                try (PackResources resources = pack.open()) {
                    if (resources instanceof TextureScalingPack) {
                        continue;
                    }
                    result.addAll(resources.getNamespaces(PackType.CLIENT_RESOURCES));
                } catch (Exception ignored) {
                    // this pack refused to enumerate — skip it
                }
            }
        } catch (Exception ignored) {
            // repository not ready — the live-manager path above is all we have
        }
    }

    @Override
    public String packFingerprint() {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (PackResources pack : rm.listPacks().collect(Collectors.toList())) {
            try {
                ids.add(pack.packId());
            } catch (Exception e) {
                ids.add("?");
            }
        }
        Collections.sort(ids);
        return String.join(",", ids);
    }

    private static ResourceManager resourceManager() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getResourceManager();
    }

    private static final class Handle implements TextureHandle {
        private final ResourceLocation location;
        private final Resource resource;

        Handle(ResourceLocation location, Resource resource) {
            this.location = location;
            this.resource = resource;
        }

        @Override public String namespace() { return location.getNamespace(); }
        @Override public String path() { return location.getPath(); }
        @Override public InputStream open() throws IOException { return resource.open(); }
    }
}
