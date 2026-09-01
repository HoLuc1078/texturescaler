package com.evernight.texturescaler;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The dynamic, highest-priority resource pack that downscales oversized block-atlas
 * textures at load time.
 *
 * <p>v1.2 — the crucial fix: the block atlas does NOT fetch textures through
 * {@link ResourceManager#getResource(ResourceLocation)} — it enumerates them through
 * {@link ResourceManager#listResources(String, java.util.function.Predicate)} (the vanilla
 * {@code atlases/blocks.json} "directory" source). The 1.0/1.1 overlay only implemented
 * {@code getResource}, so its scaled images never reached the atlas and the stitch always
 * overflowed. We now override {@code listResources} too: the first time any texture listing
 * happens in a reload we scan the whole merged texture list, downscale every oversized
 * eligible PNG (block/item dirs, extra dirs, model-referenced, or previously-stitched
 * sprites) and re-emit those locations; because this pack sits above the mod packs in the
 * resource stack, our scaled entries overwrite the originals in the merged listing.</p>
 *
 * <p>{@code getResource} is kept for non-listing lookups (mods querying individual textures,
 * Continuity emissive checks, SingleFile/Unstitcher atlas sources).</p>
 */
public final class TextureScalingPack implements PackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PACK_ID = "texturescaler_overlay";
    private static final byte[] PACK_META = ("{\"pack\":{\"description\":{\"text\":\"Texture Scaler overlay\"},"
            + "\"pack_format\":15}}").getBytes(StandardCharsets.UTF_8);

    private static final TextureScalingPack INSTANCE = new TextureScalingPack();

    /** How many candidate locations are logged in detail per reload (then only counted). */
    private static final int SAMPLE_LIMIT = 60;

    // ---- global state -----------------------------------------------------

    /** GPU-derived cap (largest allowed edge). Updated on client setup and each reload. */
    private static volatile int currentCap = 512;

    /** Whether the GPU cap has been detected at least once (so we always log the first result). */
    private static volatile boolean gpuDetected = false;

    /** Snapshot of the other packs, refreshed on every resource reload. */
    private static volatile List<PackResources> sourcePacks = List.of();

    /** Texture -> max texture_size edge of referencing models (0 = no constraint). */
    private static volatile Map<String, Integer> modelSizes = Map.of();

    /** "ns:spritePath" set of block-atlas sprites captured after the previous stitch. */
    private static volatile Set<String> blockAtlasSprites = null;

    /** Namespaces this pack claims, so the resource manager routes lookups to us. */
    private static volatile Set<String> claimedNamespaces = null;
    private static final Object NAMESPACE_LOCK = new Object();

    /** Extra block-atlas texture directories from the config. */
    private static volatile List<String> extraDirs = List.of();

    /** In-memory scaled results for getResource lookups. */
    private static final ConcurrentHashMap<ResourceLocation, byte[]> scaledCache = new ConcurrentHashMap<>();

    /** Locations we decided not to touch (small, model-UV constrained, or unreadable). */
    private static final Set<ResourceLocation> knownUntouched = ConcurrentHashMap.newKeySet();

    /** Locations with no original available anywhere. */
    private static final Set<ResourceLocation> knownMissing = ConcurrentHashMap.newKeySet();

    /** Re-entrancy guard for the live-resource-manager fallback. */
    private static final ThreadLocal<Boolean> READING_ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ---- listResources (atlas) state ---------------------------------------

    /** Scaled locations -> PNG bytes, computed once per reload for the atlas listing. */
    private static volatile Map<ResourceLocation, byte[]> listedScaled = Map.of();
    private static volatile boolean listingComputed = false;
    private static final Object LISTING_LOCK = new Object();

    /** Re-entrancy guard for the nested manager listing inside our own listResources. */
    private static final ThreadLocal<Boolean> IN_LISTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ---- per-reload statistics ---------------------------------------------

    private static final AtomicLong statConsulted = new AtomicLong();
    private static final AtomicLong statScaled = new AtomicLong();
    private static final AtomicLong statSmall = new AtomicLong();
    private static final AtomicLong statModelSkipped = new AtomicLong();
    private static final AtomicLong statMissing = new AtomicLong();
    private static final AtomicLong statFailed = new AtomicLong();
    private static final AtomicLong sampled = new AtomicLong();

    // -----------------------------------------------------------------------

    public static Pack createPack() {
        return Pack.readMetaAndCreate(
                PACK_ID,
                Component.literal("Texture Scaler"),
                true, // required -> automatically selected, cannot be disabled
                id -> INSTANCE,
                PackType.CLIENT_RESOURCES,
                Pack.Position.TOP,
                PackSource.BUILT_IN);
    }

    /** Called on every resource reload (before background work of this listener). */
    public static void onReloadStart(ResourceManager rm) {
        resetStats();
        sampled.set(0);
        if (!Config.enabled()) {
            LOGGER.info("[TextureScaler] disabled by config, overlay pack inactive");
            return;
        }
        List<PackResources> snapshot;
        try {
            snapshot = rm.listPacks().toList();
        } catch (Exception e) {
            snapshot = List.of();
            LOGGER.warn("[TextureScaler] could not list packs: {}", e.toString());
        }
        sourcePacks = snapshot;
        scaledCache.clear();
        knownUntouched.clear();
        knownMissing.clear();
        listedScaled = Map.of();
        listingComputed = false;
        extraDirs = new ArrayList<>(Config.extraTextureDirs());

        // Refresh the claimed namespaces from the live pack list (excluding ourselves).
        Set<String> ns = new HashSet<>();
        for (PackResources pack : snapshot) {
            if (pack instanceof TextureScalingPack) {
                continue;
            }
            try {
                for (String s : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                    ns.add(s);
                }
            } catch (Exception e) {
                LOGGER.warn("[TextureScaler] could not read namespaces of pack {}: {}",
                        pack.packId(), e.toString());
            }
        }
        claimedNamespaces = Set.copyOf(ns);

        updateCap();
        LOGGER.info("[TextureScaler] resource reload started: {} source packs, cap = {}, {} namespaces claimed",
                snapshot.size(), currentCap, claimedNamespaces.size());
    }

    /** Log the outcome of a reload (called after the reload barrier completes). */
    public static void onReloadEnd() {
        LOGGER.info("[TextureScaler] reload finished: consulted {}, scaled {}, small {}, model-uv-skipped {}, "
                        + "missing {}, failed {}",
                statConsulted.get(), statScaled.get(), statSmall.get(),
                statModelSkipped.get(), statMissing.get(), statFailed.get());
    }

    private static void resetStats() {
        statConsulted.set(0);
        statScaled.set(0);
        statSmall.set(0);
        statModelSkipped.set(0);
        statMissing.set(0);
        statFailed.set(0);
    }

    /** Log the first {@link #SAMPLE_LIMIT} decisions in detail (or all when debugLog is on). */
    private static void sample(ResourceLocation loc, String decision) {
        long n = sampled.getAndIncrement();
        if (n < SAMPLE_LIMIT || Config.debugLog()) {
            LOGGER.info("[TextureScaler]   [{}] {} {}", n + 1, decision, loc);
        }
    }

    public static void setModelSizes(Map<String, Integer> sizes) {
        modelSizes = sizes == null ? Map.of() : Map.copyOf(sizes);
    }

    /** Called from {@code TextureStitchEvent.Post} (after each stitch). */
    public static void rememberBlockAtlasTextures(Set<ResourceLocation> textures) {
        if (textures == null || textures.isEmpty()) {
            return;
        }
        Set<String> s = new HashSet<>(textures.size() * 2);
        for (ResourceLocation rl : textures) {
            s.add(rl.toString());
        }
        blockAtlasSprites = s;
    }

    /** Query the GPU limit (only valid once a GL context exists). */
    public static void updateCap() {
        int gpu = 0;
        try {
            gpu = RenderSystem.maxSupportedTextureSize();
        } catch (Throwable t) {
            try {
                gpu = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            } catch (Throwable t2) {
                gpu = 0;
            }
        }
        int cap;
        boolean override = Config.capOverride() > 0;
        if (override) {
            cap = Config.capOverride();
        } else if (gpu > 0) {
            cap = Math.max(Config.capMin(), Math.min(Config.capMax(), gpu / Config.capDivisor()));
        } else {
            cap = 512;
        }
        if (cap != currentCap || !gpuDetected) {
            currentCap = cap;
            gpuDetected = true;
            if (override) {
                LOGGER.info("[TextureScaler] GPU max texture size detected: {} (manual capOverride {}, scaling cap = {})",
                        gpu, cap, cap);
            } else if (gpu > 0) {
                LOGGER.info("[TextureScaler] GPU max texture size detected: {} -> scaling cap = {}", gpu, cap);
            } else {
                LOGGER.warn("[TextureScaler] could not query GL_MAX_TEXTURE_SIZE, using fallback scaling cap = {}", cap);
            }
        }
    }

    public static int currentCap() {
        return currentCap;
    }

    // ---- PackResources ----------------------------------------------------

    private TextureScalingPack() {
    }

    @Override
    public String packId() {
        return PACK_ID;
    }

    @Override
    public boolean isHidden() {
        return true; // keep the pack out of the resource pack UI
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES || !Config.enabled()) {
            return Set.of();
        }
        return computeNamespaces();
    }

    /**
     * Returns the union of namespaces we may be asked about.
     *
     * <p>The {@code MultiPackResourceManager} calls this when it is constructed, which happens
     * BEFORE any reload listener runs — so this must never depend on listener state alone.
     * We prefer the snapshot taken from the actual reload pack list
     * ({@link #onReloadStart}), and otherwise fall back to discovering namespaces from the
     * live resource manager and the pack repository. Claiming namespaces that end up unused
     * is harmless (we return {@code null} for anything we do not touch).</p>
     */
    private static Set<String> computeNamespaces() {
        Set<String> cached = claimedNamespaces;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        synchronized (NAMESPACE_LOCK) {
            cached = claimedNamespaces;
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
            Set<String> result = new HashSet<>();
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();

            // Source 1: the live resource manager's packs (same set the reload will use).
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getResourceManager() != null) {
                    mc.getResourceManager().listPacks().forEach(pack -> {
                        if (pack instanceof TextureScalingPack) {
                            return;
                        }
                        try {
                            result.addAll(pack.getNamespaces(PackType.CLIENT_RESOURCES));
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    });
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }

            // Source 2: the pack repository's selected packs.
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getResourcePackRepository() != null) {
                    PackRepository repo = mc.getResourcePackRepository();
                    for (Pack pack : repo.getSelectedPacks()) {
                        try (PackResources pr = pack.open()) {
                            if (pr instanceof TextureScalingPack) {
                                continue;
                            }
                            result.addAll(pr.getNamespaces(PackType.CLIENT_RESOURCES));
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }

            if (result.isEmpty()) {
                // Never cache an empty result: a later call (or reload listener) may succeed.
                LOGGER.warn("[TextureScaler] namespace discovery returned empty ({} failures); "
                        + "the overlay pack will not be consulted until namespaces are known", failures.get());
                return Set.of();
            }
            claimedNamespaces = Set.copyOf(result);
            LOGGER.info("[TextureScaler] namespace discovery ({} failures): {} namespaces claimed",
                    failures.get(), result.size());
            return claimedNamespaces;
        }
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        IoSupplier<InputStream> supplier = getRootResource("pack.mcmeta");
        if (supplier == null) {
            return null;
        }
        try (InputStream in = supplier.get()) {
            return getMetadataFromStream(serializer, in);
        }
    }

    private static <T> T getMetadataFromStream(MetadataSectionSerializer<T> serializer, InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            JsonObject json = GsonHelper.parse(reader);
            if (!json.has(serializer.getMetadataSectionName())) {
                return null;
            }
            return serializer.fromJson(GsonHelper.getAsJsonObject(json, serializer.getMetadataSectionName()));
        } catch (Exception e) {
            LOGGER.error("[TextureScaler] Couldn't load {} metadata", serializer.getMetadataSectionName(), e);
            return null;
        }
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (paths.length == 1 && "pack.mcmeta".equals(paths[0])) {
            return () -> new ByteArrayInputStream(PACK_META);
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || !Config.enabled()) {
            return null;
        }
        if (!isCandidate(location)) {
            return null;
        }
        if (knownUntouched.contains(location) || knownMissing.contains(location)) {
            return null;
        }
        if (!isEligible(location)) {
            return null;
        }
        statConsulted.incrementAndGet();

        byte[] cached = scaledCache.get(location);
        if (cached != null) {
            return () -> new ByteArrayInputStream(cached);
        }

        byte[] original = readOriginal(location);
        if (original == null) {
            knownMissing.add(location);
            statMissing.incrementAndGet();
            sample(location, "missing");
            return null;
        }

        byte[] scaled = scaleIfNeeded(location, original);
        if (scaled == null) {
            return null;
        }
        scaledCache.put(location, scaled);
        statScaled.incrementAndGet();
        sample(location, "scaled");
        final byte[] served = scaled;
        return () -> new ByteArrayInputStream(served);
    }

    /**
     * The block atlas enumerates its textures through {@code listResources} (vanilla
     * {@code atlases/blocks.json} "directory" source), NOT through {@code getResource}.
     * We therefore re-emit every location we downscaled here: because this pack sits above
     * the mod packs in the resource stack, its entries overwrite the originals in the
     * merged listing, so the atlas stitches the scaled images.
     *
     * <p><b>Important:</b> the {@code path} passed here is the <i>converter prefix</i>,
     * e.g. {@code "textures/block"} or {@code "textures/item"} (the atlas source
     * {@code "block"} is prefixed with {@code "textures/"} by {@code DirectoryLister}),
     * NOT {@code "textures"}. v1.2.0 matched only {@code "textures".equals(path)}, so the
     * atlas listing ({@code "textures/block"}) never received our scaled images — the
     * scan ran (cache filled) but nothing was ever emitted. Match by prefix instead.</p>
     */
    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput resourceOutput) {
        if (type != PackType.CLIENT_RESOURCES || !Config.enabled()) {
            return;
        }
        if (!path.startsWith("textures/")) {
            return;
        }
        ensureListingComputed();
        Map<ResourceLocation, byte[]> scaled = listedScaled;
        if (scaled.isEmpty()) {
            return;
        }
        // Only re-emit entries that actually live under this listing prefix. DirectoryLister
        // builds a FileToIdConverter for e.g. "textures/block" and maps every entry back to
        // a sprite id via substring(prefix.length(), ...); emitting entries from other
        // directories here (v0.0.1 bug) made it substring past the end and crashed the whole
        // reload with StringIndexOutOfBoundsException in FileToIdConverter.m_245273_.
        String prefix = path + "/";
        int emitted = 0;
        for (Map.Entry<ResourceLocation, byte[]> e : scaled.entrySet()) {
            if (e.getKey().getNamespace().equals(namespace)
                    && e.getKey().getPath().startsWith(prefix)) {
                byte[] bytes = e.getValue();
                resourceOutput.accept(e.getKey(), () -> new ByteArrayInputStream(bytes));
                emitted++;
            }
        }
        if (emitted > 0) {
            LOGGER.info("[TextureScaler] listResources(ns={}, path={}): emitted {} scaled textures",
                    namespace, path, emitted);
        }
    }

    /**
     * Computes (once per reload) the set of textures that must be downscaled for the atlas,
     * by listing the whole merged texture tree and scaling every oversized eligible PNG.
     * The nested listing is recursion-guarded so our own {@code listResources} contributes
     * nothing to it and the originals are what we process.
     */
    private static void ensureListingComputed() {
        if (listingComputed) {
            return;
        }
        synchronized (LISTING_LOCK) {
            if (listingComputed) {
                return;
            }
            Map<ResourceLocation, byte[]> result = new HashMap<>();
            long started = System.currentTimeMillis();
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null || mc.getResourceManager() == null) {
                    LOGGER.warn("[TextureScaler] no resource manager available for atlas scan");
                    listingComputed = true; // avoid hot-looping; retried next reload
                    listedScaled = result;
                    return;
                }
                if (IN_LISTING.get()) {
                    return; // recursion guard failed somewhere; do not emit
                }
                IN_LISTING.set(Boolean.TRUE);
                try {
                    Map<ResourceLocation, Resource> all =
                            mc.getResourceManager().listResources("textures",
                                    loc -> loc.getPath().endsWith(".png"));

                    // Reuse previously read dimensions so we do not open every PNG again.
                    // New/unknown entries get a 24-byte header peek and extend the manifest.
                    Map<String, int[]> knownSizes = TextureCache.loadSizeManifest(currentCap);
                    int manifestEntries = 0;
                    int eligible = 0;
                    int fromCache = 0;
                    for (Map.Entry<ResourceLocation, Resource> e : all.entrySet()) {
                        ResourceLocation loc = e.getKey();
                        if (!isCandidate(loc) || !isEligible(loc)) {
                            continue;
                        }
                        eligible++;
                        statConsulted.incrementAndGet();

                        int[] dims = knownSizes.get(loc.toString());
                        if (dims == null) {
                            // Cheap header peek: most textures are small and can be skipped
                            // without reading the whole file.
                            try (InputStream in = e.getValue().open()) {
                                dims = readPngDimensions(in.readNBytes(24));
                            } catch (Exception ex) {
                                statFailed.incrementAndGet();
                                sample(loc, "failed(" + ex + ")");
                                continue;
                            }
                            if (dims != null) {
                                knownSizes.put(loc.toString(), dims);
                                manifestEntries++;
                            }
                        }
                        if (dims != null && Math.max(dims[0], dims[1]) <= currentCap) {
                            statSmall.incrementAndGet();
                            sample(loc, "small");
                            continue;
                        }

                        // Blockbench models with absolute-pixel UVs would break if their
                        // texture_size exceeds the cap — checked before any cache/decode work.
                        int modelSize = modelSizes.getOrDefault(spriteKey(loc), 0);
                        if (modelSize > currentCap) {
                            statModelSkipped.incrementAndGet();
                            sample(loc, "model-uv-skip(" + modelSize + ")");
                            continue;
                        }

                        // Oversized: with header dims we can hit the disk cache without
                        // decoding the original image.
                        if (dims != null) {
                            byte[] png = TextureCache.get(
                                    loc.getNamespace(), loc.getPath(), dims[0], dims[1], currentCap);
                            if (png != null) {
                                result.put(loc, png);
                                statScaled.incrementAndGet();
                                fromCache++;
                                sample(loc, "scaled(cached)");
                                continue;
                            }
                        }

                        byte[] original;
                        try (InputStream in = e.getValue().open()) {
                            original = in.readAllBytes();
                        } catch (Exception ex) {
                            statFailed.incrementAndGet();
                            sample(loc, "failed(" + ex + ")");
                            continue;
                        }
                        byte[] scaled = scaleIfNeeded(loc, original);
                        if (scaled != null) {
                            result.put(loc, scaled);
                            statScaled.incrementAndGet();
                            sample(loc, "scaled");
                        }
                    }
                    if (manifestEntries > 0) {
                        TextureCache.saveSizeManifest(currentCap, knownSizes);
                    }
                    LOGGER.info("[TextureScaler] atlas scan: {} textures listed, {} eligible, "
                                    + "{} downscaled ({} from cache, {} new) in {} ms",
                            all.size(), eligible, result.size(), fromCache,
                            result.size() - fromCache, System.currentTimeMillis() - started);
                } finally {
                    IN_LISTING.set(Boolean.FALSE);
                }
            } catch (Exception e) {
                LOGGER.warn("[TextureScaler] atlas scan failed: {}", e.toString());
            }
            listedScaled = result;
            listingComputed = true;
        }
    }

    @Override
    public void close() {
        // singleton — nothing to close
    }

    // ---- helpers ----------------------------------------------------------

    private static boolean isCandidate(ResourceLocation loc) {
        if (Config.skipNamespace(loc.getNamespace())) {
            return false;
        }
        String p = loc.getPath();
        return p.startsWith("textures/") && p.endsWith(".png");
    }

    private static boolean isEligible(ResourceLocation loc) {
        String p = loc.getPath();
        if (p.startsWith("textures/block/") || p.startsWith("textures/item/")) {
            return true;
        }
        for (String dir : extraDirs) {
            if (!dir.isEmpty() && p.startsWith("textures/" + dir + "/")) {
                return true;
            }
        }
        String key = spriteKey(loc);
        if (modelSizes.containsKey(key)) {
            return true;
        }
        Set<String> sprites = blockAtlasSprites;
        return sprites != null && sprites.contains(key);
    }

    private static String spriteKey(ResourceLocation loc) {
        String p = loc.getPath();
        return loc.getNamespace() + ":" + p.substring("textures/".length(), p.length() - ".png".length());
    }

    /**
     * Parses width/height from the PNG IHDR chunk without decoding the whole image.
     * Returns {@code null} when the bytes are not a plain PNG (caller falls back to full decode).
     */
    private static int[] readPngDimensions(byte[] data) {
        if (data == null || data.length < 24) {
            return null;
        }
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        if ((data[0] & 0xFF) != 0x89 || data[1] != 0x50 || data[2] != 0x4E || data[3] != 0x47
                || data[4] != 0x0D || data[5] != 0x0A || data[6] != 0x1A || data[7] != 0x0A) {
            return null;
        }
        // IHDR length (4 bytes) + "IHDR" (4 bytes) then width/height (big-endian)
        if (data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
            return null;
        }
        int w = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        int h = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        if (w <= 0 || h <= 0 || w > 65536 || h > 65536) {
            return null;
        }
        return new int[]{w, h};
    }

    /**
     * Downscales {@code original} if its largest edge exceeds the cap; returns the scaled
     * PNG bytes, or {@code null} when the texture should be left alone (small, model-UV
     * constrained, or unreadable). Uses the disk cache when enabled — with header dims the
     * cached entry is served without decoding the original image.
     */
    private static byte[] scaleIfNeeded(ResourceLocation loc, byte[] original) {
        int cap = currentCap;

        // Fast path: read the PNG header only — most textures are small and can be
        // skipped without decoding.
        int[] dims = readPngDimensions(original);
        if (dims != null && Math.max(dims[0], dims[1]) <= cap) {
            knownUntouched.add(loc);
            statSmall.incrementAndGet();
            sample(loc, "small");
            return null;
        }

        // Blockbench-style models with absolute-pixel UVs would break if their
        // texture_size exceeds the new size (doc pitfall #2) — leave them alone.
        // Decided before decoding: it only depends on the model scan, not the pixels.
        int modelSize = modelSizes.getOrDefault(spriteKey(loc), 0);
        if (modelSize > cap) {
            knownUntouched.add(loc);
            statModelSkipped.incrementAndGet();
            sample(loc, "model-uv-skip(" + modelSize + ")");
            return null;
        }

        // Disk-cache fast path: with header dims we can serve the cached scaled PNG
        // without decoding the (possibly huge) original. Counting is left to the caller
        // (ensureListingComputed / getResource) so stats are not double-counted.
        if (dims != null) {
            byte[] png = TextureCache.get(loc.getNamespace(), loc.getPath(), dims[0], dims[1], cap);
            if (png != null) {
                return png;
            }
        }

        NativeImage img = null;
        try {
            img = NativeImage.read(new ByteArrayInputStream(original));
            int w = img.getWidth();
            int h = img.getHeight();
            int maxEdge = Math.max(w, h);
            if (w <= 0 || h <= 0 || maxEdge <= cap) {
                knownUntouched.add(loc);
                statSmall.incrementAndGet();
                sample(loc, "small");
                return null;
            }

            int newW = Math.max(1, (int) Math.round((long) w * cap / maxEdge));
            int newH = Math.max(1, (int) Math.round((long) h * cap / maxEdge));

            NativeImage scaled = downscale(img, newW, newH);
            byte[] png;
            try {
                png = scaled.asByteArray();
            } finally {
                scaled.close();
            }
            TextureCache.put(loc.getNamespace(), loc.getPath(), w, h, cap, png);
            return png;
        } catch (Exception e) {
            // Unreadable/unsupported image: leave it alone rather than crash the reload.
            knownUntouched.add(loc);
            statFailed.incrementAndGet();
            sample(loc, "failed(" + e + ")");
            return null;
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /**
     * Read the original bytes: prefer the live resource manager (it always reflects the
     * current reload and skips us via the re-entrancy guard), then the reload snapshot.
     */
    private static byte[] readOriginal(ResourceLocation loc) {
        if (!READING_ORIGINAL.get()) {
            READING_ORIGINAL.set(Boolean.TRUE);
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getResourceManager() != null) {
                    Optional<Resource> r = mc.getResourceManager().getResource(loc);
                    if (r.isPresent()) {
                        try (InputStream in = r.get().open()) {
                            return in.readAllBytes();
                        }
                    }
                    // The merged manager already contains every pack (including us): an
                    // empty result means the texture exists nowhere, so the pack-by-pack
                    // fallback below would only repeat the same lookups (perf: tens of
                    // thousands of missing lookups per reload). Only reach the fallback
                    // when the merged query itself failed or we are re-entering.
                    return null;
                }
            } catch (Exception ignore) {
                // fall through to the snapshot
            } finally {
                READING_ORIGINAL.set(Boolean.FALSE);
            }
        }
        for (PackResources pack : sourcePacks) {
            if (pack instanceof TextureScalingPack) {
                continue;
            }
            try {
                IoSupplier<InputStream> supplier = pack.getResource(PackType.CLIENT_RESOURCES, loc);
                if (supplier != null) {
                    try (InputStream in = supplier.get()) {
                        return in.readAllBytes();
                    }
                }
            } catch (Exception ignore) {
                // try the next pack
            }
        }
        return null;
    }

    // ---- image processing -------------------------------------------------

    private static NativeImage downscale(NativeImage src, int newW, int newH) {
        NativeImage out = new NativeImage(newW, newH, false);
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        float sx = (float) srcW / newW;
        float sy = (float) srcH / newH;
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                float gx = (x + 0.5f) * sx - 0.5f;
                float gy = (y + 0.5f) * sy - 0.5f;
                out.setPixelRGBA(x, y, bilinearSample(src, gx, gy, srcW, srcH));
            }
        }
        return out;
    }

    private static int bilinearSample(NativeImage src, float gx, float gy, int srcW, int srcH) {
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        float fx = gx - x0;
        float fy = gy - y0;
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        if (x0 < 0) x0 = 0;
        if (x1 > srcW - 1) x1 = srcW - 1;
        if (y0 < 0) y0 = 0;
        if (y1 > srcH - 1) y1 = srcH - 1;
        int c00 = src.getPixelRGBA(x0, y0);
        int c10 = src.getPixelRGBA(x1, y0);
        int c01 = src.getPixelRGBA(x0, y1);
        int c11 = src.getPixelRGBA(x1, y1);
        return lerpPixel(lerpPixel(c00, c10, fx), lerpPixel(c01, c11, fx), fy);
    }

    private static int lerpPixel(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int r = (int) (((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t) + 0.5f);
        int g = (int) ((((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t) + 0.5f);
        int bl = (int) ((((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t) + 0.5f);
        int al = (int) ((((a >> 24) & 0xFF) + (((b >> 24) & 0xFF) - ((a >> 24) & 0xFF)) * t) + 0.5f);
        return r | (g << 8) | (bl << 16) | (al << 24);
    }
}
