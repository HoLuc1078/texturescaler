package com.evernight.texturescaler;

import com.evernight.texturescaler.core.DiskCache;
import com.evernight.texturescaler.core.ScalerConfig;
import com.evernight.texturescaler.core.ScalerLog;
import com.evernight.texturescaler.core.ScalerPlatform;
import com.evernight.texturescaler.core.TextureHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraft.resources.IResourcePack;
import net.minecraft.resources.ResourcePackType;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
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

/**
 * Forge 1.16.5 implementation of the shared {@link ScalerPlatform} (MCP mappings).
 */
final class ForgePlatform implements ScalerPlatform {

    private static final Logger LOGGER = TextureScaler.LOGGER;

    /**
     * Guards against our own pack being consulted while we read an original.
     */
    private static final ThreadLocal<Boolean> READING_ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ScalerLog log = new ScalerLog() {
        @Override public void info(String message, Object... args) { LOGGER.info(message, args); }
        @Override public void warn(String message, Object... args) { LOGGER.warn(message, args); }
        @Override public void error(String message, Object... args) { LOGGER.error(message, args); }
    };

    private volatile ScalerConfig cachedConfig;

    @Override
    public ScalerLog log() {
        return log;
    }

    @Override
    public ScalerConfig config() {
        ScalerConfig c = cachedConfig;
        if (c == null) {
            c = Config.snapshot();
            cachedConfig = c;
        }
        return c;
    }

    void invalidateConfig() {
        cachedConfig = null;
    }

    boolean isReadingOriginal() {
        return READING_ORIGINAL.get();
    }

    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
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
        IResourceManager rm = resourceManager();
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
        IResourceManager rm = resourceManager();
        if (rm == null) {
            return Collections.emptyMap();
        }
        Collection<ResourceLocation> all = rm.listResources("models", name -> name.endsWith(".json"));
        Map<String, String> out = new HashMap<String, String>(all.size() * 2);
        for (ResourceLocation location : all) {
            try (InputStream in = rm.getResource(location).getInputStream()) {
                out.put(location.toString(), new String(DiskCache.readAll(in), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // unreadable model — skipped, it cannot constrain anything
            }
        }
        return out;
    }

    @Override
    public byte[] readOriginal(String namespace, String path) {
        if (READING_ORIGINAL.get()) {
            return null;
        }
        IResourceManager rm = resourceManager();
        if (rm == null) {
            return null;
        }
        READING_ORIGINAL.set(Boolean.TRUE);
        try {
            IResource resource = rm.getResource(new ResourceLocation(namespace, path));
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
        IResourceManager rm = resourceManager();
        if (rm != null) {
            List<IResourcePack> packs;
            try {
                packs = rm.listPacks().collect(java.util.stream.Collectors.toList());
            } catch (Throwable t) {
                packs = Collections.emptyList();
            }
            for (IResourcePack pack : packs) {
                if (pack instanceof LocalTextureScalingPack) {
                    continue;
                }
                try {
                    result.addAll(pack.getNamespaces(ResourcePackType.CLIENT_RESOURCES));
                } catch (Exception ignored) {
                    // pack refused to enumerate — skip it
                }
            }
        }
        // Safety net for the very first reload, where packs are registered one by one and
        // the list is only complete once our (last-added) overlay is queried.
        try {
            net.minecraftforge.fml.ModList.get().forEachModContainer((id, container) -> result.add(id));
        } catch (Throwable ignored) {
            // ModList not ready — not fatal
        }
        return result;
    }

    @Override
    public String packFingerprint() {
        IResourceManager rm = resourceManager();
        if (rm == null) {
            return null;
        }
        List<String> ids = new ArrayList<String>();
        try {
            for (IResourcePack pack : rm.listPacks().collect(java.util.stream.Collectors.toList())) {
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

    private static IResourceManager resourceManager() {
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
            IResourceManager rm = resourceManager();
            if (rm == null) {
                throw new IOException("no resource manager");
            }
            return rm.getResource(location).getInputStream();
        }
    }
}
