package com.evernight.texturescaler.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * One client-resource texture as seen by the platform layer.
 */
public interface TextureHandle {

    /**
     * e.g. {@code citymod}.
     */
    String namespace();

    /**
     * e.g. {@code textures/block/laptop.png}.
     */
    String path();

    /**
     * Fresh stream over the original (un-scaled) bytes.
     */
    InputStream open() throws IOException;
}
