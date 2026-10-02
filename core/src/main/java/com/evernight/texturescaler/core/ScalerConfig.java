package com.evernight.texturescaler.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Loader-agnostic snapshot of the mod configuration.
 *
 * <p>Each loader owns its own config backend (ForgeConfigSpec / ModConfigSpec / a plain
 * JSON file on Fabric) and simply fills this bean. The core never talks to a config API
 * directly, which is what lets one algorithm serve every loader and version.</p>
 */
public final class ScalerConfig {

    public boolean enabled = true;
    /** Manual cap override; 0 = auto-detect from the GPU. */
    public int capOverride = 0;
    public int capDivisor = 32;
    public int capMin = 256;
    public int capMax = 2048;
    public boolean diskCacheEnabled = true;
    public String cacheDir = "texturescaler/cache";
    public List<String> skipNamespaces = new ArrayList<>(Arrays.asList("minecraft", "texturescaler"));
    public List<String> extraTextureDirs = new ArrayList<>();
    public boolean debugLog = false;

    public ScalerConfig() {
    }

    public boolean skipNamespace(String namespace) {
        return skipNamespaces != null && skipNamespaces.contains(namespace);
    }

    public List<String> extraTextureDirs() {
        return extraTextureDirs == null ? Collections.<String>emptyList() : extraTextureDirs;
    }

    /** Auto cap = clamp(maxTextureSize / divisor, min, max), with 0 meaning "auto". */
    public int computeCap(int gpuMaxTextureSize) {
        if (capOverride > 0) {
            return capOverride;
        }
        if (gpuMaxTextureSize > 0) {
            int divisor = Math.max(1, capDivisor);
            return Math.max(capMin, Math.min(capMax, gpuMaxTextureSize / divisor));
        }
        return 512;
    }
}
