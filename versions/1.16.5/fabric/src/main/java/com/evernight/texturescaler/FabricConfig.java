package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fabric has no config API, so we use a plain {@code config/texturescaler.json}.
 * The file is re-read automatically when its mtime changes (edit + F3+T works).
 */
public final class FabricConfig {

    public boolean enabled = true;
    public int capOverride = 0;
    public int capDivisor = 32;
    public int capMin = 256;
    public int capMax = 2048;
    public boolean diskCacheEnabled = true;
    public String cacheDir = "texturescaler/cache";
    public List<String> skipNamespaces = new ArrayList<String>(Arrays.asList("minecraft", "texturescaler"));
    public List<String> extraTextureDirs = new ArrayList<String>();
    public boolean debugLog = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file;
    private static long lastModified = Long.MIN_VALUE;
    private static ScalerConfig cached;

    private FabricConfig() {
    }

    public static synchronized ScalerConfig snapshot() {
        Path f = file();
        long mtime = -2L;
        try {
            if (Files.exists(f)) {
                mtime = Files.getLastModifiedTime(f).toMillis();
            }
        } catch (Exception ignored) {
        }
        if (cached != null && mtime == lastModified) {
            return cached;
        }
        FabricConfig cfg = load(f);
        cached = toScalerConfig(cfg);
        lastModified = mtime;
        return cached;
    }

    private static Path file() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve("texturescaler.json");
        }
        return file;
    }

    private static FabricConfig load(Path f) {
        try {
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    FabricConfig c = GSON.fromJson(r, FabricConfig.class);
                    if (c != null) {
                        return c;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        FabricConfig c = new FabricConfig();
        save(f, c);
        return c;
    }

    private static void save(Path f, FabricConfig c) {
        try {
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(c, w);
            }
        } catch (Exception ignored) {
        }
    }

    private static ScalerConfig toScalerConfig(FabricConfig c) {
        ScalerConfig s = new ScalerConfig();
        s.enabled = c.enabled;
        s.capOverride = c.capOverride;
        s.capDivisor = c.capDivisor;
        s.capMin = c.capMin;
        s.capMax = c.capMax;
        s.diskCacheEnabled = c.diskCacheEnabled;
        if (c.cacheDir != null) {
            s.cacheDir = c.cacheDir;
        }
        if (c.skipNamespaces != null) {
            s.skipNamespaces = new ArrayList<String>(c.skipNamespaces);
        }
        if (c.extraTextureDirs != null) {
            s.extraTextureDirs = new ArrayList<String>(c.extraTextureDirs);
        }
        s.debugLog = c.debugLog;
        return s;
    }
}
