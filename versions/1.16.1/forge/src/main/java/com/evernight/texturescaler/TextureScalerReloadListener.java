package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.minecraft.profiler.IProfiler;
import net.minecraft.resources.IFutureReloadListener;
import net.minecraft.resources.IResourceManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Resets the engine, then scans model {@code texture_size}s on the background executor.
 */
public class TextureScalerReloadListener implements IFutureReloadListener {

    @Override
    public CompletableFuture<Void> reload(IStage stage, IResourceManager resourceManager,
                                          IProfiler profiler, IProfiler gameProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        ScalerEngine engine = TextureScaler.ENGINE;
        engine.onReloadStart();
        return CompletableFuture
                .runAsync(engine::scanModelUvConstraints, backgroundExecutor)
                .thenCompose(stage::wait)
                .thenRun(engine::onReloadEnd);
    }
}
