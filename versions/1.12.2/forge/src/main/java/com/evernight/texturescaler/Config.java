package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerConfig;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Forge 1.12.2 client config backed by {@link Configuration}.
 *
 * <p>1.12.2 predates {@code ForgeConfigSpec}, so the classic
 * {@code net.minecraftforge.common.config.Configuration} API is used. The values are
 * still exposed to the shared core as a loader-agnostic {@link ScalerConfig} bean, which
 * is what keeps one algorithm serving every loader.</p>
 */
public final class Config {

    private static final String CATEGORY = "general";

    /**
     * 1.12.2 keeps block textures in {@code textures/blocks/} and item textures in
     * {@code textures/items/} (plural), whereas the shared core's built-in eligibility
     * test only knows the 1.13+ singular folders. Feeding the plural folders through
     * {@code extraTextureDirs} makes every block/item sprite eligible on this version.
     */
    private static final String[] DEFAULT_TEXTURE_DIRS = new String[]{"blocks", "items"};

    private static Configuration config;

    private Config() {
    }

    /** Loads (and creates on first run) the config file. Called from preInit. */
    public static synchronized void load(File file) {
        if (config == null) {
            config = new Configuration(file);
        }
        config.load();
        config.setCategoryComment(CATEGORY, "Texture Scaler - client side texture downscaling");
        readAll();
        if (config.hasChanged()) {
            config.save();
        }
    }

    /** Re-reads the current values; also used after a config GUI save. */
    public static synchronized void readAll() {
        if (config == null) {
            return;
        }
        // Force the values to materialise (and defaults to be written) before snapshotting.
        getEnabled();
        getCapOverride();
        getCapDivisor();
        getCapMin();
        getCapMax();
        getDiskCacheEnabled();
        getCacheDir();
        getSkipNamespaces();
        getExtraTextureDirs();
        getDebugLog();
    }

    private static boolean getEnabled() {
        return config.getBoolean("enabled", CATEGORY, true,
                "Master switch. When false the mod does nothing and the original textures are used.");
    }

    private static int getCapOverride() {
        return config.getInt("capOverride", CATEGORY, 0, 0, 16384,
                "Manual cap override (largest allowed texture edge, in pixels). 0 = auto-detect from GL_MAX_TEXTURE_SIZE.");
    }

    private static int getCapDivisor() {
        return config.getInt("capDivisor", CATEGORY, 32, 4, 512,
                "Auto cap is computed as clamp(GL_MAX_TEXTURE_SIZE / divisor, capMin, capMax). "
                        + "32768 (NVIDIA) -> 1024, 16384 (RX 580 / most iGPU) -> 512, 8192 -> 256.");
    }

    private static int getCapMin() {
        return config.getInt("capMin", CATEGORY, 256, 64, 4096, "Lower bound of the auto cap.");
    }

    private static int getCapMax() {
        return config.getInt("capMax", CATEGORY, 2048, 256, 16384, "Upper bound of the auto cap.");
    }

    private static boolean getDiskCacheEnabled() {
        return config.getBoolean("diskCacheEnabled", CATEGORY, true,
                "Cache downscaled textures on disk so later launches skip re-scaling.");
    }

    private static String getCacheDir() {
        return config.getString("cacheDir", CATEGORY, "texturescaler/cache",
                "Cache sub-directory (relative to the game directory).");
    }

    private static String[] getSkipNamespaces() {
        return config.getStringList("skipNamespaces", CATEGORY, new String[]{"minecraft", "texturescaler"},
                "Namespaces that are never modified (vanilla assets never need scaling anyway).");
    }

    private static String[] getExtraTextureDirs() {
        return config.getStringList("extraTextureDirs", CATEGORY, DEFAULT_TEXTURE_DIRS,
                "Extra texture sub-directories (relative to textures/, without trailing slash) that also "
                        + "feed the block atlas and should be checked. 1.12.2 uses the plural folders "
                        + "blocks/ and items/.");
    }

    private static boolean getDebugLog() {
        return config.getBoolean("debugLog", CATEGORY, false, "Log each scaled texture.");
    }

    /** Snapshot the live config into the loader-agnostic bean consumed by the engine. */
    public static ScalerConfig snapshot() {
        ScalerConfig c = new ScalerConfig();
        try {
            c.enabled = getEnabled();
            c.capOverride = getCapOverride();
            c.capDivisor = getCapDivisor();
            c.capMin = getCapMin();
            c.capMax = getCapMax();
            c.diskCacheEnabled = getDiskCacheEnabled();
            c.cacheDir = getCacheDir();
            c.skipNamespaces = new ArrayList<String>(Arrays.asList(getSkipNamespaces()));
            c.extraTextureDirs = new ArrayList<String>(Arrays.asList(getExtraTextureDirs()));
            c.debugLog = getDebugLog();
        } catch (Exception ignored) {
            // Config not loaded yet (very early call) - keep the defaults.
        }
        return c;
    }
}
