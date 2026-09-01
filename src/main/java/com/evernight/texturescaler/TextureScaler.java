package com.evernight.texturescaler;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Texture Scaler — a client-side Forge 1.20.1 mod.
 *
 * <p>Problem: some mods ship huge block/item textures (1024..4096 px). On GPUs whose
 * GL_MAX_TEXTURE_SIZE is only 16384 (many AMD cards / Intel iGPUs) the block atlas
 * cannot fit and the whole resource reload fails, which in turn prevents fonts
 * (including CJK glyphs) from loading — everything becomes tofu, then the game crashes.</p>
 *
 * <p>Fix: this mod registers a high-priority dynamic resource pack that intercepts
 * block-atlas textures whose largest edge exceeds the GPU-dependent cap and serves a
 * downscaled PNG instead. No mod files are modified.</p>
 */
@Mod(TextureScaler.MODID)
public class TextureScaler {

    public static final String MODID = "texturescaler";

    private static final Logger LOGGER = LogUtils.getLogger();

    public TextureScaler(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        // Query the GPU limit only after the GL context is ready.
        modBus.addListener(this::clientSetup);
        // Register our resource-reload listener (snapshot, caches, model UV scan).
        modBus.addListener(this::registerReloadListeners);
        // Add our overlay pack (fired on the mod bus during client construction).
        modBus.addListener(this::onAddPackFinders);
        // Learn the exact block-atlas sprite set after each stitch; used by the next reload.
        // (Forge 1.20.1 has no TextureStitchEvent.Pre — only Post.)
        modBus.addListener(this::onTextureStitchPost);

        context.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        LOGGER.info("[TextureScaler] loaded (client-side)");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // The GL context is already current on the render thread during client setup;
        // query directly, and re-query via enqueueWork as a safety net.
        // (updateCap logs the detected GL_MAX_TEXTURE_SIZE and the resulting cap once.)
        TextureScalingPack.updateCap();
        event.enqueueWork(TextureScalingPack::updateCap);
    }

    private void registerReloadListeners(final RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new TextureScalerReloadListener());
    }

    private void onAddPackFinders(final AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            event.addRepositorySource(new RepositorySource() {
                @Override
                public void loadPacks(Consumer<Pack> packConsumer) {
                    packConsumer.accept(TextureScalingPack.createPack());
                }
            });
        }
    }

    private void onTextureStitchPost(final TextureStitchEvent.Post event) {
        if (event.getAtlas().location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            TextureScalingPack.rememberBlockAtlasTextures(event.getAtlas().getTextureLocations());
        }
    }
}
