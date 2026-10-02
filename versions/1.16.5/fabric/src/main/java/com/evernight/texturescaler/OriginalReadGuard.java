package com.evernight.texturescaler;

/**
 * 1.16.5-local copy of the shared guard interface.
 *
 * <p>The shared {@code com.evernight.texturescaler.common} sources target 1.18+ and cannot be
 * compiled against the 1.16.5 vanilla API, so this module carries its own copy.</p>
 */
public interface OriginalReadGuard {

    boolean isReadingOriginal();
}
