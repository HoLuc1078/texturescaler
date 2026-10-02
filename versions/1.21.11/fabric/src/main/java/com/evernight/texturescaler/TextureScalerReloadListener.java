package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Resets the engine, then scans model {@code texture_size}s on the background executor.
 */
public class TextureScalerReloadListener implements IdentifiableResourceReloadListener {

    private static final Identifier ID =
            Identifier.fromNamespaceAndPath(TextureScalerFabric.MODID, "reload");

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public CompletableFuture<Void> reload(SharedState sharedState, Executor backgroundExecutor,
                                          PreparationBarrier barrier, Executor gameExecutor) {
        ScalerEngine engine = TextureScalerFabric.ENGINE;
        // Runs on the render thread (reload() is invoked synchronously): the GL query in
        // onReloadStart() must not be moved onto the background executor.
        engine.onReloadStart();
        return CompletableFuture
                .runAsync(engine::scanModelUvConstraints, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenRunAsync(engine::onReloadEnd, gameExecutor);
    }
}
