package com.dogpound.prideperms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

/**
 * Website / phone-app bridge for PridePerms — the standard in ~/mc-mods/research/WEB-HOOKS.md (read that first).
 * OUT (read-only, every 5 min and after changes): <world>/pride-web/prideperms/ranks.json + players.json.
 * IN: <world>/pride-web/inbox/prideperms/*.json = {"body":"<json text>","signed":"<hex HMAC-SHA256 of body>"}; body =
 *   {"id":"unique","ts":<epoch ms>,"action":"…","by":"<admin uuid>","player":"<uuid>","args":{…}}.
 *   The site signs the exact body text with the secret in config/pride-web.cfg, so no JSON re-formatting can break it.
 *   Actions: user.group.add {group, duration?} · user.group.remove {group} · user.node.set {node, value true|false|unset}
 *   · group.node.set {group, node, value} · group.meta.set {group, key, value} — all need `by` = someone allowed /perms;
 *   rank.buy {group} — the player buys a for-sale rank with IN-GAME coins (must be online; same checks as /rank buy).
 *   verify.code {name|uuid, code, account, minutes?} — the join gate: a one-time code the player types with /verify
 *   (no `by` needed — the signature is the site's word). verify.revoke {name|uuid} — unlink a closed site account.
 *   OUT also: prideperms/verified.json {gateOn, verified:[{uuid,name,account,verifiedAt}]}.
 *   Answers go to <world>/pride-web/outbox/prideperms/<id>.json {ok, message}; handled requests move to …/done/.
 */
public final class WebHook {
    private WebHook() {}

