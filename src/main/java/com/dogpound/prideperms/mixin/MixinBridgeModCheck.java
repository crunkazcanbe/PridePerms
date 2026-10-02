package com.dogpound.prideperms.mixin;

import java.util.HashMap;
import java.util.Map;

import com.dogpound.prideperms.bridge.Bridge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Pride Bridge, server side of the join: Forge rejects a client that lacks any of the server's mods. A client that has
 * PridePerms (Pride Lite) is checked as if it had every mod it's missing, at the server's own version — so a mod it
 * DOES have at a wrong version is still rejected, but merely missing mods are fine (they're translated for it).
 */
@Mixin(value = FMLNetworkHandler.class, remap = false)
public abstract class MixinBridgeModCheck {
    @ModifyVariable(method = "checkModList(Ljava/util/Map;Lnet/minecraftforge/fml/relauncher/Side;)Ljava/lang/String;",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static Map<String, String> pride$liteMayJoin(Map<String, String> remote, Map<String, String> remote2, Side side) {
        if (side != Side.CLIENT || remote == null || !remote.containsKey(Bridge.MARK)) return remote;
        Map<String, String> filled = new HashMap<>(remote);
        int added = 0;
        for (ModContainer m : Loader.instance().getActiveModList())
            if (filled.putIfAbsent(m.getModId(), m.getVersion()) == null) added++;
        if (added > 0) System.out.println("[PridePerms] Pride Bridge: letting a Lite client in (" + added + " server mods it doesn't have will be shown as look-alikes)");
        return filled;
    }
}
