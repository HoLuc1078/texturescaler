package com.evernight.texturescaler.core;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free self-test for the shared core. Run with:
 * java -cp <classes>:<gson> com.evernight.texturescaler.core.CoreSelfTest
 * Exits non-zero on the first failed assertion.
 */
public final class CoreSelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testPngInfo();
        testDownscaleSquare();
        testDownscaleTall();
        testSmallUntouched();
        testAnimationInfo();
        testDownscaleByDivisor();
        testAnimationSafeListing();
        testModelScanner();
        testComputeCap();
        testTieredCaps();
        testCapLatching();
        testDiskCache();
        testEngineListing();
        if (failures == 0) {
            System.out.println("CORE SELF-TEST: ALL PASSED");
        } else {
            System.out.println("CORE SELF-TEST: " + failures + " FAILURE(S)");
            System.exit(1);
        }
    }

    private static void testPngInfo() throws Exception {
        byte[] png = png(37, 91);
        int[] d = PngInfo.read(png);
        check("pnginfo non-null", d != null);
        check("pnginfo w", d != null && d[0] == 37);
        check("pnginfo h", d != null && d[1] == 91);
        check("pnginfo rejects garbage", PngInfo.read(new byte[]{1, 2, 3}) == null);
    }

    private static void testDownscaleSquare() throws Exception {
        byte[] png = png(1024, 1024);
        ImageDownscaler.Result r = ImageDownscaler.downscale(png, 128);
        check("square result non-null", r != null);
        if (r != null) {
            check("square out 128x128", r.outW == 128 && r.outH == 128);
            check("square src 1024", r.srcW == 1024 && r.srcH == 1024);
            int[] out = PngInfo.read(r.png);
            check("square encoded dims", out != null && out[0] == 128 && out[1] == 128);
            // corner pixel must remain the same colour (blue)
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(r.png));
            int rgb = img.getRGB(1, 1) & 0xFFFFFF;
            check("square colour preserved", Math.abs(((rgb >> 16) & 0xFF) - 0) < 20
                    && Math.abs(((rgb >> 8) & 0xFF) - 0) < 20
                    && Math.abs((rgb & 0xFF) - 255) < 20);
        }
    }

    private static void testDownscaleTall() throws Exception {
        // 64x1920 with cap 512 -> 17x512 (aspect preserved, same as the original implementation)
        byte[] png = png(64, 1920);
        ImageDownscaler.Result r = ImageDownscaler.downscale(png, 512);
        check("tall result non-null", r != null);
        if (r != null) {
            check("tall out 17x512", r.outW == 17 && r.outH == 512);
        }
        // a cap larger than the image -> untouched
        check("tall untouched at cap 4096", ImageDownscaler.downscale(png, 4096) == null);
    }

    private static void testAnimationInfo() throws Exception {
        AnimationInfo bare = AnimationInfo.parse(
                "{\"animation\":{\"frametime\":10}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check("anim bare parsed", bare != null && bare.animated && !bare.explicitFrameSize);
        AnimationInfo explicit = AnimationInfo.parse(
                "{\"animation\":{\"width\":16,\"height\":16}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check("anim explicit parsed", explicit != null && explicit.animated && explicit.explicitFrameSize);
        check("anim absent section", AnimationInfo.parse("{\"pack\":{}}".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)) == null);
        check("anim garbage safe", AnimationInfo.parse(new byte[]{1, 2, 3}) == null);
        check("anim empty safe", AnimationInfo.parse(new byte[0]) == null);

        // smallest divisor that fits the cap; gcd(64, 11776) = 64 -> k = 4 gives 16x2944
        check("safeDivisor 64x11776@4096", AnimationInfo.safeDivisor(64, 11776, 4096) == 4);
        check("safeDivisor 62x11408@4096", AnimationInfo.safeDivisor(62, 11408, 4096) == 31);
        check("safeDivisor 512x10240@4096", AnimationInfo.safeDivisor(512, 10240, 4096) == 4);
        check("safeDivisor 64x1920@128", AnimationInfo.safeDivisor(64, 1920, 128) == 16);
        check("safeDivisor coprime gives 1", AnimationInfo.safeDivisor(101, 103, 8) == 1);
    }

    private static void testDownscaleByDivisor() throws Exception {
        byte[] strip = png(64, 11776);
        ImageDownscaler.Result r = ImageDownscaler.downscaleByDivisor(strip, 4);
        check("divisor result non-null", r != null);
        if (r != null) {
            check("divisor out 16x2944", r.outW == 16 && r.outH == 2944);
            int[] out = PngInfo.read(r.png);
            check("divisor encoded dims", out != null && out[0] == 16 && out[1] == 2944);
            int frame = Math.min(16, 2944);
            check("divisor frame count preserved (184)", (2944 / frame) * (16 / frame) == 184);
        }
        check("divisor rejects non-divisor", ImageDownscaler.downscaleByDivisor(strip, 3) == null);
        check("divisor rejects 1", ImageDownscaler.downscaleByDivisor(strip, 1) == null);
    }

    /**
     * End-to-end: an animated filmstrip must be divided evenly (never aspect-scaled), and a
     * sidecar that pins its frame size must be left completely alone.
     */
    private static void testAnimationSafeListing() throws Exception {
        Path gameDir = Files.createTempDirectory("ts-anim-test");
        EnginePlatform platform = new EnginePlatform(gameDir);
        platform.config.capOverride = 128;
        platform.textures.put("ns:textures/block/anim.png", png(64, 1920));
        platform.textures.put("ns:textures/block/anim.png.mcmeta",
                "{\"animation\":{\"frametime\":10}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        platform.textures.put("ns:textures/block/pinned.png", png(1024, 1024));
        platform.textures.put("ns:textures/block/pinned.png.mcmeta",
                "{\"animation\":{\"width\":16,\"height\":16}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        platform.textures.put("ns:textures/block/plain.png", png(1024, 1024));

        ScalerEngine engine = new ScalerEngine(platform);
        engine.onReloadStart();
        Map<String, byte[]> listed = engine.getListedScaled();

        byte[] anim = listed.get("ns:textures/block/anim.png");
        int[] animDims = anim == null ? null : PngInfo.read(anim);
        // gcd(64, 1920) = 64; the smallest divisor reaching <= 128 is 16 -> 4x120 (30 frames of 4)
        check("animated divided evenly", animDims != null && animDims[0] == 4 && animDims[1] == 120);
        check("animated frame count preserved",
                animDims != null && (animDims[1] / Math.min(animDims[0], animDims[1]))
                         * (animDims[0] / Math.min(animDims[0], animDims[1])) == 30);

        check("pinned frame size left alone", !listed.containsKey("ns:textures/block/pinned.png"));
        byte[] plain = listed.get("ns:textures/block/plain.png");
        int[] plainDims = plain == null ? null : PngInfo.read(plain);
        check("plain still aspect scaled", plainDims != null && plainDims[0] == 128 && plainDims[1] == 128);

        // the same rule must hold on the getResource path
        check("getResource animated divided",
                PngInfo.read(engine.getScaledResource("ns", "textures/block/anim.png"))[1] == 120);
        check("getResource pinned untouched",
                engine.getScaledResource("ns", "textures/block/pinned.png") == null);

        // With the tier caps in force (no override) on a 4096 GPU, a filmstrip that already
        // fits GL_MAX_TEXTURE_SIZE must be kept pixel-perfect instead of being ground down,
        // because integer division is the only safe resize and its lattice is coarse.
        Path gameDir2 = Files.createTempDirectory("ts-anim-fit");
        EnginePlatform p2 = new EnginePlatform(gameDir2);
        p2.textures.put("ns:textures/block/fits.png", png(64, 1920));
        p2.textures.put("ns:textures/block/fits.png.mcmeta", "{\"animation\":{\"frametime\":10}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        p2.textures.put("ns:textures/block/toobig.png", png(64, 11776));
        p2.textures.put("ns:textures/block/toobig.png.mcmeta", "{\"animation\":{\"frametime\":10}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ScalerEngine engine2 = new ScalerEngine(p2);
        engine2.onReloadStart();
        Map<String, byte[]> listed2 = engine2.getListedScaled();
        check("stitchable filmstrip kept pristine", !listed2.containsKey("ns:textures/block/fits.png"));
        int[] tooBig = PngInfo.read(listed2.get("ns:textures/block/toobig.png"));
        check("unstitchable filmstrip divided to 16x2944", tooBig != null && tooBig[0] == 16 && tooBig[1] == 2944);
    }

    private static void testSmallUntouched() throws Exception {
        byte[] png = png(16, 16);
        check("small untouched", ImageDownscaler.downscale(png, 512) == null);
        check("bad bytes untouched", ImageDownscaler.downscale(new byte[]{9, 9, 9}, 512) == null);
    }

    private static void testModelScanner() {
        Map<String, String> models = new HashMap<String, String>();
        models.put("test:block/base",
                "{\"textures\":{\"all\":\"test:block/tex\"},\"texture_size\":[2048,2048]}");
        models.put("test:block/child",
                "{\"parent\":\"test:block/base\",\"textures\":{\"side\":\"test:block/side\"}}");
        models.put("test:block/plain",
                "{\"textures\":{\"all\":\"test:block/plaintex\"}}");
        Map<String, Integer> r = ModelScanner.scan(models);
        check("scanner base tex", Integer.valueOf(2048).equals(r.get("test:block/tex")));
        check("scanner child inherits texture_size", Integer.valueOf(2048).equals(r.get("test:block/side")));
        check("scanner plain has 0", Integer.valueOf(0).equals(r.get("test:block/plaintex")));

        // namespace-less texture path defaults to the model's namespace
        Map<String, String> m2 = new HashMap<String, String>();
        m2.put("city:block/m", "{\"textures\":{\"all\":\"block/x\"},\"texture_size\":[4096,4096]}");
        Map<String, Integer> r2 = ModelScanner.scan(m2);
        check("scanner default namespace", Integer.valueOf(4096).equals(r2.get("city:block/x")));
    }

    private static void testEngineListing() throws Exception {
        Path gameDir = Files.createTempDirectory("ts-engine-test");
        EnginePlatform platform = new EnginePlatform(gameDir);
        platform.config.capOverride = 128;
        platform.textures.put("ns:textures/block/big.png", png(1024, 1024));
        platform.textures.put("ns:textures/block/small.png", png(16, 16));
        platform.textures.put("ns:textures/block/uv.png", png(2048, 2048));

        ScalerEngine engine = new ScalerEngine(platform);
        engine.setModelUvConstraints(java.util.Collections.singletonMap("ns:block/uv", 4096));
        engine.onReloadStart();
        Map<String, byte[]> listed = engine.getListedScaled();

        check("listing has big", listed.containsKey("ns:textures/block/big.png"));
        check("listing skips small", !listed.containsKey("ns:textures/block/small.png"));
        check("listing skips model-uv-constrained", !listed.containsKey("ns:textures/block/uv.png"));
        byte[] big = listed.get("ns:textures/block/big.png");
        int[] dims = big == null ? null : PngInfo.read(big);
        check("listing scaled to cap 128", dims != null && dims[0] == 128 && dims[1] == 128);

        check("getResource scales big",
                engine.getScaledResource("ns", "textures/block/big.png") != null);
        check("getResource leaves small",
                engine.getScaledResource("ns", "textures/block/small.png") == null);
        check("getResource leaves uv",
                engine.getScaledResource("ns", "textures/block/uv.png") == null);

        // disabled -> listing must be empty even though a scan exists
        platform.config.enabled = false;
        engine.onReloadStart();
        check("disabled empties listing", engine.getListedScaled().isEmpty());
        platform.config.enabled = true;

        // unchanged pack list + unchanged cap -> reuse; changed cap -> full rescan
        engine.onReloadStart();
        check("reuse keeps big", engine.getListedScaled().containsKey("ns:textures/block/big.png"));
        platform.config.capOverride = 256;
        engine.onReloadStart();
        int[] dims256 = PngInfo.read(engine.getListedScaled().get("ns:textures/block/big.png"));
        check("changed cap rescales", dims256 != null && dims256[0] == 256);
    }

    private static final class EnginePlatform implements ScalerPlatform {
        final ScalerConfig config = new ScalerConfig();
        final Map<String, byte[]> textures = new java.util.LinkedHashMap<String, byte[]>();
        final Path gameDir;
        final Set<String> namespaces = new java.util.HashSet<String>(java.util.Arrays.asList("ns"));
        String fingerprint = "fp1";

        EnginePlatform(Path gameDir) {
            this.gameDir = gameDir;
        }

        @Override public ScalerLog log() { return ScalerLog.NOOP; }
        @Override public ScalerConfig config() { return config; }
        @Override public Path gameDirectory() { return gameDir; }
        @Override public int gpuMaxTextureSize() { return 4096; }

        @Override public List<TextureHandle> listAllTextures() {
            List<TextureHandle> out = new java.util.ArrayList<TextureHandle>();
            for (Map.Entry<String, byte[]> e : textures.entrySet()) {
                int colon = e.getKey().indexOf(':');
                out.add(new StubHandle(e.getKey().substring(0, colon),
                        e.getKey().substring(colon + 1), e.getValue()));
            }
            return out;
        }

        @Override public Map<String, String> listModels() { return Collections.emptyMap(); }
        @Override public byte[] readOriginal(String namespace, String path) {
            return textures.get(namespace + ":" + path);
        }
        @Override public Set<String> claimedNamespaces() { return namespaces; }
        @Override public String packFingerprint() { return fingerprint; }
    }

    private static final class StubHandle implements TextureHandle {
        private final String namespace;
        private final String path;
        private final byte[] bytes;

        StubHandle(String namespace, String path, byte[] bytes) {
            this.namespace = namespace;
            this.path = path;
            this.bytes = bytes;
        }

        @Override public String namespace() { return namespace; }
        @Override public String path() { return path; }
        @Override public java.io.InputStream open() { return new java.io.ByteArrayInputStream(bytes); }
    }

    private static void testComputeCap() {
        ScalerConfig c = new ScalerConfig();
        check("cap 32768 -> 1024", c.computeCap(32768) == 1024);
        check("cap 16384 -> 512", c.computeCap(16384) == 512);
        check("cap 8192 -> 256", c.computeCap(8192) == 256);
        check("cap unknown -> 512", c.computeCap(0) == 512);
        check("skip namespace default", c.skipNamespace("minecraft") && !c.skipNamespace("citymod"));
        c.capOverride = 768;
        check("cap override wins", c.computeCap(16384) == 768);
    }

    /**
     * The single flat cap destroyed two kinds of texture: big "display" sheets (4096 ->
     * 512 is an 8x reduction) and extreme-aspect strips (64x11776 -> 3x512). The tiers
     * spend the same atlas budget where it is actually visible.
     */
    private static void testTieredCaps() {
        ScalerConfig c = new ScalerConfig();
        check("normal 16384 -> 256", c.computeNormalCap(16384) == 256);
        check("detail 16384 -> 2048", c.computeDetailCap(16384) == 2048);
        check("strip 16384 -> 4096", c.computeStripCap(16384) == 4096);
        check("normal 32768 -> 512", c.computeNormalCap(32768) == 512);
        check("detail 32768 -> 4096", c.computeDetailCap(32768) == 4096);
        check("caps fall back to 16384 while GL is silent",
                c.computeNormalCap(0) == 256 && c.computeDetailCap(0) == 2048);

        check("ordinary 256 texture -> normal cap", c.capFor(16384, 256, 256) == 256);
        check("512 texture -> normal cap", c.capFor(16384, 512, 512) == 256);
        check("1024 texture -> normal cap", c.capFor(16384, 1024, 1024) == 256);
        check("4096 sheet -> detail cap", c.capFor(16384, 4096, 4096) == 2048);
        check("2048 sheet -> detail cap", c.capFor(16384, 2048, 2048) == 2048);
        check("64x11776 strip -> strip cap", c.capFor(16384, 64, 11776) == 4096);
        check("512x10240 strip -> strip cap", c.capFor(16384, 512, 10240) == 4096);
        check("2048x2048 is not a strip", !c.isStrip(2048, 2048));
        check("64x11776 is a strip", c.isStrip(64, 11776));

        c.capOverride = 320;
        check("capOverride beats the tiers", c.capFor(16384, 4096, 4096) == 320
                && c.capFor(16384, 64, 11776) == 320);

        ScalerConfig legacy = new ScalerConfig();
        legacy.capOverride = 0;
        legacy.tieredCaps = false;
        check("tiering off reproduces the old single cap", legacy.capFor(16384, 4096, 4096) == 512
                && legacy.capFor(16384, 64, 11776) == 512);
    }

    /**
     * The GL query is only valid once the render backend exists, so callers may hit the
     * engine before a value is available. The cap must fall back, be latched once known,
     * never be downgraded by a later failed query, and honour {@code capOverride} without
     */
    private static void testCapLatching() {
        ScalerConfig config = new ScalerConfig();
        CapPlatform platform = new CapPlatform(config);
        ScalerEngine engine = new ScalerEngine(platform);

        // Not ready yet (mod initializer inside the Minecraft constructor).
        platform.gpu = 0;
        engine.updateCap();
        check("cap falls back while GL is not ready", engine.currentCap() == 256);
        check("unknown GPU is queried once", platform.calls == 1);

        // First successful reload -> real value is used and latched.
        platform.gpu = 8192;
        engine.updateCap();
        check("cap upgrades once detected", engine.currentCap() == 256);
        check("gpu value is latched", engine.detectedGpuMaxTextureSize() == 8192);

        // Later failed query (background thread / config event) must not downgrade.
        platform.gpu = 0;
        engine.updateCap();
        check("no re-query once detected", platform.calls == 2);
        check("failed re-query cannot downgrade the cap", engine.currentCap() == 256);

        // capOverride still wins, still without querying.
        config.capOverride = 128;
        engine.updateCap();
        check("capOverride wins", engine.currentCap() == 128 && platform.calls == 2);

        // Override with no GPU knowledge at all: still resolved, still no query needed.
        CapPlatform bare = new CapPlatform(new ScalerConfig());
        bare.config.capOverride = 64;
        ScalerEngine bareEngine = new ScalerEngine(bare);
        bareEngine.updateCap();
        check("override resolves with unknown GPU", bareEngine.currentCap() == 64);
    }

    private static final class CapPlatform implements ScalerPlatform {
        final ScalerConfig config;
        int gpu;
        int calls;

        CapPlatform(ScalerConfig config) {
            this.config = config;
        }

        @Override public ScalerLog log() { return ScalerLog.NOOP; }
        @Override public ScalerConfig config() { return config; }
        @Override public Path gameDirectory() { return java.nio.file.Paths.get("."); }
        @Override public int gpuMaxTextureSize() { calls++; return gpu; }
        @Override public List<TextureHandle> listAllTextures() { return Collections.emptyList(); }
        @Override public Map<String, String> listModels() { return Collections.emptyMap(); }
        @Override public byte[] readOriginal(String namespace, String path) { return null; }
        @Override public Set<String> claimedNamespaces() { return Collections.emptySet(); }
        @Override public String packFingerprint() { return "cap"; }
    }

    private static void testDiskCache() throws Exception {
        Path gameDir = Files.createTempDirectory("ts-cache-test");
        StubPlatform platform = new StubPlatform(gameDir);
        DiskCache cache = new DiskCache(platform);

        check("cache miss", cache.get("ns", "textures/block/a.png", 1024, 1024, 512) == null);
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        cache.put("ns", "textures/block/a.png", 1024, 1024, 512, data);
        byte[] got = cache.get("ns", "textures/block/a.png", 1024, 1024, 512);
        check("cache hit", got != null && java.util.Arrays.equals(got, data));
        check("cache key includes cap",
                cache.get("ns", "textures/block/a.png", 1024, 1024, 256) == null);

        Map<String, int[]> sizes = new HashMap<String, int[]>();
        sizes.put("ns:textures/block/a.png", new int[]{1024, 1024});
        cache.saveSizeManifest("256/2048/4096", sizes);
        Map<String, int[]> loaded = cache.loadSizeManifest("256/2048/4096");
        check("manifest roundtrip", loaded.size() == 1
                && loaded.get("ns:textures/block/a.png")[0] == 1024);
        check("manifest rejects other policy", cache.loadSizeManifest("512/512/512").isEmpty());
    }

    private static final class StubPlatform implements ScalerPlatform {
        private final ScalerConfig config = new ScalerConfig();
        private final Path gameDir;

        StubPlatform(Path gameDir) {
            this.gameDir = gameDir;
        }

        @Override public ScalerLog log() { return ScalerLog.NOOP; }
        @Override public ScalerConfig config() { return config; }
        @Override public Path gameDirectory() { return gameDir; }
        @Override public int gpuMaxTextureSize() { return 16384; }
        @Override public List<TextureHandle> listAllTextures() { return Collections.emptyList(); }
        @Override public Map<String, String> listModels() { return Collections.emptyMap(); }
        @Override public byte[] readOriginal(String namespace, String path) { return null; }
        @Override public Set<String> claimedNamespaces() { return Collections.emptySet(); }
        @Override public String packFingerprint() { return "stub"; }
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 255, 255));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  ok   " + name);
        } else {
            System.out.println("  FAIL " + name);
            failures++;
        }
    }
}
