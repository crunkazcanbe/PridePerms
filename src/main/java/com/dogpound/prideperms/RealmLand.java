package com.dogpound.prideperms;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "What kind of land is this player standing on?" for land-context rules (her ask: embed with the Pride Realms
 * mod). Asks Pride Realms by reflection so PridePerms runs without it: own (co-owner), trusted (builder+),
 * others (someone else's parcel), wild (no parcel). null when Pride Realms isn't installed.
 * ponytail: the player's own block, not the block they click (they're within reach of it); cached until they move.
 */
public final class RealmLand {
    private RealmLand() {}

    private static boolean tried;
    private static Method registryGet, at, level;
    private static final Map<UUID, Object[]> CACHE = new HashMap<>();     // uuid → {BlockPos, dim, answer}

    public static String at(EntityPlayerMP p) {
        if (!ready()) return null;
        BlockPos pos = new BlockPos(p);
        Object[] c = CACHE.get(p.getUniqueID());
        if (c != null && c[0].equals(pos) && (int) c[1] == p.dimension) return (String) c[2];
        String answer;
        try {
            Object reg = registryGet.invoke(null, p.world);
            Object parcel = at.invoke(reg, p.dimension, pos);
            if (parcel == null) answer = "wild";
            else {
                Object trust = level.invoke(null, p.world, p, parcel);
                String t = trust == null ? "" : trust.toString();
                answer = t.equals("COOWNER") ? "own" : t.equals("BUILDER") ? "trusted" : "others";
            }
        } catch (Throwable e) { answer = null; }
        CACHE.put(p.getUniqueID(), new Object[]{pos, p.dimension, answer});
        return answer;
    }

    public static void forget(UUID id) { CACHE.remove(id); }

    private static boolean ready() {
        if (tried) return registryGet != null;
        tried = true;
        try {
            Class<?> reg = Class.forName("com.dogpound.realmcoin.land.ParcelRegistry");
            Class<?> parcel = Class.forName("com.dogpound.realmcoin.land.Parcel");
            registryGet = reg.getMethod("get", net.minecraft.world.World.class);
            at = reg.getMethod("at", int.class, BlockPos.class);
            level = Class.forName("com.dogpound.realmcoin.land.LandPerms").getMethod("level", net.minecraft.world.World.class, net.minecraft.entity.player.EntityPlayer.class, parcel);
            PridePerms.LOG.info("Pride Realms found: land-context rules (own / trusted / claimed / others / wild) are on");
        } catch (Throwable e) { registryGet = null; }
        return registryGet != null;
    }
}
