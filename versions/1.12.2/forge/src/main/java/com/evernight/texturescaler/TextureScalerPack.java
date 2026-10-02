package com.evernight.texturescaler;

import com.evernight.texturescaler.core.ScalerEngine;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 1.12.2 overlay resource pack.
 *
 * <p>This is the 1.12.2 counterpart of {@code common/TextureScalingPack}. It cannot be
 * shared because the resource-pack contract is completely different on this version:
 * 1.12.2 has {@link IResourcePack} (stream based, {@code getInputStream}) while 1.16+
 * has {@code PackResources} (supplier based, {@code getResource}/{@code listResources}).
 * It lives in the module so that {@code common/} stays untouched.</p>
 *
 * <p>How it works: {@code FallbackResourceManager.getResource} walks the pack list from
 * the <em>end</em> backwards, so the last pack added wins. This pack is appended to
 * {@code Minecraft.defaultResourcePacks} in preInit - after Forge has appended every
 * mod's resource pack - which makes it the highest priority pack of the default group
 * while user-selected resource packs and server packs (added after the defaults) still
 * override it. Every atlas sprite lookup therefore reaches
 * {@link ScalerEngine#getScaledResource} first, which returns the downscaled PNG for
 * oversized textures and {@code null} for everything else, letting the original pack
 * win unchanged.</p>
 *
 * <p>{@code resourceExists} and {@code getInputStream} must agree: the vanilla manager
 * probes {@code resourceExists} and then opens the stream. Both delegate to the same
 * memoising engine call, so they stay consistent within a reload.</p>
 */
@SideOnly(Side.CLIENT)
public final class TextureScalerPack implements IResourcePack {

    public static final String PACK_ID = "texturescaler_overlay";
    public static final String PACK_NAME = "Texture Scaler";

    /** Small generated icon; never read by the atlas. */
    private static final BufferedImage ICON = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);

    private final ScalerEngine engine;
    private final ForgePlatform platform;

    public TextureScalerPack(ScalerEngine engine, ForgePlatform platform) {
        this.engine = engine;
        this.platform = platform;
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        // While the platform reads an original, step aside so the lower packs win.
        if (platform.isReadingOriginal()) {
            return null;
        }
        byte[] png = engine.getScaledResource(location.getNamespace(), location.getPath());
        if (png == null) {
            return null;
        }
        return new ByteArrayInputStream(png);
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        if (platform.isReadingOriginal()) {
            return false;
        }
        return engine.getScaledResource(location.getNamespace(), location.getPath()) != null;
    }

    @Override
    public Set<String> getResourceDomains() {
        return engine.getNamespaces();
    }

    @Override
    @Nullable
    public <T extends IMetadataSection> T getPackMetadata(MetadataSerializer metadataSerializer, String metadataSectionName)
            throws IOException {
        // No pack.mcmeta: the pack is injected programmatically, never selected by the user.
        return null;
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        return ICON;
    }

    @Override
    public String getPackName() {
        return PACK_NAME;
    }
}
