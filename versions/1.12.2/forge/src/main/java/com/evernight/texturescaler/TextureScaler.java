package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Texture Scaler - client-side Forge 1.12.2 entry point.
 *
 * <p>All the algorithm lives in the shared {@code com.evernight.texturescaler.core}
 * package; this class only wires it to the 1.12.2 (MCP stable_39) Forge API.</p>
 *
 * <p><b>Lifecycle note.</b> 1.12.2 performs exactly one automatic resource reload,
 * from {@code FMLClientHandler.beginMinecraftLoading} - after all mods are
 * <em>constructed</em> but before {@code preInit}. The overlay pack therefore has to be
 * registered from the mod constructor / {@code FMLConstructionEvent} rather than from
 * {@code preInit}, otherwise it would only take effect after a manual resource reload
 * (F3+T). For the same reason the config is loaded there too, since the eligibility test
 * for {@code textures/blocks} and {@code textures/items} depends on it.</p>
 */
@Mod(modid = TextureScaler.MODID,
        name = TextureScaler.NAME,
        version = TextureScaler.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        clientSideOnly = true)
@SideOnly(Side.CLIENT)
public class TextureScaler {

    public static final String MODID = "texturescaler";
    public static final String NAME = "Texture Scaler";
    public static final String VERSION = ModVersion.VERSION;

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    static final ForgePlatform PLATFORM = new ForgePlatform();
    public static final ScalerEngine ENGINE = new ScalerEngine(PLATFORM);
    private static final TextureScalerPack PACK = new TextureScalerPack(ENGINE, PLATFORM);

    private boolean bootstrapped;

    public TextureScaler() {
        // Runs during mod construction, i.e. before the automatic resource reload.
        bootstrap();
    }

    @Mod.EventHandler
    public void construct(FMLConstructionEvent event) {
        bootstrap();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            Config.load(event.getSuggestedConfigurationFile());
            PLATFORM.invalidateConfig();
        } catch (Throwable t) {
            LOGGER.error("[TextureScaler] config load failed", t);
        }
        bootstrap();
        ENGINE.updateCap();
        LOGGER.info("[TextureScaler] preInit done (client-side, Forge 1.12.2, cap {})", ENGINE.currentCap());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ENGINE.updateCap();
    }

    /**
     * Idempotent early setup. Safe to call from both the constructor and the
     * {@code FMLConstructionEvent} handler.
     */
    private void bootstrap() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (!bootstrapped) {
                Config.load(new File(new File(mc.gameDir, "config"), MODID + ".cfg"));
                PLATFORM.invalidateConfig();
                MinecraftForge.EVENT_BUS.register(this);
                bootstrapped = true;
            }
            registerPack();
            PLATFORM.refreshClaimedNamespaces();
            ENGINE.updateCap();
        } catch (Throwable t) {
            LOGGER.error("[TextureScaler] early bootstrap failed; texture scaling stays inactive", t);
        }
    }

    /**
     * Appends the overlay to {@code Minecraft.defaultResourcePacks} as the <em>last</em>
     * element. {@code FallbackResourceManager.getResource} iterates the pack list from the
     * end backwards, so the last pack wins; Forge appends every mod's own resource pack
     * during {@code FMLLoadEvent}, which happens before this runs. User-selected packs and
     * server packs are appended by {@code Minecraft.refreshResources} after the defaults,
     * so they still take precedence over us.
     */
    private void registerPack() {
        List<IResourcePack> packs = PLATFORM.defaultResourcePacks();
        if (packs == null) {
            LOGGER.warn("[TextureScaler] defaultResourcePacks unavailable, overlay pack not registered");
            return;
        }
        try {
            List<IResourcePack> kept = new ArrayList<IResourcePack>(packs.size() + 1);
            for (IResourcePack pack : packs) {
                if (pack != PACK) {
                    kept.add(pack);
                }
            }
            kept.add(PACK);
            packs.clear();
            packs.addAll(kept);
            LOGGER.info("[TextureScaler] overlay pack registered at priority {} of {} default packs",
                    packs.size(), packs.size());
        } catch (Throwable t) {
            LOGGER.error("[TextureScaler] could not register the overlay pack", t);
        }
    }

    @SubscribeEvent
    public void onTextureStitchPre(TextureStitchEvent.Pre event) {
        if (!isBlockAtlas(event)) {
            return;
        }
        PLATFORM.refreshClaimedNamespaces();
        ENGINE.updateCap();
        ENGINE.onReloadStart();
        ENGINE.setModelUvConstraints(PLATFORM.modelUvConstraints());
    }

    @SubscribeEvent
    public void onTextureStitchPost(TextureStitchEvent.Post event) {
        if (!isBlockAtlas(event)) {
            return;
        }
        ENGINE.onReloadEnd();
        // Authoritative namespace set for the next reload.
        PLATFORM.refreshClaimedNamespaces();
    }

    private static boolean isBlockAtlas(TextureStitchEvent event) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && event.getMap() == mc.getTextureMapBlocks();
        } catch (Throwable t) {
            return false;
        }
    }
}
