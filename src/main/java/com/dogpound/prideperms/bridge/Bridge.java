package com.dogpound.prideperms.bridge;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

/**
 * Pride Bridge (her ask 2026-10-02): Pride Lite and Pride Heavy players on the SAME server.
 * A Lite client is any client with PridePerms that lacks some of the server's mods. The server lets it in, the client
 * skips registry entries it doesn't have, and everything from a missing mod is shown to that player as a vanilla
 * look-alike (see Translate).
 */
public final class Bridge {
    /** the mod that marks a bridge-capable client */
    public static final String MARK = "prideperms";

    /** client side: true while connected to a server whose lists had entries this pack lacks */
    public static volatile boolean clientBridged;

    /** the mods a player's client reported at login (empty if unknown) */
    public static Set<String> clientMods(EntityPlayerMP p) {
        try {
            NetworkDispatcher d = NetworkDispatcher.get(p.connection.getNetworkManager());
            Map<String, String> m = d == null ? null : d.getModList();
            return m == null ? Collections.emptySet() : new HashSet<>(m.keySet());
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    private Bridge() {}
}
