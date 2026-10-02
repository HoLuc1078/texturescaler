package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class TextureScalerReloadListener implements IdentifiableResourceReloadListener {

    private static final ResourceLocation ID =
            new ResourceLocation(TextureScalerFabric.MODID, "reload");

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                          ProfilerFiller profiler, ProfilerFiller gameProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        ScalerEngine engine = TextureScalerFabric.ENGINE;
        engine.onReloadStart();
        return CompletableFuture
                .runAsync(engine::scanModelUvConstraints, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenRun(engine::onReloadEnd);
    }
}