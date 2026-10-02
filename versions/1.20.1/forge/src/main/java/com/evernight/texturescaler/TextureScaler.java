package com.evernight.texturescaler;

import com.evernight.texturescaler.common.TextureScalingPack;
import com.evernight.texturescaler.core.ScalerEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Texture Scaler — client-side Forge 1.20.1 entry point.
 */
@Mod(TextureScaler.MODID)
public class TextureScaler {

    public static final String MODID = "texturescaler";

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ForgePlatform PLATFORM = new ForgePlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    private static final TextureScalingPack PACK =
            new TextureScalingPack(ENGINE, PLATFORM::isReadingOriginal, 15);

    public TextureScaler(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        modBus.addListener(this::clientSetup);
        modBus.addListener(this::registerReloadListeners);
        modBus.addListener(this::onAddPackFinders);
        modBus.addListener(this::onTextureStitchPost);
        modBus.addListener(this::onConfigChanged);

        context.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        LOGGER.info("[TextureScaler] loaded (client-side, Forge 1.20.1)");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Not resolved here: client setup may run before the window/GL backend exists, and
        // touching GL that early kills the JVM in native code. The enqueued task, the
        // first resource reload and every later reload resolve the cap instead.
        event.enqueueWork(ENGINE::updateCap);
    }

    private void registerReloadListeners(final RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new TextureScalerReloadListener());
    }

    private void onAddPackFinders(final AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            event.addRepositorySource(new RepositorySource() {
                @Override
                public void loadPacks(Consumer<Pack> packConsumer) {
                    packConsumer.accept(PACK.createPack());
                }
            });
        }
    }

    private void onTextureStitchPost(final TextureStitchEvent.Post event) {
        if (event.getAtlas().location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            Set<ResourceLocation> textures = event.getAtlas().getTextureLocations();
            Set<String> keys = new HashSet<>(textures.size() * 2);
            for (ResourceLocation rl : textures) {
                keys.add(rl.toString());
            }
            ENGINE.rememberBlockAtlasSprites(keys);
        }
    }

    private void onConfigChanged(final ModConfigEvent event) {
        PLATFORM.invalidateConfig();
        ENGINE.updateCap();
    }
}
