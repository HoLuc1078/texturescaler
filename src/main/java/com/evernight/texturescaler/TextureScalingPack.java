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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dynamic, highest-priority resource pack that downscales oversized block-atlas
 * textures at load time.
 *
 * <p>For every requested PNG that is (a) under {@code textures/**}, (b) in a namespace we
 * are allowed to touch and (c) actually part of the block atlas (block/item dirs, extra
 * configured dirs, model-referenced textures, or sprites observed in the previous reload),
 * the original bytes are read from the other packs and, if the largest edge exceeds the
 * GPU-derived cap, a scaled-down PNG is served instead.</p>
 *
 * <p>Everything we do not modify returns {@code null}, letting the resource manager fall
 * through to the original pack — this also avoids recursion when we read originals.</p>
 */
public final class TextureScalingPack implements PackResources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PACK_ID = "texturescaler_overlay";
    private static final byte[] PACK_META = ("{\"pack\":{\"description\":{\"text\":\"Texture Scaler overlay\"},"
            + "\"pack_format\":15}}").getBytes(StandardCharsets.UTF_8);

    private static final TextureScalingPack INSTANCE = new TextureScalingPack();

    // ---- global state -----------------------------------------------------

    /** GPU-derived cap (largest allowed edge). Updated on client setup and each reload. */
    private static volatile int currentCap = 512;

    /** Whether the GPU cap has been detected at least once (so we always log the first result). */
    private static volatile boolean gpuDetected = false;

    /** Snapshot of the other packs, refreshed on every resource reload. */
    private static volatile List<PackResources> sourcePacks = List.of();

    /** Texture -> max texture_size edge of referencing models (0 = no constraint). */
    private static volatile Map<String, Integer> modelSizes = Map.of();

    /** "ns:spritePath" set of block-atlas sprites, captured after the previous stitch. */
    private static volatile Set<String> blockAtlasSprites = null;

    /** Namespaces this pack claims, so the resource manager routes lookups to us. */
    private static volatile Set<String> claimedNamespaces = null;
    private static final Object NAMESPACE_LOCK = new Object();

    /** Extra block-atlas texture directories from the config. */
    private static volatile List<String> extraDirs = List.of();

    /** In-memory scaled results. */
    private static final ConcurrentHashMap<ResourceLocation, byte[]> scaledCache = new ConcurrentHashMap<>();

    /** Locations we decided not to touch (small, model-UV constrained, or unreadable). */
    private static final Set<ResourceLocation> knownUntouched = ConcurrentHashMap.newKeySet();

    /** Locations with no original available anywhere. */
    private static final Set<ResourceLocation> knownMissing = ConcurrentHashMap.newKeySet();

    /** Re-entrancy guard for the live-resource-manager fallback. */
    private static final ThreadLocal<Boolean> READING_ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
        if (!Config.enabled()) {
            return;
        }
        List<PackResources> snapshot = rm.listPacks().toList();
        sourcePacks = snapshot;
        scaledCache.clear();
        knownUntouched.clear();
        knownMissing.clear();
        extraDirs = new ArrayList<>(Config.extraTextureDirs());

        // Refresh the claimed namespaces from the live pack list (excluding ourselves).
        Set<String> ns = new HashSet<>();
        for (PackResources pack : snapshot) {
            if (pack instanceof TextureScalingPack) {
                continue;
            }
            try {
                for (String s : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                    if (!Config.skipNamespace(s)) {
                        ns.add(s);
                    }
                }
            } catch (Exception ignore) {
            }
        }
        claimedNamespaces = Set.copyOf(ns);

        updateCap();
        LOGGER.info("[TextureScaler] resource reload started: {} source packs, cap = {}, {} namespaces claimed",
                snapshot.size(), currentCap, claimedNamespaces.size());
    }

    public static void setModelSizes(Map<String, Integer> sizes) {
        modelSizes = sizes == null ? Map.of() : Map.copyOf(sizes);
    }

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
        Set<String> cached = claimedNamespaces;
        if (cached != null) {
            return cached;
        }
        // Lazily compute from the pack repository (first manager construction).
        synchronized (NAMESPACE_LOCK) {
            if (claimedNamespaces != null) {
                return claimedNamespaces;
            }
            Set<String> result = new HashSet<>();
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    PackRepository repo = mc.getResourcePackRepository();
                    if (repo != null) {
                        for (Pack pack : repo.getSelectedPacks()) {
                            try (PackResources pr = pack.open()) {
                                if (pr instanceof TextureScalingPack) {
                                    continue;
                                }
                                for (String s : pr.getNamespaces(PackType.CLIENT_RESOURCES)) {
                                    if (!Config.skipNamespace(s)) {
                                        result.add(s);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            claimedNamespaces = Set.copyOf(result);
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

        byte[] cached = scaledCache.get(location);
        if (cached != null) {
            return () -> new ByteArrayInputStream(cached);
        }

        byte[] original = readOriginal(location);
        if (original == null) {
            knownMissing.add(location);
            return null;
        }

        int cap = currentCap;

        // Fast path: read the PNG header only — most textures are small and can be
        // skipped without decoding.
        int[] dims = readPngDimensions(original);
        if (dims != null && Math.max(dims[0], dims[1]) <= cap) {
            knownUntouched.add(location);
            return null;
        }

        NativeImage img = null;
        try {
            img = NativeImage.read(new ByteArrayInputStream(original));
            int w = img.getWidth();
            int h = img.getHeight();
            int maxEdge = Math.max(w, h);
            if (w <= 0 || h <= 0 || maxEdge <= cap) {
                knownUntouched.add(location);
                return null;
            }

            // Blockbench-style models with absolute-pixel UVs would break if their
            // texture_size exceeds the new size (doc pitfall #2) — leave them alone.
            int modelSize = modelSizes.getOrDefault(spriteKey(location), 0);
            if (modelSize > cap) {
                knownUntouched.add(location);
                if (Config.debugLog()) {
                    LOGGER.info("[TextureScaler] skip {} (model texture_size {} > cap {})",
                            location, modelSize, cap);
                }
                return null;
            }

            int newW = Math.max(1, (int) Math.round((long) w * cap / maxEdge));
            int newH = Math.max(1, (int) Math.round((long) h * cap / maxEdge));

            byte[] png = TextureCache.get(location.getNamespace(), location.getPath(), w, h, cap);
            if (png == null) {
                NativeImage scaled = downscale(img, newW, newH);
                try {
                    png = scaled.asByteArray();
                } finally {
                    scaled.close();
                }
                TextureCache.put(location.getNamespace(), location.getPath(), w, h, cap, png);
            }

            byte[] result = png;
            scaledCache.put(location, result);
            if (Config.debugLog()) {
                LOGGER.info("[TextureScaler] scaled {} {}x{} -> {}x{}", location, w, h, newW, newH);
            }
            return () -> new ByteArrayInputStream(result);
        } catch (Exception e) {
            // Unreadable/unsupported image: leave it alone rather than crash the reload.
            knownUntouched.add(location);
            if (Config.debugLog()) {
                LOGGER.warn("[TextureScaler] failed to process {}: {}", location, e.toString());
            }
            return null;
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput resourceOutput) {
        // We do not enumerate; the original packs list everything.
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

    /** Read the original bytes from the other packs (snapshot first, then live manager). */
    private static byte[] readOriginal(ResourceLocation loc) {
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
        // Fallback: ask the live resource manager (it skips us via the re-entrancy guard,
        // so this resolves to the original texture without infinite recursion).
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
                }
            } catch (Exception ignore) {
            } finally {
                READING_ORIGINAL.set(Boolean.FALSE);
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
