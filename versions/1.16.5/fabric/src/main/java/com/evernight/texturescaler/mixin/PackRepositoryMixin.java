package com.evernight.texturescaler.mixin;

import com.evernight.texturescaler.LocalTextureScalingPack;
import com.evernight.texturescaler.TextureScalerFabric;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fabric has no "add pack finder" event (and 1.16.5 has no
 * {@code AddPackFindersEvent}/{@code PackRepository#addPackFinder}), so the overlay is
 * injected straight into the repository's discovered-pack map using the repository's own
 * {@code Pack.PackConstructor}.
 */
@Mixin(PackRepository.class)
public class PackRepositoryMixin {

    @Shadow @Final private Pack.PackConstructor constructor;

    @Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
    private void texturescaler$addOverlay(CallbackInfoReturnable<Map<String, Pack>> cir) {
        Pack pack = TextureScalerFabric.PACK.createPack(this.constructor);
        if (pack == null) {
            return;
        }
        Map<String, Pack> map = new LinkedHashMap<String, Pack>(cir.getReturnValue());
        map.put(pack.getId(), pack);
        cir.setReturnValue(map);
    }
}
