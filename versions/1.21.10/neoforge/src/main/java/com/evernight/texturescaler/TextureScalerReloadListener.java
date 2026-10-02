package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Resets the engine, then scans model {@code texture_size}s on the background executor.
 */
public class TextureScalerReloadListener implements PreparableReloadListener {

    @Override
    public CompletableFuture<Void> reload(SharedState sharedState, Executor backgroundExecutor,
                                          PreparationBarrier barrier, Executor gameExecutor) {
        ScalerEngine engine = TextureScaler.ENGINE;
        engine.onReloadStart();
        return CompletableFuture
                .runAsync(engine::scanModelUvConstraints, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenRunAsync(engine::onReloadEnd, gameExecutor);
    }
}
