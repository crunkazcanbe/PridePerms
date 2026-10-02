package com.dogpound.prideperms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Server side of the Permissions Studio (client/GuiStudio): everything the pages show besides the rules themselves
 * (settings, join gate, change log, who's online, backups), plus /perms config and backup/restore.
 */
public final class Studio {
    private Studio() {}

    // ------------------------------------------------------------------ settings (every public static field of PermsConfig)
    static Map<String, String> config() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : PermsConfig.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object v = f.get(null);
                out.put(f.getName(), v instanceof String[] ? String.join(" ;; ", (String[]) v) : String.valueOf(v));
            } catch (IllegalAccessException ignored) {}
        }
        return out;
    }

    /** set one setting; lists are lines joined with " ;; " */
    static String setConfig(String key, String value) {
        Field f;
        try { f = PermsConfig.class.getField(key); } catch (NoSuchFieldException e) { throw new IllegalArgumentException("No setting called " + key + ". /perms config lists them."); }
        try {
            Class<?> t = f.getType();
            if (t == boolean.class) f.setBoolean(null, value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equals("1"));
            else if (t == int.class) f.setInt(null, Integer.parseInt(value.trim()));
            else if (t == double.class) f.setDouble(null, Double.parseDouble(value.trim()));
            else if (t == String[].class) {
                List<String> lines = new ArrayList<>();
                for (String l : value.split("\\s*;;\\s*")) if (!l.trim().isEmpty()) lines.add(l.trim());
                f.set(null, lines.toArray(new String[0]));
            } else f.set(null, value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " needs a number.");
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Can't change " + key);
        }
        ConfigManager.sync(PridePerms.MODID, Config.Type.INSTANCE);
        WebHook.dirty = true;
        return key + " = " + config().get(key) + (key.equals("forgeHandler") ? " (takes effect after a restart)" : "");
    }

    // ------------------------------------------------------------------ backups: <world>/prideperms-backups/*.json
    private static File dir(MinecraftServer srv) { return new File(srv.getEntityWorld().getSaveHandler().getWorldDirectory(), "prideperms-backups"); }

    private static File live(MinecraftServer srv) { return new File(srv.getEntityWorld().getSaveHandler().getWorldDirectory(), "prideperms.json"); }

    static String backup(MinecraftServer srv, String note) {
        PridePerms.store.save();
        File d = dir(srv);
        d.mkdirs();
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-" + note.replaceAll("[^A-Za-z0-9_-]", "") + ".json";
        try { Files.copy(live(srv).toPath(), new File(d, name).toPath(), StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception e) { throw new IllegalArgumentException("Backup failed: " + e.getMessage()); }
        List<String> all = backups(srv);                                 // keep the newest 40
        for (int i = 40; i < all.size(); i++) new File(d, all.get(i)).delete();
        return name;
    }

    static List<String> backups(MinecraftServer srv) {
        String[] n = dir(srv).list((x, f) -> f.endsWith(".json"));
        List<String> out = n == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(n));
        out.sort(Collections.reverseOrder());
        return out;
    }

    static String restore(MinecraftServer srv, String name) {
        File b = new File(dir(srv), new File(name).getName());
        if (!b.isFile()) throw new IllegalArgumentException("No backup called " + name + ". /perms backups lists them.");
        String safety = backup(srv, "before-restore");
        try { Files.copy(b.toPath(), live(srv).toPath(), StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception e) { throw new IllegalArgumentException("Restore failed: " + e.getMessage()); }
        PridePerms.store = PermStore.load(live(srv));
        return "Restored " + b.getName() + " (the rules from just before are saved as " + safety + ")";
    }

    // ------------------------------------------------------------------ what the Studio pages show (sent with the rules)
    static JsonObject info(EntityPlayerMP viewer) {
        MinecraftServer srv = viewer.getServer();
        JsonObject o = new JsonObject();
        JsonObject cfg = new JsonObject();
        for (Map.Entry<String, String> e : config().entrySet()) cfg.addProperty(e.getKey(), e.getValue());
        o.add("config", cfg);
        o.addProperty("handler", PridePerms.handlerIsOurs());

        JsonObject gate = new JsonObject();
        JsonArray verified = new JsonArray();
        for (Map.Entry<String, Gate.Linked> e : Gate.verified().entrySet()) {
            JsonObject v = new JsonObject();
            v.addProperty("uuid", e.getKey());
            v.addProperty("name", e.getValue().name);
            v.addProperty("account", e.getValue().account);
            v.addProperty("at", e.getValue().at);
            verified.add(v);
        }
        gate.add("verified", verified);
        gate.addProperty("codes", Gate.codesWaiting());
        JsonArray locked = new JsonArray();
        for (String n : Gate.lockedNames(srv)) locked.add(n);
        gate.add("locked", locked);
        gate.addProperty("spot", Gate.spotSet());
        o.add("gate", gate);

        JsonArray online = new JsonArray();
        for (EntityPlayerMP p : srv.getPlayerList().getPlayers()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.getName());
            j.addProperty("uuid", p.getUniqueID().toString());
            j.addProperty("op", PridePerms.isOp(p.getUniqueID()));
            online.add(j);
        }
        o.add("online", online);

        JsonArray log = new JsonArray();
        List<String> lines = Verbose.lastLog(srv, 80);
        for (int i = lines.size() - 1; i >= 0; i--) log.add(lines.get(i));   // newest first
        o.add("log", log);

        JsonArray bk = new JsonArray();
        for (String n : backups(srv)) bk.add(n);
        o.add("backups", bk);

        JsonArray nodes = new JsonArray();
        List<String> known = Known.search("");
        for (String n : known.subList(0, Math.min(4000, known.size()))) nodes.add(n);
        o.add("nodes", nodes);
        return o;
    }
}
