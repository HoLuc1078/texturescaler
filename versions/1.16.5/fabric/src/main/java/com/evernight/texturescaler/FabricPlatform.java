package com.evernight.texturescaler;

import com.evernight.texturescaler.core.DiskCache;
import com.evernight.texturescaler.core.ScalerConfig;
import com.evernight.texturescaler.core.ScalerLog;
import com.evernight.texturescaler.core.ScalerPlatform;
import com.evernight.texturescaler.core.TextureHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fabric 1.16.5 implementation of the shared {@link ScalerPlatform} (official mappings).
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
        try {
            cachedGpuMaxTextureSize = RenderSystem.maxSupportedTextureSize();
        } catch (Throwable t) {
            try {
                cachedGpuMaxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            } catch (Throwable t2) {
                return 0;
            }
        }
        return cachedGpuMaxTextureSize > 0 ? cachedGpuMaxTextureSize : 0;
    }

    @Override
    public List<TextureHandle> listAllTextures() {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return Collections.emptyList();
        }
        Collection<ResourceLocation> all = rm.listResources("textures", name -> name.endsWith(".png"));
        List<TextureHandle> out = new ArrayList<TextureHandle>(all.size());
        for (ResourceLocation location : all) {
            out.add(new Handle(location));
        }
        return out;
    }

    @Override
    public Map<String, String> listModels() {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return Collections.emptyMap();
        }
        Collection<ResourceLocation> all = rm.listResources("models", name -> name.endsWith(".json"));
        Map<String, String> out = new HashMap<String, String>(all.size() * 2);
        for (ResourceLocation location : all) {
            try (InputStream in = rm.getResource(location).getInputStream()) {
                out.put(location.toString(), new String(DiskCache.readAll(in), StandardCharsets.UTF_8));
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
            Resource resource = rm.getResource(new ResourceLocation(namespace, path));
            try (InputStream in = resource.getInputStream()) {
                return DiskCache.readAll(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            READING_ORIGINAL.set(Boolean.FALSE);
        }
    }

    @Override
    public Set<String> claimedNamespaces() {
        Set<String> result = new HashSet<String>();
        ResourceManager rm = resourceManager();
        if (rm != null) {
            List<PackResources> packs;
            try {
                packs = rm.listPacks().collect(Collectors.toList());
            } catch (Throwable t) {
                packs = Collections.emptyList();
            }
            for (PackResources pack : packs) {
                if (pack instanceof LocalTextureScalingPack) {
                    continue;
                }
                try {
                    result.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES));
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        // Safety net for the very first reload, where packs are registered one by one and
        // the list is only complete once our (last-added) overlay is queried.
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                result.add(mod.getMetadata().getId());
            }
        } catch (Throwable ignored) {
            // loader not ready — not fatal
        }
        return result;
    }

    @Override
    public String packFingerprint() {
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return null;
        }
        List<String> ids = new ArrayList<String>();
        try {
            for (PackResources pack : rm.listPacks().collect(Collectors.toList())) {
                try {
                    ids.add(pack.getName());
                } catch (Exception e) {
                    ids.add("?");
                }
            }
        } catch (Throwable ignored) {
            return null;
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

        Handle(ResourceLocation location) {
            this.location = location;
        }

        @Override public String namespace() { return location.getNamespace(); }
        @Override public String path() { return location.getPath(); }
        @Override public InputStream open() throws IOException {
            ResourceManager rm = resourceManager();
            if (rm == null) {
                throw new IOException("no resource manager");
            }
            return rm.getResource(location).getInputStream();
        }
    }
}