    static volatile boolean dirty = true;
    private static int tick;
    private static String secret;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || PridePerms.store == null) return;
        tick++;
        if (tick % 20 == 0) inbox();
        if (tick % 6000 == 0 || (dirty && tick % 20 == 0)) { dirty = false; snapshot(); }
    }

    static File web() {
        MinecraftServer s = FMLCommonHandler.instance().getMinecraftServerInstance();
        return new File(s.getEntityWorld().getSaveHandler().getWorldDirectory(), "pride-web");
    }

    // ------------------------------------------------------------------ OUT
    static void snapshot() {
        try {
            PermStore st = PridePerms.store;
            JsonObject ranks = new JsonObject();
            JsonArray groups = new JsonArray();
            for (PermStore.Group g : st.groups.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", g.name);
                o.addProperty("priority", g.priority);
                o.addProperty("prefix", g.prefix);
                o.addProperty("suffix", g.suffix);
                JsonArray par = new JsonArray();
                for (String p : g.parents) par.add(p);
                o.add("parents", par);
                if (g.meta.containsKey("price")) {
                    JsonObject sale = new JsonObject();
                    sale.addProperty("price", g.meta.get("price"));
                    sale.addProperty("duration", g.meta.getOrDefault("duration", ""));
                    sale.addProperty("requires", g.meta.getOrDefault("requires", ""));
                    o.add("forSale", sale);
                }
                groups.add(o);
            }
            ranks.add("groups", groups);
            ranks.add("tracks", new JsonParser().parse(new com.google.gson.Gson().toJson(st.tracks)));
            JsonArray players = new JsonArray();
            for (Map.Entry<String, PermStore.Player> e : st.players.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.getKey());
                o.addProperty("name", e.getValue().name);
                JsonArray gs = new JsonArray();
                for (String g : e.getValue().groups) gs.add(g);
                o.add("groups", gs);
                JsonObject temp = new JsonObject();
                for (Map.Entry<String, Long> t : e.getValue().tempGroups.entrySet()) temp.addProperty(t.getKey(), t.getValue());
                o.add("tempGroups", temp);
                players.add(o);
            }
            JsonObject pl = new JsonObject();
            pl.add("players", players);
            write(new File(web(), "prideperms/ranks.json"), envelope(ranks));
            write(new File(web(), "prideperms/players.json"), envelope(pl));
            JsonArray linked = new JsonArray();                               // who is verified + their site account (no IPs)
            for (Map.Entry<String, Gate.Linked> e : Gate.verified().entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.getKey());
                o.addProperty("name", e.getValue().name);
                o.addProperty("account", e.getValue().account);
                o.addProperty("verifiedAt", e.getValue().at);
                linked.add(o);
            }
            JsonObject vf = new JsonObject();
            vf.addProperty("gateOn", PermsConfig.gateEnabled);
            vf.add("verified", linked);
            write(new File(web(), "prideperms/verified.json"), envelope(vf));
        } catch (Exception ex) { PridePerms.LOG.warn("web snapshot failed: " + ex); }
    }

    private static String envelope(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("mod", PridePerms.MODID);
        o.addProperty("version", PridePerms.VERSION);
        o.addProperty("written", System.currentTimeMillis());
        o.add("data", data);
        return o.toString();
    }

    /** write to .tmp, then move over, so the site never reads half a file */
    static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ IN
    static void inbox() {
        File dir = new File(web(), "inbox/prideperms");
        File[] reqs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (reqs == null) return;
        for (File f : reqs) {
            String id = f.getName().replace(".json", ""), answer;
            boolean ok = false;
            try {
                JsonObject wrap = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
                String body = wrap.get("body").getAsString();
                if (!MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.UTF_8), wrap.get("signed").getAsString().toLowerCase().getBytes(StandardCharsets.UTF_8)))
                    throw new IllegalArgumentException("bad signature");
                JsonObject b = new JsonParser().parse(body).getAsJsonObject();
                id = b.get("id").getAsString().replaceAll("[^A-Za-z0-9_-]", "");
                long ts = b.get("ts").getAsLong();
                if (Math.abs(System.currentTimeMillis() - ts) > 600_000) throw new IllegalArgumentException("too old (or clock wrong)");
                if (new File(dir, "done/" + id + ".json").exists()) throw new IllegalArgumentException("already done");
                answer = act(b);
                ok = true;
            } catch (Exception ex) { answer = String.valueOf(ex.getMessage()); }
            try {
                JsonObject out = new JsonObject();
                out.addProperty("ok", ok);
                out.addProperty("message", answer);
                write(new File(web(), "outbox/prideperms/" + id + ".json"), out.toString());
                File done = new File(dir, "done/" + id + ".json");
                done.getParentFile().mkdirs();
                Files.move(f.toPath(), done.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) { f.delete(); }
        }
    }

    /** carry out one checked request; returns the message for the site */
    static String act(JsonObject b) throws Exception {
        MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
        PermStore st = PridePerms.store;
        String action = b.get("action").getAsString();
        JsonObject a = b.has("args") ? b.getAsJsonObject("args") : new JsonObject();
        UUID player = b.has("player") ? UUID.fromString(b.get("player").getAsString()) : null;
        if (action.equals("rank.buy")) {
            EntityPlayerMP p = player == null ? null : srv.getPlayerList().getPlayerByUUID(player);
            if (p == null) throw new IllegalArgumentException("the player must be online to pay with in-game coins");
            int r = srv.getCommandManager().executeCommand(p, "rank buy " + a.get("group").getAsString());
            return r > 0 ? "bought" : "the game refused (see the player's chat)";
        }
        if (action.equals("verify.code")) return Gate.siteCode(a);         // the site itself is the authority here
        if (action.equals("verify.revoke")) return Gate.siteRevoke(a);
        UUID by = UUID.fromString(b.get("by").getAsString());
        if (!admin(srv, by)) throw new IllegalArgumentException("'by' isn't allowed to change permissions");
        String who = nameOf(srv, by), result;
        switch (action) {
            case "user.group.add": {
                PermStore.Player p = st.player(player, nameOf(srv, player));
                String g = group(st, a);
                long ms = a.has("duration") ? PermsCommand.duration(a.get("duration").getAsString()) : 0;
                if (ms > 0) p.tempGroups.put(g, System.currentTimeMillis() + ms); else if (!p.groups.contains(g)) p.groups.add(g);
                result = "added " + g + (ms > 0 ? " for " + a.get("duration").getAsString() : "");
                break;
            }
            case "user.group.remove": {
                PermStore.Player p = st.player(player, nameOf(srv, player));
                String g = group(st, a);
                p.groups.remove(g); p.tempGroups.remove(g);
                result = "removed " + g;
                break;
            }
            case "user.node.set": result = setNode(st.player(player, nameOf(srv, player)).nodes, a); break;
            case "group.node.set": result = setNode(st.groups.get(group(st, a)).nodes, a); break;
            case "group.meta.set": {
                PermStore.Group g = st.groups.get(group(st, a));
                String k = a.get("key").getAsString(), v = a.has("value") ? a.get("value").getAsString() : "";
                if (v.isEmpty()) g.meta.remove(k); else g.meta.put(k, v);
                result = "meta " + k + " = " + v;
                break;
            }
            default: throw new IllegalArgumentException("unknown action " + action);
        }
        st.save();
        Verbose.log(srv, who + " (website)", action + " " + (player == null ? "" : nameOf(srv, player) + " ") + a);
        return result;
    }

    private static String group(PermStore st, JsonObject a) {
        String g = a.get("group").getAsString().toLowerCase();
        if (!st.groups.containsKey(g)) throw new IllegalArgumentException("no group " + g);
        return g;
    }

    private static String setNode(Map<String, Boolean> nodes, JsonObject a) {
        String node = a.get("node").getAsString(), v = a.get("value").getAsString();
        if (v.equals("unset")) nodes.remove(node); else nodes.put(node, Boolean.parseBoolean(v));
        return node + " = " + v;
    }

    /** may this person change permissions? a rule granting command.perms, or op level 3+ */
    static boolean admin(MinecraftServer srv, UUID id) {
        Boolean d = PridePerms.decideCommand(id, "command.perms");
        if (d != null) return d;
        GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(id);
        return gp != null && srv.getPlayerList().getOppedPlayers().getPermissionLevel(gp) >= 3;
    }

    private static String nameOf(MinecraftServer srv, UUID id) {
        GameProfile gp = id == null ? null : srv.getPlayerProfileCache().getProfileByUUID(id);
        return gp == null ? String.valueOf(id) : gp.getName();
    }

    // ------------------------------------------------------------------ signing
    static String hmac(String body) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte x : m.doFinal(body.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** shared with the website: config/pride-web.cfg "secret=…" (made on first use; never commit or show it) */
    static synchronized String secret() throws Exception {
        if (secret != null) return secret;
        File f = new File("config/pride-web.cfg");
        if (f.isFile()) for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (l.startsWith("secret=")) secret = l.substring(7).trim();
        if (secret == null || secret.isEmpty()) {
            byte[] r = new byte[32];
            new SecureRandom().nextBytes(r);
            StringBuilder sb = new StringBuilder();
            for (byte x : r) sb.append(String.format("%02x", x));
            secret = sb.toString();
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), ("# shared secret for the Pride website/app → game bridge. Keep private.\nsecret=" + secret + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return secret;
    }
}
