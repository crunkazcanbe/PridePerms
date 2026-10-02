package com.dogpound.prideperms.mixin;

import com.dogpound.prideperms.bridge.Translate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pride Bridge: every packet to a Lite player goes through Translate (look-alikes for what its pack lacks). */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinBridgeSendPacket {
    @Shadow(remap = false) public EntityPlayerMP field_147369_b;   // player

    @Inject(method = "func_147359_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void pride$translate(Packet<?> packet, CallbackInfo ci) {
        if (Translate.BUSY.get() || packet == null) return;
        Packet<?> out = Translate.forPlayer(field_147369_b, packet);
        if (out == packet) return;
        ci.cancel();
        if (out == null) return;                                   // left out: the client couldn't read it
        Translate.BUSY.set(true);
        try { ((NetHandlerPlayServer) (Object) this).sendPacket(out); }
        finally { Translate.BUSY.set(false); }
    }
}
