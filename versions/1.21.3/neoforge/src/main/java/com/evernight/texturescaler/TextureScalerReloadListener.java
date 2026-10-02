package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Resets the engine, then scans model {@code texture_size}s on the background executor.
 */
public class TextureScalerReloadListener implements PreparableReloadListener {

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        ScalerEngine engine = TextureScaler.ENGINE;
        engine.onReloadStart();
        return CompletableFuture
                .runAsync(engine::scanModelUvConstraints, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenRun(engine::onReloadEnd);
    }
}
