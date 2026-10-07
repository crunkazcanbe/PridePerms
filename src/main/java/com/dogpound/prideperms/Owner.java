package com.dogpound.prideperms;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.Arrays;

/**
 * The server owner (requested feature). Accounts listed by UUID in config ownerUuids get the top "owner" group (every node, above admin), op level 4
 * on dedicated servers, and are never held by the join gate. UUID, not name, so a renamed account can't claim it.
 */
public final class Owner {
    private Owner() {}

    public static boolean isOwner(java.util.UUID id) {
        String s = id.toString(), flat = s.replace("-", "");
        return Arrays.stream(PermsConfig.ownerUuids).map(String::trim)
                .anyMatch(u -> u.equalsIgnoreCase(s) || u.replace("-", "").equalsIgnoreCase(flat));
    }

    /** the owner group exists in every store: priority above admin, every permission */
    static void ensureGroup(PermStore s) {
        PermStore.Group g = s.groups.get("owner");
        if (g == null) {
            g = s.group("owner", 1000, "§d§l");
            if (s.groups.containsKey("admin")) g.parents.add("admin");
        }
        if (!Boolean.TRUE.equals(g.nodes.get("*"))) { g.nodes.put("*", true); s.changed(); }
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.player instanceof EntityPlayerMP) || !isOwner(e.player.getUniqueID())) return;
        PermStore s = PridePerms.store;
        if (s != null) {
            ensureGroup(s);
            PermStore.Player p = s.player(e.player.getUniqueID(), e.player.getName());
            if (!p.groups.contains("owner")) { p.groups.add("owner"); s.changed(); s.save(); }
        }
        MinecraftServer server = e.player.getServer();
        if (server != null && server.isDedicatedServer()) {
            GameProfile gp = e.player.getGameProfile();
            if (server.getPlayerList().getOppedPlayers().getPermissionLevel(gp) < 4) server.getPlayerList().addOp(gp);
        }
    }
}
