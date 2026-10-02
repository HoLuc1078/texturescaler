package com.evernight.texturescaler.core;

/**
 * Minimal logging abstraction so that the shared core has no dependency on any
 * Minecraft/loader logging stack (slf4j is not guaranteed on 1.12.2, and the
 * {@code com.mojang.logging} package only exists on 1.16+).
 *
 * <p>Messages use SLF4J-style {@code {}} placeholders.</p>
 */
public interface ScalerLog {

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    /** Discards everything. */
    ScalerLog NOOP = new ScalerLog() {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
    };
}
