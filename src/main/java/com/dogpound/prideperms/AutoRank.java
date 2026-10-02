package com.dogpound.prideperms;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.StatList;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;
import java.util.Map;

/**
 * Playtime rank-ups (like PowerRanks): on any track, a group with meta "hours" = N is where players go once they've
 * played N hours — checked once a minute for everyone online. Only ever promotes along the track, never skips back.
 *   /perms track members set default member regular veteran
 *   /perms group regular meta set hours 5
 *   /perms group veteran meta set hours 50
 */
public final class AutoRank {
    private static int tick;

    private AutoRank() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++tick % 1200 != 0) return;
        PermStore st = PridePerms.store;
        MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (st == null || srv == null || st.tracks.isEmpty()) return;
        boolean changed = false;
        for (EntityPlayerMP p : srv.getPlayerList().getPlayers()) {
            double hours = p.getStatFile().readStat(StatList.PLAY_ONE_MINUTE) / 72000.0;   // the stat counts ticks
            PermStore.Player pd = st.player(p.getUniqueID(), p.getName());
            for (Map.Entry<String, List<String>> tr : st.tracks.entrySet()) {
                List<String> steps = tr.getValue();
                int at = -1;
                for (int i = steps.size() - 1; i >= 0; i--) if (pd.groups.contains(steps.get(i)) || (i == 0 && steps.get(0).equals(PermStore.DEFAULT))) { at = i; break; }
                int to = at;
                for (int i = at + 1; i < steps.size(); i++) {
                    PermStore.Group g = st.groups.get(steps.get(i));
                    String need = g == null ? "" : g.meta.getOrDefault("hours", "");
                    if (need.isEmpty()) break;                                 // a step without hours = manual only; stop here
                    try { if (hours >= Double.parseDouble(need)) to = i; else break; } catch (NumberFormatException ex) { break; }
                }
                if (to > at && to >= 0) {
                    pd.groups.removeAll(steps);
                    if (!steps.get(to).equals(PermStore.DEFAULT)) pd.groups.add(steps.get(to));
                    changed = true;
                    PermStore.Group g = st.groups.get(steps.get(to));
                    p.sendMessage(new TextComponentString("§d✦ Rank up! §fYou're now " + (g == null ? steps.get(to) : g.prefix + g.name) + " §7(" + String.format("%.1f", hours) + " hours played)"));
                    Verbose.log(srv, "auto-rank", p.getName() + " → " + steps.get(to) + " on track " + tr.getKey() + " at " + String.format("%.1f", hours) + "h");
                }
            }
        }
        if (changed) st.save();
    }
}
