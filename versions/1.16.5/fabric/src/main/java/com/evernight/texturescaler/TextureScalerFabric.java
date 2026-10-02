package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Texture Scaler — client-side Fabric 1.16.5 entry point. */
public class TextureScalerFabric implements ClientModInitializer {

    public static final String MODID = "texturescaler";

    // Minecraft 1.16.5 still logs through log4j2 (the slf4j switch came in 1.17).
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    /** The client resource pack format used by Minecraft 1.16.2 – 1.16.5. */
    private static final int PACK_FORMAT = 5;

    public static final FabricPlatform PLATFORM = new FabricPlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    public static final LocalTextureScalingPack PACK =
            new LocalTextureScalingPack(ENGINE, PLATFORM::isReadingOriginal, PACK_FORMAT);

    @Override
    public void onInitializeClient() {
        // ResourceManagerHelper.get() takes vanilla's PackType (Fabric API 0.42 remaps
        // net.minecraft.class_3264 straight to it).
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new TextureScalerReloadListener());
        // The scaling cap is resolved by TextureScalerReloadListener -> onReloadStart(),
        // i.e. on the first resource reload, after the render backend (and therefore the GL
        // context) exists. Querying it from a mod initializer runs inside the Minecraft
        // constructor, before the window is created, and kills the JVM in native code.
        LOGGER.info("[TextureScaler] loaded (client-side, Fabric 1.16.5)");
    }
}
