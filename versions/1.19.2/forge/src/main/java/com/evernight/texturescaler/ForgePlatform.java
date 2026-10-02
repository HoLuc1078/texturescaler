package com.evernight.texturescaler;

import com.evernight.texturescaler.core.DiskCache;
import com.evernight.texturescaler.core.ScalerConfig;
import com.evernight.texturescaler.core.ScalerLog;
import com.evernight.texturescaler.core.ScalerPlatform;
import com.evernight.texturescaler.core.TextureHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Forge 1.19.2 implementation of the shared {@link ScalerPlatform}. */
final class ForgePlatform implements ScalerPlatform {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Guards against our own pack being consulted while we read an original. */
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

    /** Result of the one successful GL query, or 0 while the GL context is not ready yet. */
    private int cachedGpuMaxTextureSize;

    @Override
    public int gpuMaxTextureSize() {
        int cached = cachedGpuMaxTextureSize;
        if (cached > 0) {
            return cached;
        }
        // Never touch a GL entry point before the render backend created the capabilities:
        // LWJGL would call through an uninitialised function table and the JVM dies in
        // native code (EXCEPTION_ACCESS_VIOLATION in lwjgl_opengl.dll), where no Java catch
        // can help. GL.getCapabilities() throws (or returns null when LWJGL checks are off)
        // in exactly that state, and both are safe to observe.
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
        // 1.18.2 lists locations only; the bytes are resolved lazily through openOriginal,
        // which bypasses our own pack via the reading guard.
        Map<ResourceLocation, Resource> all =
                rm.listResources("textures", loc -> loc.getPath().endsWith(".png"));
        List<TextureHandle> out = new ArrayList<>(all.size());
        for (ResourceLocation rl : all.keySet()) {
            out.add(new Handle(this, rl));
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
        for (ResourceLocation rl : all.keySet()) {
            byte[] bytes = readOriginal(rl.getNamespace(), rl.getPath());
            if (bytes != null) {
                out.put(rl.toString(), new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** Opens one resource from the packs below the overlay (1.18.2 returns a nullable Resource). */
    InputStream openOriginal(String namespace, String path) {
        if (READING_ORIGINAL.get()) {
            return null;
        }
        ResourceManager rm = resourceManager();
        if (rm == null) {
            return null;
        }
        READING_ORIGINAL.set(Boolean.TRUE);
        try {
            Optional<Resource> optional = rm.getResource(new ResourceLocation(namespace, path));
            return optional.isPresent() ? optional.get().open() : null;
        } catch (Exception e) {
            return null;
        } finally {
            READING_ORIGINAL.set(Boolean.FALSE);
        }
    }

    @Override
    public byte[] readOriginal(String namespace, String path) {
        InputStream in = openOriginal(namespace, path);
        if (in == null) {
            return null;
        }
        try {
            return DiskCache.readAll(in);
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    @Override
    public Set<String> claimedNamespaces() {
        Set<String> result = new HashSet<>();
        ResourceManager rm = resourceManager();
        if (rm != null) {
            for (PackResources pack : rm.listPacks().collect(Collectors.toList())) {
                if (pack instanceof OverlayPack) {
                    continue;
                }
                try {
                    result.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES));
                } catch (Exception ignored) {
                    // pack refused to enumerate - skip it
                }
            }
        }
        // Safety net for the first reload: the new MultiPackResourceManager is constructed before
        // any reload listener runs, so the live manager above is still the previous (empty) one.
        // The mod list is already complete at that point, and mod ids are the namespaces the
        // block atlas asks about.
        try {
            net.minecraftforge.fml.ModList.get().forEachModContainer((id, container) -> result.add(id));
        } catch (Throwable ignored) {
            // ModList not ready - not fatal
        }
        return result;
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
                ids.add(pack.getName());
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
        private final ForgePlatform platform;
        private final ResourceLocation location;

        Handle(ForgePlatform platform, ResourceLocation location) {
            this.platform = platform;
            this.location = location;
        }

        @Override public String namespace() { return location.getNamespace(); }
        @Override public String path() { return location.getPath(); }

        @Override
        public InputStream open() throws IOException {
            InputStream in = platform.openOriginal(location.getNamespace(), location.getPath());
            if (in == null) {
                throw new IOException("missing original " + location);
            }
            return in;
        }
    }
}