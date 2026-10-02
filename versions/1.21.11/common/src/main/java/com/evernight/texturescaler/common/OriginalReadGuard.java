package com.evernight.texturescaler.common;

/** Lets the platform tell the overlay pack to step aside while an original is read. */
public interface OriginalReadGuard {

    boolean isReadingOriginal();
}