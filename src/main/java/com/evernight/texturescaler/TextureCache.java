package com.evernight.texturescaler;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Small on-disk cache for downscaled textures.
 *
 * <p>Key = sha256 of "namespace:path|srcWxsrcH|cap". The original dimensions and the
 * cap are part of the key, so any change (new mod version, changed cap, …) produces a
 * different file and stale entries are simply never read again.</p>
 */
final class TextureCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Path cacheDir;

    private TextureCache() {
    }

    static synchronized Path dir() {
        if (cacheDir == null) {
            Path base = Minecraft.getInstance().gameDirectory.toPath();
            cacheDir = base.resolve(Config.cacheDir()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException e) {
                LOGGER.warn("[TextureScaler] cannot create cache dir {}: {}", cacheDir, e.toString());
            }
        }
        return cacheDir;
    }

    /** Read a cached entry, or {@code null} when absent/corrupt. */
    static byte[] get(String namespace, String path, int srcW, int srcH, int cap) {
        if (!Config.diskCacheEnabled()) {
            return null;
        }
        Path file = fileFor(namespace, path, srcW, srcH, cap);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("[TextureScaler] cache read failed for {}:{} ({}), will re-scale", namespace, path, e.toString());
            return null;
        }
    }

    /** Store an entry (best effort, atomic rename). */
    static void put(String namespace, String path, int srcW, int srcH, int cap, byte[] png) {
        if (!Config.diskCacheEnabled()) {
            return;
        }
        try {
            Path dir = dir();
            Path target = fileFor(namespace, path, srcW, srcH, cap);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(png);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("[TextureScaler] cache write failed for {}:{}: {}", namespace, path, e.toString());
        }
    }

    private static Path fileFor(String namespace, String path, int srcW, int srcH, int cap) {
        String key = namespace + ":" + path + "|" + srcW + "x" + srcH + "|" + cap;
        return dir().resolve(sha256(key) + ".png");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            // Cannot happen on a standard JVM; fall back to identity-ish key.
            return Integer.toHexString(s.hashCode());
        }
    }
}
