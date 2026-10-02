package com.evernight.texturescaler;

import com.evernight.texturescaler.core.DiskCache;
import com.evernight.texturescaler.core.ScalerConfig;
import com.evernight.texturescaler.core.ScalerLog;
import com.evernight.texturescaler.core.ScalerPlatform;
import com.evernight.texturescaler.core.TextureHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.12.2 (MCP stable_39) implementation of the shared {@link ScalerPlatform}.
 *
 * <p>Differences from the 1.16+ implementations that matter here:</p>
 * <ul>
 *   <li>{@code IResourceManager} has no directory listing API, so
 *       {@link #listAllTextures()} is empty and the engine's atlas-listing path is never
 *       used. The 1.12.2 block atlas reads every sprite through
 *       {@code IResourceManager.getResource}, which is exactly the path the overlay pack
 *       intercepts.</li>
 *   <li>Texture paths are {@code textures/blocks/...} and {@code textures/items/...}
 *       (plural); {@link Config} feeds those in via {@code extraTextureDirs}.</li>
 *   <li>MCP mappings are stable_39, so the names are {@code getNamespace()}/{@code getPath()}
 *       on {@link ResourceLocation}.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
final class ForgePlatform implements ScalerPlatform {

    private static final Logger LOGGER = LogManager.getLogger(TextureScaler.MODID);

    /** Guards against our own pack being consulted while an original is read. */
    private static final ThreadLocal<Boolean> READING_ORIGINAL = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private final ScalerLog log = new ScalerLog() {
        @Override
        public void info(String message, Object... args) {
            LOGGER.info(message, args);
        }

        @Override
        public void warn(String message, Object... args) {
            LOGGER.warn(message, args);
        }

        @Override
        public void error(String message, Object... args) {
            LOGGER.error(message, args);
        }
    };

    private volatile ScalerConfig cachedConfig;
    private volatile Set<String> claimedNamespaces;
    private volatile Map<String, Integer> modelUvConstraints;

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
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? new File(".").toPath() : mc.gameDir.toPath();
    }

    /** Result of the one successful GL query, or 0 while the GL context is not ready yet. */
    private int cachedGpuMaxTextureSize;

    @Override
    public int gpuMaxTextureSize() {
        int cached = cachedGpuMaxTextureSize;
        if (cached > 0) {
            return cached;
        }
        // Never touch a GL entry point before a context is current: LWJGL 2 keeps the
        // function pointers in the context capabilities, so a call before the display is
        // created dereferences an uninitialised table and kills the JVM in native code,
        // where no Java catch can help. GLContext.getCapabilities() is safe to observe.
        try {
            if (GLContext.getCapabilities() == null) {
                return 0;
            }
        } catch (Throwable notReadyYet) {
            return 0;
        }
        try {
            int reported = Minecraft.getGLMaximumTextureSize();
            if (reported > 0) {
                cachedGpuMaxTextureSize = reported;
                return reported;
            }
        } catch (Throwable ignored) {
            // fall through to the raw GL query
        }
        try {
            cachedGpuMaxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        } catch (Throwable t) {
            return 0;
        }
        return cachedGpuMaxTextureSize > 0 ? cachedGpuMaxTextureSize : 0;
    }

    /**
     * 1.12.2's {@code IResourceManager} exposes no "list everything under a directory"
     * call, so the engine's merged-listing path is unused on this version. The block
     * atlas loads each sprite individually through {@code getResource} instead, which the
     * overlay pack handles.
     */
    @Override
    public List<TextureHandle> listAllTextures() {
        return Collections.emptyList();
    }

    /**
     * Unused: {@link ScalerEngine#scanModelUvConstraints()} would route through the
     * shared {@code ModelScanner}, which uses Gson's {@code JsonParser.parseReader}
     * (added in Gson 2.8.6). 1.12.2 ships Gson 2.8.0, so this module computes the
     * constraints with {@link LegacyModelScanner} and pushes them in via
     * {@link ScalerEngine#setModelUvConstraints(Map)} instead.
     */
    @Override
    public Map<String, String> listModels() {
        return Collections.emptyMap();
    }

    @Override
    public byte[] readOriginal(String namespace, String path) {
        if (READING_ORIGINAL.get()) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        IResourceManager rm = mc.getResourceManager();
        if (rm == null) {
            return null;
        }
        READING_ORIGINAL.set(Boolean.TRUE);
        try {
            IResource resource = rm.getResource(new ResourceLocation(namespace, path));
            if (resource == null) {
                return null;
            }
            InputStream in = resource.getInputStream();
            if (in == null) {
                return null;
            }
            try {
                return DiskCache.readAll(in);
            } finally {
                try {
                    resource.close();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            READING_ORIGINAL.set(Boolean.FALSE);
        }
    }

    @Override
    public Set<String> claimedNamespaces() {
        Set<String> cached = claimedNamespaces;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return seedNamespacesFromMods();
    }

    /**
     * Re-reads the authoritative namespace set from the live resource manager. Must only
     * be called when the resource manager is fully built (preInit and the texture stitch
     * events); never from inside our own pack's {@code getResourceDomains()}, which the
     * manager invokes while it is still rebuilding.
     */
    Set<String> refreshClaimedNamespaces() {
        Set<String> result = new HashSet<String>();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getResourceManager() != null) {
                result.addAll(mc.getResourceManager().getResourceDomains());
            }
        } catch (Throwable ignored) {
            // fall back to the mod ids below
        }
        if (result.isEmpty()) {
            return seedNamespacesFromMods();
        }
        result.remove(TextureScaler.MODID);
        Set<String> immutable = Collections.unmodifiableSet(result);
        claimedNamespaces = immutable;
        return immutable;
    }

    /** Safe fallback that never touches the resource manager (no re-entrancy). */
    private Set<String> seedNamespacesFromMods() {
        Set<String> result = new HashSet<String>();
        try {
            for (ModContainer container : Loader.instance().getActiveModList()) {
                result.add(container.getModId());
            }
        } catch (Throwable ignored) {
            // Loader not up yet
        }
        result.remove(TextureScaler.MODID);
        Set<String> immutable = Collections.unmodifiableSet(result);
        claimedNamespaces = immutable;
        return immutable;
    }

    @Override
    public String packFingerprint() {
        List<String> ids = new ArrayList<String>();
        try {
            for (ModContainer container : Loader.instance().getActiveModList()) {
                ids.add(container.getModId() + "@" + container.getVersion());
            }
        } catch (Throwable ignored) {
            // Loader not up yet
        }
        Collections.sort(ids);
        return ids.toString();
    }

    /**
     * The block/item models' {@code texture_size} constraints, computed once per launch
     * by {@link LegacyModelScanner} (Gson 2.8.0 compatible).
     */
    Map<String, Integer> modelUvConstraints() {
        Map<String, Integer> cached = modelUvConstraints;
        if (cached == null) {
            try {
                cached = Collections.unmodifiableMap(LegacyModelScanner.scanAllModModels());
            } catch (Throwable t) {
                log.warn("[TextureScaler] model texture_size scan failed: {}", t.toString());
                cached = Collections.<String, Integer>emptyMap();
            }
            modelUvConstraints = cached;
        }
        return cached;
    }

    /**
     * {@code Minecraft.defaultResourcePacks} (SRG {@code field_110449_ao}); the only way
     * to inject a programmatic resource pack on 1.12.2, which predates
     * {@code AddPackFindersEvent}. We only read the list and append to it, so the final
     * field itself is never touched.
     */
    @SuppressWarnings("unchecked")
    List<IResourcePack> defaultResourcePacks() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        try {
            return ObfuscationReflectionHelper.getPrivateValue(Minecraft.class, mc, "field_110449_ao");
        } catch (Throwable t) {
            log.error("[TextureScaler] could not access Minecraft.defaultResourcePacks: {}", t.toString());
            return null;
        }
    }
}
