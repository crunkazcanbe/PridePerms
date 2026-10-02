package com.dogpound.prideperms;

import com.mojang.authlib.GameProfile;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * /perms groups
 * /perms group <g> create [priority] | delete | info | set <node> <true|false> | unset <node>
 *                  | parent add|remove <group> | prefix <text> | priority <n>
 * /perms user <player> info | add <group> | remove <group> | set <node> <true|false> | unset <node>
 * /perms check <player> <node>     /perms nodes [search]     /perms reload
 */
public class PermsCommand extends CommandBase {
    @Override public String getName() { return "perms"; }
    @Override public List<String> getAliases() { return Collections.singletonList("pp"); }
    @Override public String getUsage(ICommandSender s) { return "/perms menu | groups | tracks | promote|demote <player> <track> | verbose on|off | log | group <g> ... | user <player> ... | check <player> <node> | nodes [search] | import luckperms|pex <file> | reload"; }
    @Override public int getRequiredPermissionLevel() { return 3; }          // or command.perms = true for a group

    private static void say(ICommandSender s, String msg) { s.sendMessage(new TextComponentString(msg)); }

    @Override
    public void execute(MinecraftServer server, ICommandSender s, String[] a) throws CommandException {
        PermStore st = PridePerms.store;
        if (st == null) throw new CommandException("No world loaded.");
        if (a.length == 0) throw new WrongUsageException(getUsage(s));
        switch (a[0]) {
            case "groups": {
                for (String n : st.groupNames()) {
                    PermStore.Group g = st.groups.get(n);
                    say(s, "§d" + g.prefix + n + " §7priority " + g.priority + (g.parents.isEmpty() ? "" : " · inherits " + String.join(", ", g.parents))
                            + " · " + g.nodes.size() + " rules");
                }
                return;
            }
            case "group": group(st, s, a); audit(server, s, a); return;
            case "user": user(server, st, s, a); audit(server, s, a); return;
            case "tracks": {
                if (st.tracks.isEmpty()) say(s, "§7No tracks yet. /perms track <name> set <group1> <group2> ...");
                for (Map.Entry<String, List<String>> t : st.tracks.entrySet()) say(s, "§d" + t.getKey() + " §7" + String.join(" → ", t.getValue()));
                return;
            }
            case "track": {
                if (a.length < 3) throw new WrongUsageException("/perms track <name> set <group1> <group2> ... | delete");
                String tn = a[1].toLowerCase();
                if (a[2].equals("delete")) { st.tracks.remove(tn); say(s, "§dDeleted track " + tn); }
                else if ((a[2].equals("up") || a[2].equals("down") || a[2].equals("add") || a[2].equals("remove")) && a.length > 3) {
                    List<String> steps = st.tracks.get(tn);
                    if (steps == null) throw new CommandException("No track called " + tn);
                    String gname = a[3].toLowerCase();
                    int i = steps.indexOf(gname);
                    if (a[2].equals("add")) { if (!st.groups.containsKey(gname)) throw new CommandException("No group called " + gname); if (i < 0) steps.add(gname); }
                    else if (a[2].equals("remove")) { if (steps.size() <= 2) throw new CommandException("A track needs at least two groups."); steps.remove(gname); }
                    else if (i >= 0) { int j = a[2].equals("up") ? i + 1 : i - 1; if (j >= 0 && j < steps.size()) java.util.Collections.swap(steps, i, j); }
                    say(s, "§dTrack " + tn + ": §f" + String.join(" → ", steps));
                }
                else if (a[2].equals("set")) {
                    List<String> steps = new java.util.ArrayList<>();
                    for (int i = 3; i < a.length; i++) { if (!st.groups.containsKey(a[i])) throw new CommandException("No group called " + a[i]); steps.add(a[i]); }
                    if (steps.size() < 2) throw new CommandException("A track needs at least two groups, lowest first.");
                    st.tracks.put(tn, steps);
                    say(s, "§dTrack " + tn + ": §f" + String.join(" → ", steps));
                } else throw new WrongUsageException("/perms track <name> set <group1> <group2> ... | delete");
                st.save(); audit(server, s, a);
                return;
            }
            case "promote": case "demote": {
                if (a.length < 3) throw new WrongUsageException("/perms " + a[0] + " <player> <track>");
                List<String> steps = st.tracks.get(a[2].toLowerCase());
                if (steps == null) throw new CommandException("No track called " + a[2] + ". /perms tracks lists them.");
                GameProfile gp = profile(server, s, a[1]);
                PermStore.Player p = st.player(gp.getId(), gp.getName());
                int at = -1;
                for (int i = steps.size() - 1; i >= 0; i--) if (p.groups.contains(steps.get(i))) { at = i; break; }
                int to = a[0].equals("promote") ? at + 1 : at - 1;
                if (to >= steps.size()) throw new CommandException(gp.getName() + " is already at the top of " + a[2]);
                if (a[0].equals("demote") && at < 0) throw new CommandException(gp.getName() + " isn't on the " + a[2] + " track");
                p.groups.removeAll(steps);
                if (to >= 0) p.groups.add(steps.get(to));
                st.save(); audit(server, s, a);
                say(s, "§d" + gp.getName() + (a[0].equals("promote") ? " promoted to §f" : " demoted to §f") + (to >= 0 ? steps.get(to) : "default"));
                return;
            }
            case "verbose": {
                if (!(s instanceof net.minecraft.entity.player.EntityPlayerMP)) throw new CommandException("Only a player can watch.");
                java.util.UUID me = ((net.minecraft.entity.player.EntityPlayerMP) s).getUniqueID();
                if (a.length > 1 && a[1].equals("off")) { Verbose.off(me); say(s, "§dVerbose off."); }
                else { Verbose.on(me, a.length > 2 ? a[2] : ""); say(s, "§dVerbose on" + (a.length > 2 ? " for §f" + a[2] : "") + "§d — every permission check shows here. /perms verbose off to stop."); }
                return;
            }
            case "preset": {                                                   // ready-made ranks; only adds what's missing
                String[][] ranks = {
                    {"owner", "200", "&4[★Owner] &c", "admin", "*"},
                    {"admin", "100", "&c[Admin] ", "moderator", "*"},
                    {"moderator", "50", "&9[Mod] ", "helper", "command.kick command.mute prideguard.alerts prideprism.admin"},
                    {"helper", "30", "&b[Helper] ", "vip", "prideguard.alerts"},
                    {"vip", "20", "&6[VIP] ", "member", "prideperms.chat.color"},
                    {"member", "10", "&a", "default", "prideperms.chat.color"},
                };
                int added = 0;
                for (String[] r : ranks) {
                    if (st.groups.containsKey(r[0])) continue;
                    PermStore.Group g = st.group(r[0], Integer.parseInt(r[1]), r[2].replace('&', '§'));
                    if (!r[3].isEmpty() && st.groups.containsKey(r[3])) g.parents.add(r[3]);
                    for (String n : r[4].split(" ")) if (!n.isEmpty()) g.nodes.put(n, true);
                    added++;
                }
                for (String[] r : ranks) {                                    // fix parents when a parent was created after its child
                    PermStore.Group g = st.groups.get(r[0]);
                    if (g != null && g.parents.isEmpty() && !r[3].isEmpty() && st.groups.containsKey(r[3])) g.parents.add(r[3]);
                }
                st.save(); audit(server, s, a);
                say(s, "§dAdded " + added + " ready-made ranks §7(owner, admin, moderator, helper, vip, member). Existing ones were left alone. /perms groups lists them.");
                return;
            }
            case "log": {
                int n = a.length > 1 ? parseInt(a[1], 1, 200) : 15;
                List<String> lines = Verbose.lastLog(server, n);
                say(s, "§d" + (lines.isEmpty() ? "No changes logged yet." : "Last " + lines.size() + " changes:"));
                for (String l : lines) say(s, "§7" + l);
                return;
            }
            case "check": {
                if (a.length < 3) throw new WrongUsageException("/perms check <player> <node>");
                GameProfile gp = profile(server, s, a[1]);
                Boolean d = PridePerms.decide(gp.getId(), a[2]);
                say(s, "§d" + gp.getName() + " §f" + a[2] + " → " + (d == null ? "§7not set (the default applies)" : d ? "§aALLOWED" : "§cDENIED")
                        + " §7" + why(st, gp, a[2]));
                return;
            }
            case "nodes": {
                List<String> found = Known.search(a.length > 1 ? a[1] : "");
                say(s, "§d" + found.size() + " nodes seen so far" + (a.length > 1 ? " matching " + a[1] : "") + (found.size() > 40 ? " (first 40)" : "") + ":");
                for (String n : found.subList(0, Math.min(40, found.size()))) say(s, "§7 " + n);
                return;
            }
            case "menu": case "studio": {
                if (!(s instanceof net.minecraft.entity.player.EntityPlayerMP)) throw new CommandException("Only a player can open the menu.");
                Net.sendRules((net.minecraft.entity.player.EntityPlayerMP) s, true, a.length > 2 ? a[1].toLowerCase() + ":" + a[2] : a.length > 1 ? a[1].toLowerCase() : "");
                return;
            }
            case "config": {                        // /perms config <setting> <value…>  (lists: lines split by " ;; ")
                if (a.length < 2) { for (Map.Entry<String, String> e : Studio.config().entrySet()) say(s, "§d" + e.getKey() + " §7= §f" + e.getValue()); return; }
                try { say(s, "§d" + Studio.setConfig(a[1], a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "")); }
                catch (IllegalArgumentException ex) { throw new CommandException(ex.getMessage()); }
                audit(server, s, a);
                return;
            }
            case "backup": {
                try { say(s, "§a✦ Saved a backup: §f" + Studio.backup(server, a.length > 1 ? a[1] : "manual")); }
                catch (IllegalArgumentException ex) { throw new CommandException(ex.getMessage()); }
                audit(server, s, a);
                return;
            }
            case "backups": {
                List<String> b = Studio.backups(server);
                say(s, "§d" + b.size() + " backups" + (b.isEmpty() ? "" : " (newest first):"));
                for (String n : b.subList(0, Math.min(20, b.size()))) say(s, "§7 " + n);
                return;
            }
            case "restore": {
                if (a.length < 2) throw new WrongUsageException("/perms restore <backup name>  (/perms backups lists them)");
                try { say(s, "§a✦ " + Studio.restore(server, a[1])); }
                catch (IllegalArgumentException ex) { throw new CommandException(ex.getMessage()); }
                audit(server, s, a);
                return;
            }
            case "import": {                        // /perms import luckperms|pex <file>
                if (a.length < 3) throw new WrongUsageException("/perms import luckperms <export.json[.gz]> | /perms import pex <permissions.yml>  (world folder, config/ or server folder)");
                String name = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                java.io.File world = server.getEntityWorld().getSaveHandler().getWorldDirectory();
                java.io.File f = new java.io.File(world, name);
                if (!f.isFile()) f = new java.io.File("config", name);
                if (!f.isFile()) f = new java.io.File(name);
                try { say(s, "§a✦ " + Importer.run(PridePerms.store, a[1].toLowerCase(), f)); }
                catch (Exception ex) { throw new CommandException("Import failed: " + ex.getMessage()); }
                return;
            }
            case "reload": {
                PridePerms.store = PermStore.load(new java.io.File(server.getEntityWorld().getSaveHandler().getWorldDirectory(), "prideperms.json"));
                say(s, "§dReloaded prideperms.json.");
                return;
            }
            default: throw new WrongUsageException(getUsage(s));
        }
    }

