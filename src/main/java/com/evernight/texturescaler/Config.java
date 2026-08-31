package com.evernight.texturescaler;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Client-side configuration for Texture Scaler.
 *
 * <p>All values are read live via {@link #get()} so that config changes
 * (e.g. via F3+T resource reload or a config screen) take effect quickly.</p>
 */
public final class Config {

    public static final ForgeConfigSpec SPEC;

    /** Master switch. */
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    /** Manual cap override; 0 = auto-detect from GPU. */
    public static final ForgeConfigSpec.IntValue CAP_OVERRIDE;
    /** Auto cap = clamp(maxTextureSize / divisor, min, max). */
    public static final ForgeConfigSpec.IntValue CAP_DIVISOR;
    public static final ForgeConfigSpec.IntValue CAP_MIN;
    public static final ForgeConfigSpec.IntValue CAP_MAX;
    /** Keep scaled results on disk for fast subsequent launches. */
    public static final ForgeConfigSpec.BooleanValue DISK_CACHE_ENABLED;
    /** Cache sub-directory under the game directory. */
    public static final ForgeConfigSpec.ConfigValue<String> CACHE_DIR;
    /** Namespaces that must never be touched. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SKIP_NAMESPACES;
    /** Extra texture directories (relative to textures/) that enter the block atlas. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_TEXTURE_DIRS;
    /** Extra logging for debugging. */
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        ENABLED = b.comment(
                        "Master switch. When false the mod does nothing and original textures are used.",
                        "Default: true")
                .define("enabled", true);

        CAP_OVERRIDE = b.comment(
                        "Manual cap override (largest allowed texture edge, in pixels) for textures that",
                        "enter the block atlas. 0 = auto-detect from GL_MAX_TEXTURE_SIZE.",
                        "Useful if the auto value is too aggressive or not aggressive enough.",
                        "Default: 0 (auto)")
                .defineInRange("capOverride", 0, 0, 16384);

        CAP_DIVISOR = b.comment(
                        "Auto cap is computed as clamp(GL_MAX_TEXTURE_SIZE / divisor, capMin, capMax).",
                        "GL_MAX_TEXTURE_SIZE 32768 (NVIDIA) -> 1024, 16384 (RX 580 / most iGPU) -> 512, 8192 -> 256.",
                        "Default: 32")
                .defineInRange("capDivisor", 32, 4, 512);

        CAP_MIN = b.comment("Lower bound of the auto cap. Default: 256")
                .defineInRange("capMin", 256, 64, 4096);

        CAP_MAX = b.comment("Upper bound of the auto cap. Default: 2048")
                .defineInRange("capMax", 2048, 256, 16384);

        DISK_CACHE_ENABLED = b.comment(
                        "Cache downscaled textures on disk so later launches skip re-scaling.",
                        "Default: true")
                .define("diskCacheEnabled", true);

        CACHE_DIR = b.comment(
                        "Cache sub-directory (relative to the game directory).",
                        "Default: texturescaler/cache")
                .define("cacheDir", "texturescaler/cache");

        SKIP_NAMESPACES = b.comment(
                        "Namespaces that are never modified (vanilla assets never need scaling anyway).",
                        "Default: [minecraft, texturescaler]")
                .defineListAllowEmpty("skipNamespaces", List.of("minecraft", "texturescaler"), o -> o instanceof String);

        EXTRA_TEXTURE_DIRS = b.comment(
                        "Extra texture sub-directories (relative to textures/, without trailing slash) that",
                        "also feed the block atlas and should be checked, e.g. [\"blocks\"].",
                        "Default: []")
                .defineListAllowEmpty("extraTextureDirs", List.of(), o -> o instanceof String);

        DEBUG_LOG = b.comment("Log each scaled texture. Default: false")
                .define("debugLog", false);

        SPEC = b.build();
    }

    private Config() {
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static int capOverride() {
        return CAP_OVERRIDE.get();
    }

    public static int capDivisor() {
        return Math.max(1, CAP_DIVISOR.get());
    }

    public static int capMin() {
        return CAP_MIN.get();
    }

    public static int capMax() {
        return Math.max(1, CAP_MAX.get());
    }

    public static boolean diskCacheEnabled() {
        return DISK_CACHE_ENABLED.get();
    }

    public static String cacheDir() {
        return CACHE_DIR.get();
    }

    public static boolean skipNamespace(String ns) {
        return SKIP_NAMESPACES.get().contains(ns);
    }

    public static java.util.List<? extends String> extraTextureDirs() {
        return EXTRA_TEXTURE_DIRS.get();
    }

    public static boolean debugLog() {
        return DEBUG_LOG.get();
    }
}
