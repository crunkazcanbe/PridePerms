package com.dogpound.prideperms.mixin;

import com.dogpound.prideperms.bridge.Translate;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.chunk.BlockStateContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pride Bridge: while a chunk packet is rebuilt for a Lite player, each section is measured and written as its
 * look-alike copy (same size check and same bytes, so the packet buffer always fits).
 */
@Mixin(BlockStateContainer.class)
public abstract class MixinBridgeSection {
    @Inject(method = "func_186009_b", at = @At("HEAD"), cancellable = true, remap = false)
    private void pride$write(PacketBuffer buf, CallbackInfo ci) {
        Translate.View v = Translate.CHUNK.get();
        BlockStateContainer self = (BlockStateContainer) (Object) this;
        if (v == null || Translate.OURS.contains(self)) return;
        BlockStateContainer t = Translate.translated(v, self);
        if (t == self) return;
        ci.cancel();
        t.write(buf);
    }

    @Inject(method = "func_186018_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void pride$size(CallbackInfoReturnable<Integer> cir) {
        Translate.View v = Translate.CHUNK.get();
        BlockStateContainer self = (BlockStateContainer) (Object) this;
        if (v == null || Translate.OURS.contains(self)) return;
        BlockStateContainer t = Translate.translated(v, self);
        if (t != self) cir.setReturnValue(t.getSerializedSize());
    }
}
