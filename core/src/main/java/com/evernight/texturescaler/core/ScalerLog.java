package com.evernight.texturescaler.core;

/**
 * Minimal logging abstraction so that the shared core has no dependency on any
 * Minecraft/loader logging stack (the {@code com.mojang.logging} package is not
 * reachable from a plain Java 8 source set).
 */
public interface ScalerLog {

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    /**
     * Discards everything.
     */
    ScalerLog NOOP = new ScalerLog() {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
    };
}
