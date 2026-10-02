package com.dogpound.prideperms;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /perms verbose on [filter] — watch permission checks live in chat (like LuckPerms' verbose), at most 20 lines a second.
 * Also the change log: every /perms change goes to prideperms-log.txt in the world folder (/perms log shows the latest).
 */
final class Verbose {
    private static final Map<UUID, String> watchers = new ConcurrentHashMap<>();   // admin → filter ("" = everything)
    private static long second, sentThisSecond;

    private Verbose() {}

    static void on(UUID admin, String filter) { watchers.put(admin, filter == null ? "" : filter.toLowerCase()); }
    static void off(UUID admin) { watchers.remove(admin); }

    static void checked(UUID who, String node, Boolean result) {
        if (watchers.isEmpty()) return;
        MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (srv == null || !srv.isCallingFromMinecraftThread()) return;
        long now = System.currentTimeMillis() / 1000;
        if (now != second) { second = now; sentThisSecond = 0; }
        if (sentThisSecond >= 20) return;
        EntityPlayerMP p = srv.getPlayerList().getPlayerByUUID(who);
        String name = p == null ? who.toString().substring(0, 8) : p.getName();
        String line = "§8[verbose] §f" + name + " §7" + node + " → " + (result == null ? "§8not set" : result ? "§aallowed" : "§cdenied");
        for (Map.Entry<UUID, String> w : watchers.entrySet()) {
            if (!w.getValue().isEmpty() && !node.contains(w.getValue()) && !name.toLowerCase().contains(w.getValue())) continue;
            EntityPlayerMP admin = srv.getPlayerList().getPlayerByUUID(w.getKey());
            if (admin == null) { watchers.remove(w.getKey()); continue; }
            if (admin.getUniqueID().equals(who) && node.startsWith("command.perms")) continue;   // don't echo your own /perms
            admin.sendMessage(new TextComponentString(line));
            sentThisSecond++;
        }
    }

    // ------------------------------------------------------------------ change log
    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static void log(MinecraftServer srv, String who, String change) {
        try {
            File f = new File(srv.getEntityWorld().getSaveHandler().getWorldDirectory(), "prideperms-log.txt");
            String line = TIME.format(new Date()) + "  " + who + "  " + change + System.lineSeparator();
            Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            PridePerms.LOG.warn("couldn't write the change log: {}", e.toString());
        }
    }

    static List<String> lastLog(MinecraftServer srv, int n) {
        try {
            File f = new File(srv.getEntityWorld().getSaveHandler().getWorldDirectory(), "prideperms-log.txt");
            if (!f.isFile()) return Collections.emptyList();
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
