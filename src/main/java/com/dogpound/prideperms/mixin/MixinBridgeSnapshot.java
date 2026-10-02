package com.dogpound.prideperms.mixin;

import java.util.Iterator;
import java.util.Map;

import com.dogpound.prideperms.bridge.Bridge;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pride Bridge, client side of the join: the server sends its full block/item/... lists, and a client missing any of
 * them disconnects ("Fatally missing registry entries"). Pride Lite drops the entries it doesn't have before applying
 * the lists — the server never sends it those IDs (it sends look-alikes instead). Only for lists from a server
 * (isLocalWorld = false); single-player world loading is untouched.
 */
@Mixin(value = GameData.class, remap = false)
public abstract class MixinBridgeSnapshot {
    @Inject(method = "injectSnapshot", at = @At("HEAD"), require = 0)
    private static void pride$skipUnknown(Map<ResourceLocation, ForgeRegistry.Snapshot> snapshot, boolean injectFrozenData,
                                          boolean isLocalWorld, CallbackInfoReturnable<?> cir) {
        if (isLocalWorld || snapshot == null || !FMLCommonHandler.instance().getSide().isClient()) return;
        int dropped = 0;
        for (Iterator<Map.Entry<ResourceLocation, ForgeRegistry.Snapshot>> it = snapshot.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ResourceLocation, ForgeRegistry.Snapshot> e = it.next();
            ForgeRegistry<?> reg = RegistryManager.ACTIVE.getRegistry(e.getKey());
            if (reg == null) { dropped += e.getValue().ids.size(); it.remove(); continue; }   // a whole list this pack doesn't have
            ForgeRegistry.Snapshot s = e.getValue();
            for (Iterator<ResourceLocation> k = s.ids.keySet().iterator(); k.hasNext(); )
                if (!reg.containsKey(k.next())) { k.remove(); dropped++; }
            s.dummied.removeIf(r -> !reg.containsKey(r));
            s.overrides.keySet().removeIf(r -> !reg.containsKey(r));
            s.aliases.keySet().removeIf(r -> !reg.containsKey(r));
        }
        Bridge.clientBridged = dropped > 0;
        if (dropped > 0) System.out.println("[PridePerms] Pride Bridge: this server has " + dropped + " things this pack doesn't — they'll show as look-alikes");
    }
}
