package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Texture Scaler - client-side Forge 1.18.2 entry point.
 */
@Mod(TextureScaler.MODID)
public class TextureScaler {

    public static final String MODID = "texturescaler";

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ForgePlatform PLATFORM = new ForgePlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    private static final OverlayPack PACK =
            new OverlayPack(ENGINE, PLATFORM::isReadingOriginal, 8);

    public TextureScaler() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        modBus.addListener(this::clientSetup);
        modBus.addListener(this::registerReloadListeners);
        modBus.addListener(this::onAddPackFinders);
        modBus.addListener(this::onConfigChanged);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        LOGGER.info("[TextureScaler] loaded (client-side, Forge 1.18.2)");
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
                public void loadPacks(Consumer<Pack> packConsumer, Pack.PackConstructor constructor) {
                    Pack pack = PACK.createPack();
                    if (pack != null) {
                        packConsumer.accept(pack);
                    }
                }
            });
        }
    }

    private void onConfigChanged(final ModConfigEvent event) {
        PLATFORM.invalidateConfig();
        ENGINE.updateCap();
    }
}
