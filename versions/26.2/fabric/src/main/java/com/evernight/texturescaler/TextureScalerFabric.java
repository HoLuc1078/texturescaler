package com.evernight.texturescaler;

import com.evernight.texturescaler.common.TextureScalingPack;
import com.evernight.texturescaler.core.ScalerEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Texture Scaler — client-side Fabric entry point for 26.x.
 */
public class TextureScalerFabric implements ClientModInitializer {

    public static final String MODID = "texturescaler";

    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static final FabricPlatform PLATFORM = new FabricPlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);

    /**
     * 26.2 resource pack format ({@code pack_version.resource_major}).
     */
    public static final TextureScalingPack PACK =
            new TextureScalingPack(ENGINE, PLATFORM::isReadingOriginal, 88);

    private static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath(MODID, "reload");

    @Override
    public void onInitializeClient() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(RELOAD_ID, new TextureScalerReloadListener());
        // The scaling cap is resolved by TextureScalerReloadListener -> onReloadStart(),
        // i.e. on the first resource reload, after the render backend (and therefore the GL
        // context) exists. Querying it from a mod initializer runs inside the Minecraft
        // constructor, before the window is created, and kills the JVM in native code.
        LOGGER.info("[TextureScaler] loaded (client-side, Fabric 26.2)");
    }
}
