package com.dogpound.prideperms;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /rank list · /rank buy <group> — ranks for sale in Pride Realms coins (in-game money only).
 * A group is for sale when it has meta "price" (coins, e.g. 2500 or 24.99). Optional meta:
 *   duration = 30d  → a rental: the rank ends by itself (temporary group)
 *   requires = member → must already be in that group
 * Payment goes from the player's Pride Realms account to the server treasury. Without Pride Realms, nothing is for sale.
 */
public class RankShop extends CommandBase {
    @Override public String getName() { return "rank"; }
    @Override public String getUsage(ICommandSender s) { return "/rank list | /rank buy <rank>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public boolean checkPermission(MinecraftServer server, ICommandSender s) { return true; }

    private static void say(ICommandSender s, String m) { s.sendMessage(new TextComponentString(m)); }

    @Override
    public void execute(MinecraftServer server, ICommandSender s, String[] a) throws CommandException {
        PermStore st = PridePerms.store;
        if (st == null) throw new CommandException("No world loaded.");
        if (a.length == 0 || a[0].equals("list")) {
            List<String> forSale = forSale(st);
            if (forSale.isEmpty()) { say(s, "§7No ranks are for sale right now."); return; }
            say(s, "§d✦ Ranks for sale §7(/rank buy <name>)");
            for (String n : forSale) {
                PermStore.Group g = st.groups.get(n);
                String dur = g.meta.getOrDefault("duration", ""), req = g.meta.getOrDefault("requires", "");
                say(s, " " + g.prefix + n + " §f" + g.meta.get("price") + " coins" + (dur.isEmpty() ? "" : " §7for " + dur)
                        + (req.isEmpty() ? "" : " §7(needs " + req + ")"));
            }
            return;
        }
        if (!a[0].equals("buy") || a.length < 2) throw new WrongUsageException(getUsage(s));
        EntityPlayerMP p = getCommandSenderAsPlayer(s);
        PermStore.Group g = st.groups.get(a[1].toLowerCase());
        if (g == null || !g.meta.containsKey("price")) throw new CommandException("That rank isn't for sale. /rank list shows what is.");
        PermStore.Player pd = st.player(p.getUniqueID(), p.getName());
        if (pd.groups.contains(g.name)) throw new CommandException("You already have " + g.name + ".");
        String req = g.meta.getOrDefault("requires", "");
        if (!req.isEmpty() && !pd.groups.contains(req)) throw new CommandException("You need the " + req + " rank first.");
        long cents;
        try { cents = Math.round(Double.parseDouble(g.meta.get("price")) * 100); } catch (NumberFormatException e) { throw new CommandException("This rank's price is set wrong — tell an admin."); }
        String result = pay(p, cents, "Rank: " + g.name);
        if (!"OK".equals(result)) {
            throw new CommandException(result == null ? "Buying ranks needs Pride Realms (the money mod)."
                    : result.equals("INSUFFICIENT") ? "Not enough money in your account." : "The payment didn't go through (" + result + ").");
        }
        String dur = g.meta.getOrDefault("duration", "");
        long ms = dur.isEmpty() ? 0 : PermsCommand.duration(dur);
        if (ms > 0) pd.tempGroups.put(g.name, System.currentTimeMillis() + ms);
        else pd.groups.add(g.name);
        st.save();
        Verbose.log(server, p.getName(), "bought rank " + g.name + " for " + g.meta.get("price") + " coins" + (ms > 0 ? " (" + dur + ")" : ""));
        say(s, "§d✦ You bought " + g.prefix + g.name + "§d!" + (ms > 0 ? " §7It lasts " + dur + "." : ""));
    }

    static List<String> forSale(PermStore st) {
        List<String> out = new ArrayList<>();
        for (PermStore.Group g : st.groups.values()) if (g.meta.containsKey("price")) out.add(g.name);
        Collections.sort(out);
        return out;
    }

    /** Pride Realms Ledger.transfer(p:<uuid> → server:treasury) by reflection: "OK", a failure name, or null when it isn't installed */
    static String pay(EntityPlayerMP p, long cents, String memo) { return move(p, cents, memo, false); }

    /** the same, either way round: toPlayer = treasury → player (welcome coins), else player → treasury */
    static String move(EntityPlayerMP p, long cents, String memo, boolean toPlayer) {
        try {
            Class<?> ledger = Class.forName("com.dogpound.realmcoin.bank.Ledger");
            Object l = ledger.getMethod("get", World.class).invoke(null, p.getServer().getWorld(0));
            ledger.getMethod("personal", java.util.UUID.class, String.class, long.class).invoke(l, p.getUniqueID(), p.getName(), 0L);  // make sure the account exists
            String treasury = (String) ledger.getField("TREASURY").get(null);
            Method t = ledger.getMethod("transfer", String.class, String.class, long.class, String.class, String.class);
            String me = "p:" + p.getUniqueID();
            return String.valueOf(t.invoke(l, toPlayer ? treasury : me, toPlayer ? me : treasury, cents, toPlayer ? "welcome" : "rank", memo));
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable e) {
            PridePerms.LOG.warn("rank payment failed: {}", e.toString());
            return "ERROR";
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender s, String[] a, @Nullable BlockPos pos) {
        if (a.length == 1) return getListOfStringsMatchingLastWord(a, "list", "buy");
        if (a.length == 2 && a[0].equals("buy") && PridePerms.store != null) return getListOfStringsMatchingLastWord(a, forSale(PridePerms.store));
        return Collections.emptyList();
    }
}
