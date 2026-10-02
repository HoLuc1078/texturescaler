package com.evernight.texturescaler.mixin;

import com.evernight.texturescaler.TextureScalerFabric;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fabric has no "add pack finder" event, so the overlay is injected straight into the
 * repository's available-pack map. Because the profile is declared {@code required} it is
 * always selected, and {@code Pack.Position.TOP} puts it above every other pack.
 */
@Mixin(PackRepository.class)
public class PackRepositoryMixin {

    @Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
    private void texturescaler$addOverlay(CallbackInfoReturnable<Map<String, Pack>> cir) {
        Pack pack = TextureScalerFabric.PACK.createPack();
        if (pack == null) {
            return;
        }
        Map<String, Pack> map = new LinkedHashMap<>(cir.getReturnValue());
        map.put(pack.getId(), pack);
        cir.setReturnValue(map);
    }
}