    private void group(PermStore st, ICommandSender s, String[] a) throws CommandException {
        if (a.length < 3) throw new WrongUsageException("/perms group <g> create|delete|info|set|unset|parent|prefix|priority ...");
        String name = a[1].toLowerCase();
        if (a[2].equals("create")) {
            if (st.groups.containsKey(name)) throw new CommandException("Group " + name + " already exists.");
            st.group(name, a.length > 3 ? parseInt(a[3]) : 5, "§f");
            st.save();
            say(s, "§dCreated group " + name + ".");
            return;
        }
        PermStore.Group g = st.groups.get(name);
        if (g == null) throw new CommandException("No group called " + name + ". /perms groups lists them.");
        switch (a[2]) {
            case "clone": case "rename": {
                if (a.length < 4) throw new WrongUsageException("/perms group <g> " + a[2] + " <new name>");
                String to = a[3].toLowerCase().replaceAll("[^a-z0-9_-]", "");
                if (to.isEmpty() || st.groups.containsKey(to)) throw new CommandException("Pick a new, unused name.");
                if (a[2].equals("rename") && name.equals(PermStore.DEFAULT)) throw new CommandException("The default group can't be renamed.");
                if (a[2].equals("clone")) st.cloneGroup(name, to); else st.renameGroup(name, to);
                say(s, "§d" + (a[2].equals("clone") ? "Copied " + name + " to §f" : "Renamed " + name + " to §f") + to);
                break;
            }
            case "delete":
                if (name.equals(PermStore.DEFAULT)) throw new CommandException("The default group can't be deleted.");
                st.groups.remove(name);
                for (PermStore.Player p : st.players.values()) p.groups.remove(name);
                for (PermStore.Group o : st.groups.values()) o.parents.remove(name);
                say(s, "§dDeleted " + name + ".");
                break;
            case "info":
                say(s, "§d" + g.prefix + name + g.suffix + " §7priority " + g.priority + " · inherits " + (g.parents.isEmpty() ? "nothing" : String.join(", ", g.parents)));
                for (Map.Entry<String, Boolean> e : g.nodes.entrySet()) say(s, (e.getValue() ? "§a + " : "§c - ") + e.getKey());
                for (PermStore.Rule r : g.timed) say(s, (r.value ? "§a + " : "§c - ") + r.node + " §7" + describe(r));
                for (Map.Entry<String, String> e : g.meta.entrySet()) say(s, "§b ◆ " + e.getKey() + " §7= §f" + e.getValue());
                return;
            case "set":
                if (a.length < 5) throw new WrongUsageException("/perms group <g> set <node> <true|false> [1h30m] [dim=<id>]");
                say(s, "§d" + name + ": " + setRule(g.nodes, g.timed, a[3], parseBoolean(a[4]), a, 5));
                break;
            case "unset":
                if (a.length < 4) throw new WrongUsageException("/perms group <g> unset <node>");
                boolean had = g.nodes.remove(a[3]) != null | g.timed.removeIf(r -> r.node.equals(a[3]));
                say(s, had ? "§d" + name + ": removed " + a[3] : "§7" + name + " had no rule for " + a[3]);
                break;
            case "suffix":
                g.suffix = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)).replace('&', '§') : "";
                say(s, "§d" + name + " suffix: example" + g.suffix);
                break;
            case "meta":
                meta(g.meta, s, name, a);
                break;
            case "parent":
                if (a.length < 5) throw new WrongUsageException("/perms group <g> parent add|remove <group>");
                if (a[3].equals("add")) {
                    if (!st.groups.containsKey(a[4])) throw new CommandException("No group called " + a[4]);
                    if (a[4].equals(name)) throw new CommandException("A group can't inherit itself.");
                    if (!g.parents.contains(a[4])) g.parents.add(a[4]);
                } else g.parents.remove(a[4]);
                say(s, "§d" + name + " inherits " + (g.parents.isEmpty() ? "nothing" : String.join(", ", g.parents)));
                break;
            case "prefix":
                g.prefix = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)).replace('&', '§') : "";
                say(s, "§d" + name + " prefix: " + g.prefix + "example");
                break;
            case "priority":
                if (a.length < 4) throw new WrongUsageException("/perms group <g> priority <number>");
                g.priority = parseInt(a[3]);
                say(s, "§d" + name + " priority " + g.priority);
                break;
            default: throw new WrongUsageException("/perms group <g> create|delete|info|set|unset|parent|prefix|priority ...");
        }
        st.save();
    }

    private void user(MinecraftServer server, PermStore st, ICommandSender s, String[] a) throws CommandException {
        if (a.length < 3) throw new WrongUsageException("/perms user <player> info|add|remove|set|unset ...");
        GameProfile gp = profile(server, s, a[1]);
        PermStore.Player p = st.player(gp.getId(), gp.getName());
        switch (a[2]) {
            case "info": {
                StringBuilder gs = new StringBuilder();
                for (PermStore.Group g : st.groupsOf(p, PermsConfig.opsAreAdmin && PridePerms.isOp(gp.getId())))
                    gs.append(gs.length() == 0 ? "" : ", ").append(g.prefix).append(g.name).append("§7");
                say(s, "§d" + gp.getName() + " §7groups: " + gs);
                for (Map.Entry<String, Long> e : p.tempGroups.entrySet()) say(s, "§b ⏱ " + e.getKey() + " §7for " + left(e.getValue()));
                for (Map.Entry<String, Boolean> e : p.nodes.entrySet()) say(s, (e.getValue() ? "§a + " : "§c - ") + e.getKey());
                for (PermStore.Rule r : p.timed) say(s, (r.value ? "§a + " : "§c - ") + r.node + " §7" + describe(r));
                for (Map.Entry<String, String> e : p.meta.entrySet()) say(s, "§b ◆ " + e.getKey() + " §7= §f" + e.getValue());
                return;
            }
            case "add":
                if (a.length < 4 || !st.groups.containsKey(a[3])) throw new CommandException("Which group? /perms groups lists them.");
                if (!p.groups.contains(a[3])) p.groups.add(a[3]);
                say(s, "§d" + gp.getName() + " is now in " + a[3]);
                break;
            case "remove":
                if (a.length < 4) throw new WrongUsageException("/perms user <player> remove <group>");
                p.groups.remove(a[3]);
                say(s, "§d" + gp.getName() + " left " + a[3]);
                break;
            case "set":
                if (a.length < 5) throw new WrongUsageException("/perms user <player> set <node> <true|false> [1h30m] [dim=<id>]");
                say(s, "§d" + gp.getName() + ": " + setRule(p.nodes, p.timed, a[3], parseBoolean(a[4]), a, 5));
                break;
            case "unset":
                if (a.length < 4) throw new WrongUsageException("/perms user <player> unset <node>");
                p.nodes.remove(a[3]);
                p.timed.removeIf(r -> r.node.equals(a[3]));
                say(s, "§d" + gp.getName() + ": removed " + a[3]);
                break;
            case "addtemp": {
                if (a.length < 5 || !st.groups.containsKey(a[3])) throw new WrongUsageException("/perms user <player> addtemp <group> <time, e.g. 7d or 2h30m>");
                long ms = duration(a[4]);
                if (ms <= 0) throw new CommandException("Time like 30m, 2h, 7d or 1d12h");
                p.tempGroups.put(a[3], System.currentTimeMillis() + ms);
                say(s, "§d" + gp.getName() + " is in " + a[3] + " for §f" + a[4]);
                break;
            }
            case "prefix": case "suffix": {
                String v = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)).replace('&', '§') : "";
                if (a[2].equals("prefix")) p.prefix = v; else p.suffix = v;
                say(s, "§d" + gp.getName() + " " + a[2] + ": " + (v.isEmpty() ? "§7(from their group)" : v));
                break;
            }
            case "meta":
                meta(p.meta, s, gp.getName(), a);
                break;
            default: throw new WrongUsageException("/perms user <player> info|add|remove|set|unset ...");
        }
        st.save();
    }

    /** a plain rule, or a timed / per-dimension one when extra words follow: "1h30m", "7d", "dim=-1" */
    private static String setRule(Map<String, Boolean> plain, List<PermStore.Rule> timed, String node, boolean value, String[] a, int from) throws CommandException {
        long until = 0; int dim = PermStore.ANY_DIM; String land = null;
        for (int i = from; i < a.length; i++) {
            String w = a[i].toLowerCase();
            if (w.startsWith("land=")) { land = w.substring(5); if (!java.util.Arrays.asList("own", "trusted", "claimed", "others", "wild").contains(land)) throw new CommandException("land= must be own, trusted, claimed, others or wild"); }
            else if (w.startsWith("dim=")) dim = parseInt(w.substring(4));
            else if (w.equals("in") && i + 1 < a.length) dim = parseInt(a[++i]);
            else if (w.equals("for")) continue;
            else { long ms = duration(w); if (ms <= 0) throw new CommandException("Didn't understand '" + a[i] + "'. Use a time like 30m, 2h, 7d, dim=<number> or land=own|trusted|claimed|others|wild."); until = System.currentTimeMillis() + ms; }
        }
        final String landF = land;
        timed.removeIf(r -> r.node.equals(node) && java.util.Objects.equals(r.land, landF));
        String how = (value ? "§aallow " : "§cdeny ") + "§f" + node;
        if (until == 0 && dim == PermStore.ANY_DIM && land == null) { plain.put(node, value); return how; }
        PermStore.Rule r = new PermStore.Rule();
        r.node = node; r.value = value; r.until = until; r.dim = dim; r.land = land;
        timed.add(r);
        return how + " §7" + describe(r);
    }

    private static void meta(Map<String, String> meta, ICommandSender s, String who, String[] a) throws CommandException {
        if (a.length >= 6 && a[3].equals("set")) { meta.put(a[4], String.join(" ", Arrays.copyOfRange(a, 5, a.length))); say(s, "§d" + who + ": " + a[4] + " = " + meta.get(a[4])); }
        else if (a.length >= 5 && a[3].equals("unset")) { meta.remove(a[4]); say(s, "§d" + who + ": removed " + a[4]); }
        else throw new WrongUsageException("... meta set <key> <value> | meta unset <key>");
    }

    /** "1d12h30m15s" → milliseconds (0 = didn't understand) */
    static long duration(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*([wdhms])").matcher(t.toLowerCase());
        long ms = 0; int end = 0;
        while (m.find()) {
            if (m.start() != end) return 0;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) { case "w": ms += n * 604_800_000L; break; case "d": ms += n * 86_400_000L; break; case "h": ms += n * 3_600_000L; break; case "m": ms += n * 60_000L; break; default: ms += n * 1000L; }
            end = m.end();
        }
        return end == t.length() ? ms : 0;
    }

    private static String left(long until) {
        long s = Math.max(0, (until - System.currentTimeMillis()) / 1000);
        return s >= 86400 ? s / 86400 + "d " + s % 86400 / 3600 + "h" : s >= 3600 ? s / 3600 + "h " + s % 3600 / 60 + "m" : s / 60 + "m " + s % 60 + "s";
    }

    private static String describe(PermStore.Rule r) {
        String out = r.until == 0 ? "" : "(" + left(r.until) + " left)";
        if (r.dim != PermStore.ANY_DIM) out += (out.isEmpty() ? "" : " ") + "(only in dimension " + r.dim + ")";
        if (r.land != null) out += (out.isEmpty() ? "" : " ") + "(only on " + (r.land.equals("own") ? "their own land" : r.land.equals("trusted") ? "land they're trusted on" : r.land.equals("claimed") ? "claimed land" : r.land.equals("others") ? "other people's land" : "wilderness") + ")";
        return out;
    }

    private static void audit(MinecraftServer server, ICommandSender s, String[] a) {
        if (a.length > 2 && a[2].equals("info")) return;
        Verbose.log(server, s.getName(), "/perms " + String.join(" ", a));
    }

    /** which rule decided it, in plain words */
    private static String why(PermStore st, GameProfile gp, String node) {
        PermStore.Player p = st.players.get(gp.getId().toString());
        if (p != null && PermStore.match(p.nodes, node) != null) return "(their own rule)";
        for (PermStore.Group g : st.groupsOf(p, PermsConfig.opsAreAdmin && PridePerms.isOp(gp.getId())))
            if (st.groupDecides(g, node, new java.util.HashSet<>()) != null) return "(group " + g.name + ")";
        return "";
    }

    private static GameProfile profile(MinecraftServer server, ICommandSender s, String name) throws CommandException {
        if (name.startsWith("@")) return getPlayer(server, s, name).getGameProfile();     // @p, @a[...] etc.
        GameProfile gp = server.getPlayerProfileCache().getGameProfileForUsername(name);
        if (gp == null) throw new CommandException("Never seen a player called " + name + ".");
        return gp;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender s, String[] a, @Nullable BlockPos pos) {
        PermStore st = PridePerms.store;
        if (a.length == 1) return getListOfStringsMatchingLastWord(a, "menu", "groups", "group", "user", "check", "nodes", "reload", "tracks", "track", "promote", "demote", "verbose", "log", "preset", "import", "config", "backup", "backups", "restore");
        if (st == null) return Collections.emptyList();
        if (a.length == 2 && a[0].equals("group")) return getListOfStringsMatchingLastWord(a, st.groupNames());
        if (a.length == 2 && (a[0].equals("user") || a[0].equals("check"))) return getListOfStringsMatchingLastWord(a, server.getOnlinePlayerNames());
        if (a.length == 3 && a[0].equals("group")) return getListOfStringsMatchingLastWord(a, "create", "delete", "info", "set", "unset", "parent", "prefix", "suffix", "priority", "meta", "clone", "rename");
        if (a.length == 3 && a[0].equals("user")) return getListOfStringsMatchingLastWord(a, "info", "add", "remove", "addtemp", "set", "unset", "prefix", "suffix", "meta");
        if (a.length == 4 && a[0].equals("user") && (a[2].equals("add") || a[2].equals("remove"))) return getListOfStringsMatchingLastWord(a, st.groupNames());
        if (a.length == 4 && a[2].equals("parent")) return getListOfStringsMatchingLastWord(a, "add", "remove");
        if (a.length == 5 && a[2].equals("parent")) return getListOfStringsMatchingLastWord(a, st.groupNames());
        if ((a.length == 4 && (a[2].equals("set") || a[2].equals("unset"))) || (a.length == 3 && a[0].equals("check")))
            return getListOfStringsMatchingLastWord(a, new ArrayList<>(Known.search(a[a.length - 1])).subList(0, Math.min(200, Known.search(a[a.length - 1]).size())));
        if (a.length == 5 && a[2].equals("set")) return getListOfStringsMatchingLastWord(a, "true", "false");
        return Collections.emptyList();
    }
}
