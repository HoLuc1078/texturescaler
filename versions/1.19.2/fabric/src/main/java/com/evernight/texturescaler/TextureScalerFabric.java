package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Texture Scaler - client-side Fabric 1.19.2 entry point. */
public class TextureScalerFabric implements ClientModInitializer {

    public static final String MODID = "texturescaler";

    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static final FabricPlatform PLATFORM = new FabricPlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    public static final OverlayPack PACK =
            new OverlayPack(ENGINE, PLATFORM::isReadingOriginal, 9);

    @Override
    public void onInitializeClient() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new TextureScalerReloadListener());
        // The scaling cap is resolved by TextureScalerReloadListener -> onReloadStart(),
        // i.e. on the first resource reload, after the render backend (and therefore the GL
        // context) exists. Querying it from a mod initializer runs inside the Minecraft
        // constructor, before the window is created, and kills the JVM in native code.
        LOGGER.info("[TextureScaler] loaded (client-side, Fabric 1.18.2)");
    }
}