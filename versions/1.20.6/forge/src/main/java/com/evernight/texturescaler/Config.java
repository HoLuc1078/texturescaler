package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Forge client config; the loader-agnostic values are exposed as a {@link ScalerConfig}. */
public final class Config {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.IntValue CAP_OVERRIDE;
    private static final ForgeConfigSpec.IntValue CAP_DIVISOR;
    private static final ForgeConfigSpec.IntValue CAP_MIN;
    private static final ForgeConfigSpec.IntValue CAP_MAX;
    private static final ForgeConfigSpec.BooleanValue DISK_CACHE_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> CACHE_DIR;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SKIP_NAMESPACES;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_TEXTURE_DIRS;
    private static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        ENABLED = b.comment(
                        "Master switch. When false the mod does nothing and original textures are used.",
                        "Default: true")
                .define("enabled", true);

        CAP_OVERRIDE = b.comment(
                        "Manual cap override (largest allowed texture edge, in pixels) for textures that",
                        "enter the block atlas. 0 = auto-detect from GL_MAX_TEXTURE_SIZE.",
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
                .defineListAllowEmpty("skipNamespaces", Arrays.asList("minecraft", "texturescaler"),
                        o -> o instanceof String);

        EXTRA_TEXTURE_DIRS = b.comment(
                        "Extra texture sub-directories (relative to textures/, without trailing slash) that",
                        "also feed the block atlas and should be checked, e.g. [\"blocks\"].",
                        "Default: []")
                .defineListAllowEmpty("extraTextureDirs", Arrays.asList(), o -> o instanceof String);

        DEBUG_LOG = b.comment("Log each scaled texture. Default: false")
                .define("debugLog", false);

        SPEC = b.build();
    }

    private Config() {
    }

    /** Snapshot the live config into the loader-agnostic bean consumed by the engine. */
    public static ScalerConfig snapshot() {
        ScalerConfig c = new ScalerConfig();
        try {
            c.enabled = ENABLED.get();
            c.capOverride = CAP_OVERRIDE.get();
            c.capDivisor = CAP_DIVISOR.get();
            c.capMin = CAP_MIN.get();
            c.capMax = CAP_MAX.get();
            c.diskCacheEnabled = DISK_CACHE_ENABLED.get();
            c.cacheDir = CACHE_DIR.get();
            c.skipNamespaces = new ArrayList<String>(SKIP_NAMESPACES.get());
            c.extraTextureDirs = new ArrayList<String>(EXTRA_TEXTURE_DIRS.get());
            c.debugLog = DEBUG_LOG.get();
        } catch (Exception ignored) {
            // Config not loaded yet (very early call) — keep defaults.
        }
        return c;
    }
}
