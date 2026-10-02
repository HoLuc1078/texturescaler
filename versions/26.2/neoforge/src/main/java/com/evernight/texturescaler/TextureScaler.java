package com.evernight.texturescaler;

import com.evernight.texturescaler.common.TextureScalingPack;
import com.evernight.texturescaler.core.ScalerEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Texture Scaler — client-side NeoForge entry point for 26.x.
 */
@Mod(value = TextureScaler.MODID, dist = Dist.CLIENT)
public class TextureScaler {

    public static final String MODID = "texturescaler";

    private static final Logger LOGGER = LogUtils.getLogger();

    static final NeoForgePlatform PLATFORM = new NeoForgePlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    /**
     * 26.2 resource pack format ({@code pack_version.resource_major}).
     */
    private static final TextureScalingPack PACK =
            new TextureScalingPack(ENGINE, PLATFORM::isReadingOriginal, 88);

    public TextureScaler(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::addClientReloadListeners);
        modBus.addListener(this::onAddPackFinders);
        modBus.addListener(this::onConfigChanged);

        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        LOGGER.info("[TextureScaler] loaded (client-side, NeoForge 26.2)");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Not resolved here: client setup may run before the window/GL backend exists, and
        // touching GL that early kills the JVM in native code. The enqueued task, the
        // first resource reload and every later reload resolve the cap instead.
        event.enqueueWork(ENGINE::updateCap);
    }

    private void addClientReloadListeners(final AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(MODID, "reload"),
                new TextureScalerReloadListener());
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

    private void onConfigChanged(final ModConfigEvent event) {
        PLATFORM.invalidateConfig();
        ENGINE.updateCap();
    }
}
