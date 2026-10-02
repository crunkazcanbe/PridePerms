package com.dogpound.prideperms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command button menu (her ask 2026-09-28: "all the commands as a button… a menu that pops up with every command
 * and you can just press a button"). The server sends only the commands THIS player may use (Forge + PridePerms
 * decide via checkPermission), each tagged with the mod it came from; the client draws the buttons and forms.
 */
public final class CmdMenu {
    private CmdMenu() {}

    private static final Map<String, String> MOD_BY_JAR = new HashMap<>();

    /** the command list as JSON: [{n:name, u:usage, m:mod, a:[aliases]}] */
    static String listFor(EntityPlayerMP p) {
        MinecraftServer s = p.getServer();
        JsonArray out = new JsonArray();
        Set<ICommand> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ICommand c : s.getCommandManager().getCommands().values()) {
            if (!seen.add(c)) continue;                                  // aliases point at the same command
            boolean ok;
            try { ok = c.checkPermission(s, p); } catch (Throwable t) { ok = false; }
            if (!ok) continue;
            JsonObject o = new JsonObject();
            o.addProperty("n", c.getName());
            String usage;
            try { usage = c.getUsage(p); } catch (Throwable t) { usage = "/" + c.getName(); }
            o.addProperty("u", usage == null ? "/" + c.getName() : usage);
            o.addProperty("m", modOf(c.getClass()));
            JsonArray al = new JsonArray();
            List<String> aliases = c.getAliases();
            if (aliases != null) for (String a : aliases) al.add(a);
            o.add("a", al);
            out.add(o);
        }
        return out.toString();
    }

    /** which mod a command class came from, by the jar it was loaded out of */
    static String modOf(Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null) return "Minecraft";
            String jar = jarName(cs.getLocation());
            if (jar == null) return "Minecraft";
            synchronized (MOD_BY_JAR) {
                if (MOD_BY_JAR.isEmpty())
                    for (ModContainer m : Loader.instance().getActiveModList()) {
                        File src = m.getSource();
                        if (src != null && src.isFile()) MOD_BY_JAR.putIfAbsent(src.getName(), m.getName());
                    }
                return MOD_BY_JAR.getOrDefault(jar, jar.contains("forge") || jar.contains("minecraft") ? "Minecraft" : jar.replaceAll("\\.jar$", ""));
            }
        } catch (Throwable t) { return "Other"; }
    }

    private static String jarName(URL u) {
        String path = u.getPath();
        int bang = path.indexOf("!/");
        if (bang >= 0) path = path.substring(0, bang);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) {}
        return name.endsWith(".jar") ? name : null;
    }

    /** /cmds — open the command menu (also on the menu key) */
    public static class Command extends CommandBase {
        @Override public String getName() { return "cmds"; }
        @Override public List<String> getAliases() { return java.util.Collections.singletonList("cmdmenu"); }
        @Override public String getUsage(ICommandSender s) { return "/cmds — a menu of every command you can use"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean checkPermission(MinecraftServer server, ICommandSender s) { return true; }
        @Override public void execute(MinecraftServer server, ICommandSender s, String[] a) throws net.minecraft.command.CommandException {
            Net.CH.sendTo(new Net.Cmds(listFor(getCommandSenderAsPlayer(s))), getCommandSenderAsPlayer(s));
        }
    }
}
