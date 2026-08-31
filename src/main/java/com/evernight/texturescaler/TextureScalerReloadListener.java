package com.evernight.texturescaler;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Resource-reload listener:
 * <ul>
 *   <li>snapshots the source packs and resets in-memory caches (cheap, runs synchronously
 *       when the reload is constructed),</li>
 *   <li>recomputes the GPU cap,</li>
 *   <li>scans model {@code texture_size}s on the background executor.</li>
 * </ul>
 */
public class TextureScalerReloadListener implements PreparableReloadListener {

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                          ProfilerFiller profiler, ProfilerFiller gameProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        TextureScalingPack.onReloadStart(resourceManager);
        return CompletableFuture
                .runAsync(() -> {
                    Map<String, Integer> sizes = ModelSizeScanner.scan(resourceManager);
                    TextureScalingPack.setModelSizes(sizes);
                }, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenRun(() -> {
                });
    }
}
