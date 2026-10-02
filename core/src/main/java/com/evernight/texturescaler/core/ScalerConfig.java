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
    /** Manual cap override; 0 = auto-detect from the GPU. Wins over every tier below. */
    public int capOverride = 0;
    /** Legacy single-tier divisor; only consulted when {@link #tieredCaps} is false. */
    public int capDivisor = 32;
    public int capMin = 256;
    public int capMax = 2048;
    public boolean diskCacheEnabled = true;
    public String cacheDir = "texturescaler/cache";
    public List<String> skipNamespaces = new ArrayList<>(Arrays.asList("minecraft", "texturescaler"));
    public List<String> extraTextureDirs = new ArrayList<>();
    public boolean debugLog = false;

    // ---------------------------------------------------------------------
    // Tiered caps (2.0.2)
    //
    // A single flat cap forces every oversized texture down to the same edge, which is
    // far too aggressive for the handful of "display" textures a city-building pack
    // ships (sign sheets, screens, route maps, poster strips): a 4096 sheet landing on a
    // 512 edge is an 8x reduction and reads as mush in game, while the thousands of
    // ordinary 256-512 px block textures keep plenty of headroom at 256 because a block
    // face is only ever a few dozen screen pixels across.
    //
    // The atlas budget is unchanged by this - the tiers only move resolution from the
    // textures nobody can see to the ones on the screen.
    // ---------------------------------------------------------------------

    /** Use the tiered caps below instead of the single {@link #capDivisor} cap. */
    public boolean tieredCaps = true;
    /** Ordinary textures: {@code gpuMax / capDivisorNormal} (16384 -> 256). */
    public int capDivisorNormal = 64;
    /** Textures with an edge above {@link #detailThreshold}: {@code gpuMax / detailDivisor}. */
    public int detailDivisor = 8;
    public int detailThreshold = 1024;
    public int detailMin = 512;
    public int detailMax = 4096;
    /** Aspect ratio (long:short) at or above which a texture counts as a "strip". 0 = off. */
    public int stripAspectRatio = 4;
    /** Strips get at least {@code gpuMax / stripDivisor}: 64x11776 stays legible. */
    public int stripDivisor = 4;
    public int stripMin = 1024;
    public int stripMax = 8192;
    /** GPU value assumed while GL has not answered yet (safe middle ground: 16384). */
    public int gpuFallback = 16384;

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

    private int gpuOrDefault(int gpuMaxTextureSize) {
        return gpuMaxTextureSize > 0 ? gpuMaxTextureSize : (gpuFallback > 0 ? gpuFallback : 16384);
    }

    /** Cap for ordinary textures (16384 -> 256). */
    public int computeNormalCap(int gpuMaxTextureSize) {
        int divisor = Math.max(1, capDivisorNormal);
        return clamp(gpuOrDefault(gpuMaxTextureSize) / divisor, capMin, capMax);
    }

    /** Cap for large "display" textures (16384 -> 2048). */
    public int computeDetailCap(int gpuMaxTextureSize) {
        int divisor = Math.max(1, detailDivisor);
        return clamp(gpuOrDefault(gpuMaxTextureSize) / divisor, detailMin, detailMax);
    }

    /** Cap for extreme-aspect strips (16384 -> 4096). */
    public int computeStripCap(int gpuMaxTextureSize) {
        int divisor = Math.max(1, stripDivisor);
        return clamp(gpuOrDefault(gpuMaxTextureSize) / divisor, stripMin, stripMax);
    }

    /**
     * The cap to use for one concrete texture.
     *
     * @param textureW original width in pixels
     * @param textureH original height in pixels
     */
    public int capFor(int gpuMaxTextureSize, int textureW, int textureH) {
        if (capOverride > 0) {
            return capOverride;
        }
        if (!tieredCaps) {
            return computeCap(gpuMaxTextureSize);
        }
        int w = textureW > 0 ? textureW : 1;
        int h = textureH > 0 ? textureH : 1;
        int maxEdge = Math.max(w, h);
        int minEdge = Math.min(w, h);
        int cap = maxEdge > detailThreshold
                ? computeDetailCap(gpuMaxTextureSize)
                : computeNormalCap(gpuMaxTextureSize);
        if (stripAspectRatio > 0 && (long) maxEdge >= (long) stripAspectRatio * (long) minEdge) {
            cap = Math.max(cap, computeStripCap(gpuMaxTextureSize));
        }
        return Math.max(1, cap);
    }

    /** True when the texture is extreme-aspect ("strip") and therefore gets the strip cap. */
    public boolean isStrip(int textureW, int textureH) {
        if (stripAspectRatio <= 0) {
            return false;
        }
        int w = textureW > 0 ? textureW : 1;
        int h = textureH > 0 ? textureH : 1;
        return (long) Math.max(w, h) >= (long) stripAspectRatio * (long) Math.min(w, h);
    }

    private static int clamp(int value, int min, int max) {
        int lo = Math.max(1, min);
        int hi = Math.max(lo, max);
        return Math.max(lo, Math.min(hi, value));
    }
}
