package com.evernight.texturescaler.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * One client-resource texture as seen by the platform layer.
 *
 * <p>Deliberately expressed with plain strings and streams so the shared core never
 * references {@code ResourceLocation} / {@code Resource}, whose package, constructors
 * and even names changed between 1.12.2 and 1.21.4.</p>
 */
public interface TextureHandle {

    /** e.g. {@code citymod}. */
    String namespace();

    /** e.g. {@code textures/block/laptop.png}. */
    String path();

    /** Fresh stream over the original (un-scaled) bytes. */
    InputStream open() throws IOException;
}
