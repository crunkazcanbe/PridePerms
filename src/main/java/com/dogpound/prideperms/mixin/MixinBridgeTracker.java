package com.dogpound.prideperms.mixin;

import com.dogpound.prideperms.bridge.Translate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pride Bridge: a Lite player isn't sent mobs/objects from mods its pack doesn't have (its client couldn't spawn them). */
@Mixin(EntityTrackerEntry.class)
public abstract class MixinBridgeTracker {
    @Shadow(remap = false) private Entity field_73132_a;   // trackedEntity

    @Inject(method = "func_180233_c", at = @At("RETURN"), cancellable = true, remap = false)
    private void pride$hide(EntityPlayerMP p, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && Translate.hides(p, field_73132_a)) cir.setReturnValue(false);
    }
}
