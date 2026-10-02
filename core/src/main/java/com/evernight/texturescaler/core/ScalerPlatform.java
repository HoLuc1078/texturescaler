package com.evernight.texturescaler.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the shared core needs from the concrete loader/version.
 *
 * <p>Implementations live in {@code versions/<mc>/<loader>} and are the only
 * version-specific code besides the loader bootstrap.</p>
 */
public interface ScalerPlatform {

    ScalerLog log();

    ScalerConfig config();

    /** The .minecraft directory (used for the on-disk cache). */
    Path gameDirectory();

    /**
     * {@code GL_MAX_TEXTURE_SIZE}, or {@code <= 0} when it cannot be queried yet.
     *
     * <p>May be called at any point in the client lifecycle — including from a mod
     * initializer that runs inside the Minecraft constructor, before the window exists.
     * Implementations <b>must not</b> touch a GL entry point unless the render backend has
     * created the GL capabilities ({@code GL.createCapabilities()} on LWJGL 3, the current
     * context capabilities on LWJGL 2): calling into an uninitialised function table kills
     * the JVM in native code, where no Java {@code catch} can help. Return {@code <= 0}
     * instead; the engine latches the first positive value and retries on each reload.</p>
     */
    int gpuMaxTextureSize();

    /**
     * Every PNG under {@code assets/<ns>/textures/} currently visible in the client
     * resource stack, including the originals from every other pack. Must NOT include
     * this mod's own overlay (the caller is responsible for excluding itself).
     */
    List<TextureHandle> listAllTextures();

    /**
     * Every block/item model JSON currently visible, keyed by a model id. The id spelling
     * is up to the platform; {@link ModelScanner} accepts all common spellings.
     */
    Map<String, String> listModels();

    /**
     * Reads the original bytes of one texture from the packs below the overlay, or
     * {@code null} when the texture does not exist. Implementations must guard against
     * re-entering their own pack (otherwise the read recurses forever).
     */
    byte[] readOriginal(String namespace, String path);

    /** Namespaces the overlay pack should claim; empty means "not known yet". */
    Set<String> claimedNamespaces();

    /** Cheap identity of the pack stack (e.g. sorted pack ids joined by commas). */
    String packFingerprint();
}
