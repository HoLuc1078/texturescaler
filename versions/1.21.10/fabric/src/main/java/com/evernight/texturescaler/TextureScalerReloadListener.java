package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Resets the engine, then scans model {@code texture_size}s on the background executor.
 */
public class TextureScalerReloadListener implements IdentifiableResourceReloadListener {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TextureScalerFabric.MODID, "reload");

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }

    @Override
    public CompletableFuture<Void> reload(SharedState sharedState, Executor backgroundExecutor,
                                          PreparationBarrier barrier, Executor gameExecutor) {
        ScalerEngine engine = TextureScalerFabric.ENGINE;
        engine.onReloadStart();
        return CompletableFuture
                .runAsync(engine::scanModelUvConstraints, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenRunAsync(engine::onReloadEnd, gameExecutor);
    }
}
