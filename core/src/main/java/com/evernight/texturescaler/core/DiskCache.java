package com.evernight.texturescaler.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * On-disk cache for downscaled textures plus the persistent "texture -> [w,h]" manifest.
 *
 * <p>Cache key = sha256 of {@code "namespace:path|srcWxsrcH|cap"}. The original dimensions
 * and the cap are part of the key, so any change (new mod version, changed cap, ...)
 * produces a different file and stale entries are simply never read again.</p>
 *
 * <p>Java 8 compatible: no {@code HexFormat}, {@code readAllBytes}, {@code Path.of}, etc.</p>
 */
public final class DiskCache {

    /** Name of the size manifest inside the cache dir. */
    private static final String SIZES_FILE = "sizes.json";

    private final ScalerPlatform platform;
    private volatile Path cacheDir;

    public DiskCache(ScalerPlatform platform) {
        this.platform = platform;
    }

    private ScalerConfig config() {
        return platform.config();
    }

    public Path dir() {
        Path local = cacheDir;
        if (local == null) {
            synchronized (this) {
                local = cacheDir;
                if (local == null) {
                    Path base = platform.gameDirectory();
                    String configured = config().cacheDir;
                    if (configured == null || configured.trim().isEmpty()) {
                        configured = "texturescaler/cache";
                    }
                    local = base.resolve(configured).toAbsolutePath().normalize();
                    try {
                        Files.createDirectories(local);
                    } catch (IOException e) {
                        platform.log().warn("[TextureScaler] cannot create cache dir {}: {}", local, e.toString());
                    }
                    cacheDir = local;
                }
            }
        }
        return local;
    }

    /**
     * Loads the persisted "texture path -> [w, h]" manifest, or an empty map when absent,
     * corrupt, or built for a different cap policy.
     */
    public Map<String, int[]> loadSizeManifest(String policy) {
        Map<String, int[]> result = new HashMap<String, int[]>();
        if (!config().diskCacheEnabled) {
            return result;
        }
        Path file = dir().resolve(SIZES_FILE);
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // NOTE: instance API on purpose — the static JsonParser.parseReader(String/Reader)
            // only exists since Gson 2.8.6, while Minecraft 1.12.2/1.16.5 ship Gson 2.8.0.
            @SuppressWarnings("deprecation")
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            if (policy == null || !root.has("policy") || !policy.equals(root.get("policy").getAsString())) {
                return result;
            }
            JsonObject files = root.has("files") ? root.getAsJsonObject("files") : null;
            if (files != null) {
                for (Map.Entry<String, JsonElement> e : files.entrySet()) {
                    JsonArray a = e.getValue().getAsJsonArray();
                    if (a.size() >= 2) {
                        result.put(e.getKey(), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()});
                    }
                }
            }
        } catch (Exception e) {
            platform.log().warn("[TextureScaler] size manifest read failed (will rebuild): {}", e.toString());
            return new HashMap<String, int[]>();
        }
        return result;
    }

    /** Persists the "texture path -> [w, h]" manifest (best effort, atomic rename). */
    public void saveSizeManifest(String policy, Map<String, int[]> sizes) {
        if (!config().diskCacheEnabled) {
            return;
        }
        JsonObject files = new JsonObject();
        for (Map.Entry<String, int[]> e : sizes.entrySet()) {
            JsonArray a = new JsonArray();
            a.add(e.getValue()[0]);
            a.add(e.getValue()[1]);
            files.add(e.getKey(), a);
        }
        JsonObject root = new JsonObject();
        root.addProperty("policy", policy == null ? "" : policy);
        root.add("files", files);
        try {
            Path target = dir().resolve(SIZES_FILE);
            Path tmp = target.resolveSibling(SIZES_FILE + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new Gson().toJson(root, writer);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            platform.log().warn("[TextureScaler] size manifest write failed: {}", e.toString());
        }
    }

    /** Read a cached entry, or {@code null} when absent/corrupt. */
    public byte[] get(String namespace, String path, int srcW, int srcH, int cap) {
        return get(namespace, path, srcW, srcH, cap, "");
    }

    /**
     * Variant-aware read: {@code variant} distinguishes two different resize results for the
     * same original size and cap (e.g. {@code "k4"} for an animation-safe integer divide),
     * so a stale entry can never be served for the other policy.
     */
    public byte[] get(String namespace, String path, int srcW, int srcH, int cap, String variant) {
        if (!config().diskCacheEnabled) {
            return null;
        }
        Path file = fileFor(namespace, path, srcW, srcH, cap, variant);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readAll(in);
        } catch (IOException e) {
            platform.log().warn("[TextureScaler] cache read failed for {}:{} ({}), will re-scale",
                    namespace, path, e.toString());
            return null;
        }
    }

    /** Store an entry (best effort, atomic rename). */
    public void put(String namespace, String path, int srcW, int srcH, int cap, byte[] png) {
        put(namespace, path, srcW, srcH, cap, "", png);
    }

    /** Variant-aware store; see {@link #get(String, String, int, int, int, String)}. */
    public void put(String namespace, String path, int srcW, int srcH, int cap, String variant, byte[] png) {
        if (!config().diskCacheEnabled) {
            return;
        }
        try {
            Path target = fileFor(namespace, path, srcW, srcH, cap, variant);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(png);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            platform.log().warn("[TextureScaler] cache write failed for {}:{}: {}", namespace, path, e.toString());
        }
    }

    private Path fileFor(String namespace, String path, int srcW, int srcH, int cap, String variant) {
        String key = namespace + ":" + path + "|" + srcW + "x" + srcH + "|" + cap
                + (variant == null || variant.isEmpty() ? "" : "|" + variant);
        return dir().resolve(sha256(key) + ".png");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Java 8 stand-in for {@code InputStream.readAllBytes()} (Java 9+). */
    public static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
