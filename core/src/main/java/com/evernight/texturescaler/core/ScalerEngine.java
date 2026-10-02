package com.evernight.texturescaler.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The version- and loader-independent scaling engine.
 *
 * <p>This is a direct port of the proven Forge 1.20.1 implementation
 * ({@code TextureScalingPack}) with the Minecraft APIs replaced by {@link ScalerPlatform}.
 * The behaviour is intentionally identical:</p>
 *
 * <ul>
 *   <li>the block atlas does <b>not</b> fetch textures through {@code getResource}; it
 *       enumerates them through the directory lister, so we must be able to re-emit a
 *       merged, downscaled listing ({@link #getListedScaled()});</li>
 *   <li>{@code getResource} is still honoured for non-listing lookups;</li>
 *   <li>a PNG header peek skips the small majority cheaply and feeds a persistent size
 *       manifest;</li>
 *   <li>the disk cache is consulted <em>before</em> decoding the original;</li>
 *   <li>Blockbench absolute-pixel UVs ({@code texture_size}) veto scaling;</li>
 *   <li>a second reload with an unchanged pack list reuses the previous scan and only
 *       checks newly appeared textures (incremental).</li>
 * </ul>
 */
public final class ScalerEngine {

    /** How many candidate locations are logged in detail per reload (then only counted). */
    private static final int SAMPLE_LIMIT = 60;

    private final ScalerPlatform platform;
    private final DiskCache cache;

    // ---- global state -----------------------------------------------------

    private volatile int currentCap = 512;

    /** Cap applied to textures whose longest edge exceeds the detail threshold. */
    private volatile int currentDetailCap = 2048;

    /** Cap applied to extreme-aspect ("strip") textures. */
    private volatile int currentStripCap = 4096;

    /** Identity of the cap policy; the persisted size manifest is only reused when it matches. */
    private volatile String currentPolicyId = "unresolved";

    /** Latched result of the first successful GPU query; 0 = not known yet. */
    private volatile int detectedGpuMaxTextureSize = 0;

    /** True once the cap has been resolved (or the fallback been reported) once. */
    private volatile boolean capResolved = false;

    /** "ns:spritePath" -> max texture_size edge of referencing models (0 = no constraint). */
    private volatile Map<String, Integer> modelUvConstraints = Collections.emptyMap();

    /** "ns:spritePath" set of block-atlas sprites captured after the previous stitch. */
    private volatile Set<String> blockAtlasSprites = null;

    /** Namespaces this pack claims, so the resource manager routes lookups to us. */
    private volatile Set<String> claimedNamespaces = null;

    /** Extra block-atlas texture directories from the config. */
    private volatile List<String> extraDirs = Collections.emptyList();

    /** In-memory scaled results for getResource lookups. */
    private final Map<String, byte[]> scaledCache = new ConcurrentHashMap<String, byte[]>();

    /** Locations we decided not to touch (small, model-UV constrained, or unreadable). */
    private final Set<String> knownUntouched = ConcurrentHashMap.newKeySet();

    /** Locations with no original available anywhere. */
    private final Set<String> knownMissing = ConcurrentHashMap.newKeySet();

    // ---- listing (atlas) state --------------------------------------------

    private volatile Map<String, byte[]> listedScaled = Collections.emptyMap();
    private volatile boolean listingComputed = false;
    private final Object listingLock = new Object();
    private volatile Set<String> lastListedPaths = Collections.emptySet();
    private volatile String lastPackFingerprint = null;
    /** Cap policy the cached listing was computed with; reuse is only valid when it is unchanged. */
    private volatile String lastListingPolicy = null;
    private volatile boolean incrementalPending = false;

    /** Re-entrancy guard for the nested manager listing inside our own listing. */
    private final ThreadLocal<Boolean> inListing = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };

    // ---- per-reload statistics --------------------------------------------

    private final AtomicLong statConsulted = new AtomicLong();
    private final AtomicLong statScaled = new AtomicLong();
    private final AtomicLong statSmall = new AtomicLong();
    private final AtomicLong statModelSkipped = new AtomicLong();
    private final AtomicLong statMissing = new AtomicLong();
    private final AtomicLong statFailed = new AtomicLong();
    private final AtomicLong sampled = new AtomicLong();

    public ScalerEngine(ScalerPlatform platform) {
        this.platform = platform;
        this.cache = new DiskCache(platform);
    }

    private ScalerConfig config() {
        return platform.config();
    }

    private ScalerLog log() {
        return platform.log();
    }

    // ---- lifecycle --------------------------------------------------------

    /** Called on every resource reload, before the background work of the reload listener. */
    public void onReloadStart() {
        resetStats();
        sampled.set(0);
        if (!config().enabled) {
            // Make sure an earlier (enabled) scan cannot keep feeding the atlas listing.
            listedScaled = Collections.emptyMap();
            listingComputed = true;
            incrementalPending = false;
            log().info("[TextureScaler] disabled by config, overlay pack inactive");
            return;
        }
        scaledCache.clear();
        knownUntouched.clear();
        knownMissing.clear();
        extraDirs = new ArrayList<String>(config().extraTextureDirs());

        updateCap();

        String fp = platform.packFingerprint();
        boolean sameCap = currentPolicyId.equals(lastListingPolicy);
        if (fp != null && fp.equals(lastPackFingerprint) && sameCap && !listedScaled.isEmpty()) {
            incrementalPending = true;
            log().info("[TextureScaler] pack list and cap unchanged, reusing previous atlas scan, "
                    + "will re-check for new textures");
        } else {
            listedScaled = Collections.emptyMap();
            listingComputed = false;
            lastListedPaths = Collections.emptySet();
            incrementalPending = false;
        }
        lastPackFingerprint = fp;

        Set<String> ns = platform.claimedNamespaces();
        if (ns != null && !ns.isEmpty()) {
            claimedNamespaces = ns;
        }
        log().info("[TextureScaler] resource reload started: caps normal {}, detail {}, strip {}, "
                        + "{} namespaces claimed",
                currentCap, currentDetailCap, currentStripCap,
                claimedNamespaces == null ? 0 : claimedNamespaces.size());
    }

    /** Logs the outcome of a reload after the reload barrier completes. */
    public void onReloadEnd() {
        log().info("[TextureScaler] reload finished: consulted {}, scaled {}, small {}, model-uv-skipped {}, "
                        + "missing {}, failed {}",
                statConsulted.get(), statScaled.get(), statSmall.get(),
                statModelSkipped.get(), statMissing.get(), statFailed.get());
    }

    private void resetStats() {
        statConsulted.set(0);
        statScaled.set(0);
        statSmall.set(0);
        statModelSkipped.set(0);
        statMissing.set(0);
        statFailed.set(0);
    }

    private void sample(String key, String decision) {
        long n = sampled.getAndIncrement();
        if (n < SAMPLE_LIMIT || config().debugLog) {
            log().info("[TextureScaler]   [{}] {} {}", n + 1, decision, key);
        }
    }

    // ---- state setters ----------------------------------------------------

    /** Runs the (relatively expensive) model scan; intended for the background reload executor. */
    public void scanModelUvConstraints() {
        Map<String, String> models = platform.listModels();
        setModelUvConstraints(ModelScanner.scan(models));
    }

    public void setModelUvConstraints(Map<String, Integer> constraints) {
        modelUvConstraints = constraints == null
                ? Collections.<String, Integer>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, Integer>(constraints));
    }

    /** Called after each stitch with the sprite ids that entered the block atlas. */
    public void rememberBlockAtlasSprites(Set<String> spriteKeys) {
        if (spriteKeys == null || spriteKeys.isEmpty()) {
            return;
        }
        blockAtlasSprites = new HashSet<String>(spriteKeys);
    }

    /**
     * Recomputes the scaling cap.
     *
     * <p>Safe to call from anywhere, including a mod initializer that runs inside the
     * Minecraft constructor: querying the GPU is delegated to the platform, which returns
     * {@code <= 0} rather than touching GL before the render backend is initialized. The
     * first positive result is <b>latched</b>, so a later unsuccessful query (background
     * thread, config event before the window exists) can never downgrade a known GPU value
     * to the fallback. The value is retried on every resource reload until it is known.</p>
     */
    public void updateCap() {
        int gpu = detectedGpuMaxTextureSize;
        if (gpu <= 0) {
            int queried = 0;
            try {
                queried = platform.gpuMaxTextureSize();
            } catch (Throwable ignored) {
                queried = 0;
            }
            if (queried > 0) {
                gpu = queried;
                detectedGpuMaxTextureSize = queried;
            }
        }
        ScalerConfig cfg = config();
        int normal;
        int detail;
        int strip;
        if (cfg.capOverride > 0) {
            // A manual override is deliberately uniform: every texture gets the same cap.
            normal = cfg.capOverride;
            detail = cfg.capOverride;
            strip = cfg.capOverride;
        } else if (cfg.tieredCaps) {
            normal = cfg.computeNormalCap(gpu);
            detail = cfg.computeDetailCap(gpu);
            strip = cfg.computeStripCap(gpu);
        } else {
            normal = cfg.computeCap(gpu);
            detail = normal;
            strip = normal;
        }
        boolean changed = normal != currentCap || detail != currentDetailCap || strip != currentStripCap;
        currentCap = normal;
        currentDetailCap = detail;
        currentStripCap = strip;
        currentPolicyId = normal + "/" + detail + "/" + strip
                + (cfg.tieredCaps ? "|t" : "|f") + "|" + cfg.capOverride;
        if (changed || !capResolved) {
            capResolved = true;
            if (cfg.capOverride > 0) {
                log().info("[TextureScaler] GPU max texture size detected: {} "
                                + "(manual capOverride {}, uniform scaling cap = {})",
                        gpu, cfg.capOverride, normal);
            } else if (gpu > 0 && cfg.tieredCaps) {
                log().info("[TextureScaler] GPU max texture size detected: {} -> caps: normal {}, "
                                + "detail {} (edge > {}), strip {} (aspect >= {}:1)",
                        gpu, normal, detail, cfg.detailThreshold, strip, cfg.stripAspectRatio);
            } else if (gpu > 0) {
                log().info("[TextureScaler] GPU max texture size detected: {} -> scaling cap = {}", gpu, normal);
            } else if (cfg.tieredCaps) {
                log().warn("[TextureScaler] could not query GL_MAX_TEXTURE_SIZE, using fallback caps: "
                                + "normal {}, detail {}, strip {}", normal, detail, strip);
            } else {
                log().warn("[TextureScaler] could not query GL_MAX_TEXTURE_SIZE, using fallback scaling cap = {}", normal);
            }
        }
    }

    public int currentCap() {
        return currentCap;
    }

    /** Cap used for textures whose longest edge exceeds the detail threshold. */
    public int currentDetailCap() {
        return currentDetailCap;
    }

    /** Cap used for extreme-aspect ("strip") textures. */
    public int currentStripCap() {
        return currentStripCap;
    }

    /** The cap that will be applied to a texture of this original size. */
    public int capFor(int textureW, int textureH) {
        return config().capFor(detectedGpuMaxTextureSize, textureW, textureH);
    }

    /** The latched {@code GL_MAX_TEXTURE_SIZE} reported by the platform; 0 = unknown. */
    public int detectedGpuMaxTextureSize() {
        return detectedGpuMaxTextureSize;
    }

    /** True while the engine itself is enumerating textures (re-entrancy guard for the pack). */
    public boolean isListing() {
        return inListing.get();
    }

    public boolean isEnabled() {
        return config().enabled;
    }

    public DiskCache cache() {
        return cache;
    }

    /** Namespaces the overlay pack answers for (never caches an empty result). */
    public Set<String> getNamespaces() {
        if (!config().enabled) {
            return Collections.emptySet();
        }
        Set<String> c = claimedNamespaces;
        if (c != null && !c.isEmpty()) {
            return c;
        }
        Set<String> fromPlatform = platform.claimedNamespaces();
        if (fromPlatform == null || fromPlatform.isEmpty()) {
            // The overlay can only be consulted for namespaces it claims. This happens while
            // MultiPackResourceManager is constructed - before onReloadStart() - so the
            // platform must be able to discover namespaces without the reload listener.
            log().warn("[TextureScaler] namespace discovery returned 0 namespaces; the overlay pack "
                    + "will not be consulted and no texture will be downscaled this reload");
            return Collections.emptySet();
        }
        Set<String> copy = new HashSet<String>(fromPlatform);
        claimedNamespaces = copy;
        return copy;
    }

    // ---- getResource path -------------------------------------------------

    /**
     * @return the downscaled PNG bytes to serve for this texture, or {@code null} to let
     *         the original pack win.
     */
    public byte[] getScaledResource(String namespace, String path) {
        if (!config().enabled) {
            return null;
        }
        if (!isCandidate(namespace, path)) {
            return null;
        }
        String key = key(namespace, path);
        if (knownUntouched.contains(key) || knownMissing.contains(key)) {
            return null;
        }
        if (!isEligible(namespace, path)) {
            return null;
        }
        statConsulted.incrementAndGet();

        byte[] cached = scaledCache.get(key);
        if (cached != null) {
            return cached;
        }

        byte[] original = platform.readOriginal(namespace, path);
        if (original == null) {
            knownMissing.add(key);
            statMissing.incrementAndGet();
            sample(key, "missing");
            return null;
        }

        byte[] scaled = scaleIfNeeded(namespace, path, original);
        if (scaled == null) {
            return null;
        }
        scaledCache.put(key, scaled);
        statScaled.incrementAndGet();
        sample(key, "scaled");
        return scaled;
    }

    // ---- listing (atlas) path ---------------------------------------------

    /** The merged, downscaled texture listing for the current reload. */
    public Map<String, byte[]> getListedScaled() {
        ensureListingComputed();
        return listedScaled;
    }

    private void ensureListingComputed() {
        if (listingComputed) {
            if (incrementalPending) {
                synchronized (listingLock) {
                    if (incrementalPending) {
                        incrementalPending = false;
                        runIncrementalListing();
                    }
                }
            }
            return;
        }
        synchronized (listingLock) {
            if (listingComputed) {
                if (incrementalPending) {
                    incrementalPending = false;
                    runIncrementalListing();
                }
                return;
            }
            Map<String, byte[]> result = new HashMap<String, byte[]>();
            long started = System.currentTimeMillis();
            try {
                if (inListing.get()) {
                    return;
                }
                inListing.set(Boolean.TRUE);
                try {
                    List<TextureHandle> all = platform.listAllTextures();
                    if (all == null) {
                        all = Collections.emptyList();
                    }
                    Map<String, int[]> knownSizes = cache.loadSizeManifest(currentPolicyId);
                    ListingStats stats = new ListingStats();
                    for (TextureHandle h : all) {
                        byte[] scaled = processForListing(h, knownSizes, stats);
                        if (scaled != null) {
                            result.put(key(h.namespace(), h.path()), scaled);
                        }
                    }
                    if (stats.manifestNew > 0) {
                        cache.saveSizeManifest(currentPolicyId, knownSizes);
                    }
                    Set<String> covered = new HashSet<String>();
                    for (TextureHandle h : all) {
                        covered.add(key(h.namespace(), h.path()));
                    }
                    lastListedPaths = covered;
                    lastPackFingerprint = platform.packFingerprint();
                    log().info("[TextureScaler] atlas scan: {} textures listed, {} eligible, "
                                    + "{} downscaled ({} from cache, {} new) in {} ms",
                            all.size(), stats.eligible, result.size(), stats.fromCache,
                            result.size() - stats.fromCache, System.currentTimeMillis() - started);
                } finally {
                    inListing.set(Boolean.FALSE);
                }
            } catch (Exception e) {
                log().warn("[TextureScaler] atlas scan failed: {}", e.toString());
            }
            listedScaled = result;
            listingComputed = true;
            lastListingPolicy = currentPolicyId;
        }
    }

    private void runIncrementalListing() {
        long started = System.currentTimeMillis();
        try {
            if (inListing.get()) {
                return;
            }
            inListing.set(Boolean.TRUE);
            try {
                List<TextureHandle> all = platform.listAllTextures();
                if (all == null) {
                    all = Collections.emptyList();
                }
                Set<String> currentKeys = new HashSet<String>();
                for (TextureHandle h : all) {
                    currentKeys.add(key(h.namespace(), h.path()));
                }

                Map<String, byte[]> merged = new HashMap<String, byte[]>();
                for (Map.Entry<String, byte[]> e : listedScaled.entrySet()) {
                    if (currentKeys.contains(e.getKey())) {
                        merged.put(e.getKey(), e.getValue());
                    }
                }

                Map<String, int[]> knownSizes = cache.loadSizeManifest(currentPolicyId);
                ListingStats stats = new ListingStats();
                int added = 0;
                for (TextureHandle h : all) {
                    String k = key(h.namespace(), h.path());
                    if (lastListedPaths.contains(k)) {
                        continue;
                    }
                    byte[] scaled = processForListing(h, knownSizes, stats);
                    if (scaled != null) {
                        merged.put(k, scaled);
                        added++;
                    }
                }
                if (stats.manifestNew > 0) {
                    cache.saveSizeManifest(currentPolicyId, knownSizes);
                }
                lastListedPaths = currentKeys;
                listedScaled = merged;
                lastListingPolicy = currentPolicyId;
                log().info("[TextureScaler] atlas scan (incremental): {} textures listed, "
                                + "{} new textures checked, {} added ({} from cache) in {} ms",
                        all.size(), stats.eligible, added, stats.fromCache,
                        System.currentTimeMillis() - started);
            } finally {
                inListing.set(Boolean.FALSE);
            }
        } catch (Exception e) {
            log().warn("[TextureScaler] incremental atlas scan failed: {}", e.toString());
        }
    }

    private byte[] processForListing(TextureHandle h, Map<String, int[]> knownSizes, ListingStats stats) {
        String namespace = h.namespace();
        String path = h.path();
        if (!isCandidate(namespace, path) || !isEligible(namespace, path)) {
            return null;
        }
        stats.eligible++;
        statConsulted.incrementAndGet();
        String key = key(namespace, path);

        int[] dims = knownSizes.get(key);
        if (dims == null) {
            try (InputStream in = h.open()) {
                dims = PngInfo.read(readUpTo(in, PngInfo.HEADER_BYTES));
            } catch (Exception ex) {
                statFailed.incrementAndGet();
                sample(key, "failed(" + ex + ")");
                return null;
            }
            if (dims != null) {
                knownSizes.put(key, dims);
                stats.manifestNew++;
            }
        }
        int cap = dims != null ? capFor(dims[0], dims[1]) : currentCap;
        if (dims != null && Math.max(dims[0], dims[1]) <= cap) {
            statSmall.incrementAndGet();
            sample(key, "small");
            return null;
        }

        int modelSize = modelUvConstraints.containsKey(spriteKey(namespace, path))
                ? modelUvConstraints.get(spriteKey(namespace, path)) : 0;
        if (modelSize > cap) {
            statModelSkipped.incrementAndGet();
            sample(key, "model-uv-skip(" + modelSize + " > " + cap + ")");
            return null;
        }

        if (dims != null) {
            byte[] png = cache.get(namespace, path, dims[0], dims[1], cap);
            if (png != null) {
                statScaled.incrementAndGet();
                stats.fromCache++;
                sample(key, "scaled(cached cap " + cap + ")");
                return png;
            }
        }

        byte[] original;
        try (InputStream in = h.open()) {
            original = DiskCache.readAll(in);
        } catch (Exception ex) {
            statFailed.incrementAndGet();
            sample(key, "failed(" + ex + ")");
            return null;
        }
        byte[] scaled = scaleIfNeeded(namespace, path, original);
        if (scaled != null) {
            statScaled.incrementAndGet();
            sample(key, "scaled(cap " + cap + ")");
        }
        return scaled;
    }

    private static final class ListingStats {
        int eligible;
        int fromCache;
        int manifestNew;
    }

    // ---- scaling ----------------------------------------------------------

    private byte[] scaleIfNeeded(String namespace, String path, byte[] original) {
        int[] dims = PngInfo.read(original);
        int cap = dims != null ? capFor(dims[0], dims[1]) : currentCap;
        if (dims != null && Math.max(dims[0], dims[1]) <= cap) {
            knownUntouched.add(key(namespace, path));
            statSmall.incrementAndGet();
            sample(key(namespace, path), "small");
            return null;
        }

        int modelSize = modelUvConstraints.containsKey(spriteKey(namespace, path))
                ? modelUvConstraints.get(spriteKey(namespace, path)) : 0;
        if (modelSize > cap) {
            knownUntouched.add(key(namespace, path));
            statModelSkipped.incrementAndGet();
            sample(key(namespace, path), "model-uv-skip(" + modelSize + " > " + cap + ")");
            return null;
        }

        if (dims != null) {
            byte[] png = cache.get(namespace, path, dims[0], dims[1], cap);
            if (png != null) {
                return png;
            }
        }

        ImageDownscaler.Result result;
        try {
            result = ImageDownscaler.downscale(original, cap);
        } catch (Throwable t) {
            result = null;
        }
        if (result == null) {
            knownUntouched.add(key(namespace, path));
            statFailed.incrementAndGet();
            sample(key(namespace, path), "unchanged/unsupported");
            return null;
        }
        cache.put(namespace, path, result.srcW, result.srcH, cap, result.png);
        return result.png;
    }

    // ---- helpers ----------------------------------------------------------

    private static String key(String namespace, String path) {
        return namespace + ":" + path;
    }

    /** "ns:spritePath" (textures/ prefix and .png suffix stripped). */
    private static String spriteKey(String namespace, String path) {
        String p = path;
        if (p.startsWith("textures/") && p.endsWith(".png")) {
            p = p.substring("textures/".length(), p.length() - ".png".length());
        }
        return namespace + ":" + p;
    }

    private boolean isCandidate(String namespace, String path) {
        if (config().skipNamespace(namespace)) {
            return false;
        }
        return path.startsWith("textures/") && path.endsWith(".png");
    }

    private boolean isEligible(String namespace, String path) {
        if (path.startsWith("textures/block/") || path.startsWith("textures/item/")) {
            return true;
        }
        for (String dir : extraDirs) {
            if (dir != null && !dir.isEmpty() && path.startsWith("textures/" + dir + "/")) {
                return true;
            }
        }
        String key = spriteKey(namespace, path);
        if (modelUvConstraints.containsKey(key)) {
            return true;
        }
        Set<String> sprites = blockAtlasSprites;
        return sprites != null && sprites.contains(key);
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        byte[] buf = new byte[max];
        int off = 0;
        while (off < max) {
            int n = in.read(buf, off, max - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        if (off == max) {
            return buf;
        }
        byte[] trimmed = new byte[off];
        System.arraycopy(buf, 0, trimmed, 0, off);
        return trimmed;
    }
}
