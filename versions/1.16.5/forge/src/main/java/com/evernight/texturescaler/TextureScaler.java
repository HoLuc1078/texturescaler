package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.IPackFinder;
import net.minecraft.resources.IPackNameDecorator;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResourceManager;
import net.minecraft.resources.ResourcePackInfo;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

/**
 * Texture Scaler — client-side Forge 1.16.5 entry point.
 *
 * <p>All of the algorithm lives in the shared {@code com.evernight.texturescaler.core} module;
 * this class only wires it to the 1.16.5 Forge client.</p>
 *
 * <p>Forge 1.16.5 has neither {@code AddPackFindersEvent} nor
 * {@code RegisterClientReloadListenersEvent} (both arrived in later versions), so the overlay
 * pack is injected into the {@code ResourcePackList} through {@code addPackFinder} and the
 * reload listener is registered straight on the client {@code IReloadableResourceManager}
 * during {@link FMLClientSetupEvent} — which Forge fires before the first resource reload.</p>
 */
@Mod(TextureScaler.MODID)
public class TextureScaler {

    public static final String MODID = "texturescaler";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    /** The client resource pack format used by Minecraft 1.16.2 – 1.16.5. */
    private static final int PACK_FORMAT = 5;

    static final ForgePlatform PLATFORM = new ForgePlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    public static final LocalTextureScalingPack PACK =
            new LocalTextureScalingPack(ENGINE, PLATFORM::isReadingOriginal, PACK_FORMAT);

    public TextureScaler() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::onConfigChanged);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        LOGGER.info("[TextureScaler] loaded (client-side, Forge 1.16.5, {} mods present)",
                ModList.get() == null ? 0 : ModList.get().size());
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            LOGGER.warn("[TextureScaler] no Minecraft instance during client setup, overlay not registered");
            return;
        }

        IResourceManager rm = mc.getResourceManager();
        if (rm instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) rm).registerReloadListener(new TextureScalerReloadListener());
        } else {
            LOGGER.warn("[TextureScaler] unexpected resource manager {}, reload listener not registered",
                    rm == null ? "null" : rm.getClass().getName());
        }

        if (mc.getResourcePackRepository() != null) {
            mc.getResourcePackRepository().addPackFinder(new IPackFinder() {
                @Override
                public void loadPacks(Consumer<ResourcePackInfo> consumer, ResourcePackInfo.IFactory factory) {
                    ResourcePackInfo info = ResourcePackInfo.create(
                            LocalTextureScalingPack.PACK_ID,
                            true,
                            () -> PACK,
                            factory,
                            ResourcePackInfo.Priority.TOP,
                            IPackNameDecorator.BUILT_IN);
                    if (info != null) {
                        consumer.accept(info);
                    }
                }
            });
        }

        // The GPU limit cannot be queried yet: client setup runs before the window/GL
        // backend exists, and touching GL that early kills the JVM in native code. The
        // first resource reload resolves the cap and logs the detected value.
        LOGGER.info("[TextureScaler] overlay pack registered");
    }

    private void onConfigChanged(final ModConfig.ModConfigEvent event) {
        PLATFORM.invalidateConfig();
        ENGINE.updateCap();
    }
}
