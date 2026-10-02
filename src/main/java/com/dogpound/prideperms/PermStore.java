package com.dogpound.prideperms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups, players and their permission nodes, saved as JSON in the world folder (prideperms.json).
 *
 * A node is dot-separated, e.g. "mekanism.break.digital_miner" or "command.tp". A rule can be a wildcard: "mekanism.*",
 * "*.break.*" is NOT supported — only a trailing ".*" (and "*" for everything). Answer for (player, node):
 *   1. the player's own rules, most specific match first;
 *   2. otherwise each of the player's groups by priority (highest first), each group's rules then its parents';
 *   3. otherwise null (= "nobody said" → the caller's default).
 * Most specific wins inside one rule set: "mekanism.break.digital_miner" beats "mekanism.break.*" beats "mekanism.*" beats "*".
 */
public final class PermStore {
    /** a rule that only counts until a time (0 = forever) and/or only in one dimension (ANY_DIM = everywhere) */
    public static final class Rule {
        public String node;
        public boolean value;
        public long until;
        public int dim = ANY_DIM;
        /** Pride Realms land context: null = anywhere, else own | trusted | claimed | others | wild */
        public String land;

        public boolean live(long now, int where) { return live(now, where, null); }
        public boolean live(long now, int where, String here) {
            return (until == 0 || now < until) && (dim == ANY_DIM || dim == where) && (land == null || landMatches(land, here));
        }
    }

    /** does a rule's land context cover where the player is? (here: own | trusted | others | wild, null = unknown) */
    public static boolean landMatches(String rule, String here) {
        if (here == null) return false;
        switch (rule) {
            case "own": return here.equals("own");
            case "trusted": return here.equals("own") || here.equals("trusted");
            case "claimed": return !here.equals("wild");
            default: return rule.equals(here);                           // others, wild
        }
    }

    public static final class Group {
        public String name;
        public int priority;
        public String prefix = "", suffix = "";
        public List<String> parents = new ArrayList<>();
        public Map<String, Boolean> nodes = new LinkedHashMap<>();
        public List<Rule> timed = new ArrayList<>();
        public Map<String, String> meta = new LinkedHashMap<>();
    }

    public static final class Player {
        public String name = "";
        public String prefix = "", suffix = "";
        public List<String> groups = new ArrayList<>();
        public Map<String, Long> tempGroups = new LinkedHashMap<>();   // group → until (epoch ms)
        public Map<String, Boolean> nodes = new LinkedHashMap<>();
        public List<Rule> timed = new ArrayList<>();
        public Map<String, String> meta = new LinkedHashMap<>();
    }

    public static final int ANY_DIM = Integer.MIN_VALUE;

    public static final String DEFAULT = "default";

    public Map<String, Group> groups = new LinkedHashMap<>();
    public Map<String, Player> players = new LinkedHashMap<>();
    /** ladders for /perms promote|demote, e.g. staff: member → moderator → admin */
    public Map<String, List<String>> tracks = new LinkedHashMap<>();

    private transient File file;
    private transient final Map<String, Boolean> cache = new ConcurrentHashMap<>();   // "uuid|node" → answer (TRUE/FALSE; absent key = unknown)
    private transient final Set<String> unset = ConcurrentHashMap.newKeySet();        // "uuid|node" known to be unset
    private transient volatile long nextExpiry = Long.MAX_VALUE;           // soonest timed rule/group to run out
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** first run: default < member < moderator < admin, admin gets everything */
    public static PermStore fresh() {
        PermStore s = new PermStore();
        s.group(DEFAULT, 0, "§7");
        s.group("member", 10, "§a").parents.add(DEFAULT);
        Group mod = s.group("moderator", 50, "§b");
        mod.parents.add("member");
        Group admin = s.group("admin", 100, "§d");
        admin.parents.add("moderator");
        admin.nodes.put("*", true);
        return s;
    }

    public Group group(String name, int priority, String prefix) {
        Group g = new Group();
        g.name = name; g.priority = priority; g.prefix = prefix;
        groups.put(name, g);
        changed();
        return g;
    }

    public Player player(UUID id, String name) {
        Player p = players.computeIfAbsent(id.toString(), k -> new Player());
        if (name != null && !name.isEmpty()) p.name = name;
        return p;
    }

    // ------------------------------------------------------------------ answers
    /** TRUE / FALSE if some rule decides it, null if nobody said */
    public Boolean decide(UUID id, String node, boolean op) { return decide(id, node, op, ANY_DIM); }

    /** the same, for a player standing in dimension `dim` (per-dimension rules count only there) */
    public Boolean decide(UUID id, String node, boolean op, int dim) { return decide(id, node, op, dim, null); }

    /** …and standing on this kind of Pride Realms land (own | trusted | others | wild, null = unknown) */
    public Boolean decide(UUID id, String node, boolean op, int dim, String land) {
        long now = System.currentTimeMillis();
        if (now >= nextExpiry) prune(now);
        String key = id + (op ? "|op|" : "|") + dim + "|" + land + "|" + node;
        Boolean c = cache.get(key);
        if (c != null) return c;
        if (unset.contains(key)) return null;
        Boolean r = compute(players.get(id.toString()), node, op, dim, land);
        if (r == null) unset.add(key); else cache.put(key, r);
        return r;
    }

    Boolean compute(Player p, String node, boolean op) { return compute(p, node, op, ANY_DIM); }

    Boolean compute(Player p, String node, boolean op, int dim) { return compute(p, node, op, dim, null); }

    Boolean compute(Player p, String node, boolean op, int dim, String land) {
        if (p != null) {
            Boolean own = match(rules(p.nodes, p.timed, dim, land), node);
            if (own != null) return own;
        }
        for (Group g : groupsOf(p, op)) {
            Boolean r = groupDecides(g, node, new HashSet<>(), dim, land);
            if (r != null) return r;
        }
        return null;
    }

    Boolean groupDecides(Group g, String node, Set<String> seen) { return groupDecides(g, node, seen, ANY_DIM); }

    Boolean groupDecides(Group g, String node, Set<String> seen, int dim) { return groupDecides(g, node, seen, dim, null); }

    Boolean groupDecides(Group g, String node, Set<String> seen, int dim, String land) {
        if (g == null || !seen.add(g.name)) return null;                // unknown or a loop in the parents
        Boolean r = match(rules(g.nodes, g.timed, dim, land), node);
        if (r != null) return r;
        for (String parent : g.parents) {
            r = groupDecides(groups.get(parent), node, seen, dim, land);
            if (r != null) return r;
        }
        return null;
    }

    /** plain rules + the timed/dimension ones that count right now (those win over plain ones for the same node) */
    static Map<String, Boolean> rules(Map<String, Boolean> plain, List<Rule> timed, int dim) { return rules(plain, timed, dim, null); }

    static Map<String, Boolean> rules(Map<String, Boolean> plain, List<Rule> timed, int dim, String land) {
        if (timed == null || timed.isEmpty()) return plain;
        long now = System.currentTimeMillis();
        Map<String, Boolean> m = null;
        for (Rule r : timed) {
            if (!r.live(now, dim, land)) continue;
            if (m == null) m = new java.util.HashMap<>(plain);
            m.put(r.node, r.value);
        }
        return m == null ? plain : m;
    }

    /** drop rules and group memberships whose time is up; remember when the next one ends */
    synchronized void prune(long now) {
        long next = Long.MAX_VALUE;
        boolean any = false;
        for (Group g : groups.values()) {
            any |= g.timed.removeIf(r -> r.until != 0 && now >= r.until);
            for (Rule r : g.timed) if (r.until != 0) next = Math.min(next, r.until);
        }
        for (Player p : players.values()) {
            any |= p.timed.removeIf(r -> r.until != 0 && now >= r.until);
            any |= p.tempGroups.values().removeIf(t -> now >= t);
            for (Rule r : p.timed) if (r.until != 0) next = Math.min(next, r.until);
            for (long t : p.tempGroups.values()) next = Math.min(next, t);
        }
        if (any) save();
        cache.clear(); unset.clear();
        nextExpiry = next;                                               // set last: save() re-arms it to "now"
    }

    /** the player's groups by priority, highest first; everyone is in "default", ops also in "admin" */
    public List<Group> groupsOf(Player p, boolean op) {
        List<Group> out = new ArrayList<>();
        if (op && groups.containsKey("admin")) out.add(groups.get("admin"));
        if (p != null) for (String n : p.groups) { Group g = groups.get(n); if (g != null && !out.contains(g)) out.add(g); }
        if (p != null) { long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : p.tempGroups.entrySet()) { Group g = groups.get(e.getKey()); if (g != null && now < e.getValue() && !out.contains(g)) out.add(g); } }
        Group d = groups.get(DEFAULT);
        if (d != null && !out.contains(d)) out.add(d);
        out.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return out;
    }

    /** most specific rule in one set: exact, then each shorter ".*", then "*" */
    static Boolean match(Map<String, Boolean> rules, String node) {
        if (rules == null || rules.isEmpty()) return null;
        Boolean r = rules.get(node);
        if (r != null) return r;
        String n = node;
        for (int dot = n.lastIndexOf('.'); dot > 0; dot = n.lastIndexOf('.')) {
            n = n.substring(0, dot);
            r = rules.get(n + ".*");
            if (r != null) return r;
        }
        return rules.get("*");
    }

    public String prefixOf(UUID id, boolean op) {
        Player p = players.get(id.toString());
        if (p != null && !p.prefix.isEmpty()) return p.prefix;
        List<Group> gs = groupsOf(p, op);
        return gs.isEmpty() ? "" : gs.get(0).prefix;
    }

    public String suffixOf(UUID id, boolean op) {
        Player p = players.get(id.toString());
        if (p != null && !p.suffix.isEmpty()) return p.suffix;
        for (Group g : groupsOf(p, op)) if (!g.suffix.isEmpty()) return g.suffix;
        return "";
    }

    /** meta value: the player's own, else from their groups (highest priority first), else "" */
    public String metaOf(UUID id, boolean op, String key) {
        Player p = players.get(id.toString());
        if (p != null && p.meta.containsKey(key)) return p.meta.get(key);
        for (Group g : groupsOf(p, op)) if (g.meta.containsKey(key)) return g.meta.get(key);
        return "";
    }

    // ------------------------------------------------------------------ saving
    /** rules changed: forget cached answers and re-check expiry times on the next question */
    public void changed() { cache.clear(); unset.clear(); nextExpiry = 0; }

    public static PermStore load(File f) {
        PermStore s = null;
        if (f.isFile()) {
            try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                s = GSON.fromJson(r, PermStore.class);
            } catch (Exception e) {
                PridePerms.LOG.error("prideperms.json couldn't be read — keeping it untouched as prideperms.json.broken and starting fresh", e);
                try { Files.copy(f.toPath(), new File(f.getParentFile(), "prideperms.json.broken").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            }
        }
        if (s == null) s = fresh();
        if (s.groups == null) s.groups = new LinkedHashMap<>();
        if (s.players == null) s.players = new LinkedHashMap<>();
        if (!s.groups.containsKey(DEFAULT)) s.group(DEFAULT, 0, "§7");
        s.fillMissing();
        s.file = f;
        s.save();
        return s;
    }

    /** where save() writes (tests, imports into a fresh store) */
    public void setFile(File f) { file = f; }

    public synchronized void save() {
        changed();
        WebHook.dirty = true;                                     // the website's ranks.json/players.json refresh within a second
        try {                                                     // names in chat pick up new prefixes right away
            net.minecraft.server.MinecraftServer srv = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (srv != null && file != null) for (net.minecraft.entity.player.EntityPlayerMP pl : srv.getPlayerList().getPlayers()) pl.refreshDisplayName();
        } catch (Throwable ignored) {}
        if (file == null) return;
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) { GSON.toJson(this, w); }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            PridePerms.LOG.error("couldn't save prideperms.json", e);
        }
    }

    /** the menu: what a player gets from their own rules + groups (not counting op) */
    public Boolean decideFor(Player p, String node) { return compute(p, node, false); }

    /** the menu: what a group gets from its own rules + parents */
    public Boolean groupDecidesPublic(Group g, String node) { return groupDecides(g, node, new HashSet<>()); }

    /** older or hand-edited files: every list/map exists */
    void fillMissing() {
        if (tracks == null) tracks = new LinkedHashMap<>();
        for (Group g : groups.values()) {
            if (g.parents == null) g.parents = new ArrayList<>();
            if (g.nodes == null) g.nodes = new LinkedHashMap<>();
            if (g.timed == null) g.timed = new ArrayList<>();
            if (g.meta == null) g.meta = new LinkedHashMap<>();
            if (g.prefix == null) g.prefix = "";
            if (g.suffix == null) g.suffix = "";
        }
        for (Player p : players.values()) {
            if (p.groups == null) p.groups = new ArrayList<>();
            if (p.tempGroups == null) p.tempGroups = new LinkedHashMap<>();
            if (p.nodes == null) p.nodes = new LinkedHashMap<>();
            if (p.timed == null) p.timed = new ArrayList<>();
            if (p.meta == null) p.meta = new LinkedHashMap<>();
            if (p.prefix == null) p.prefix = "";
            if (p.suffix == null) p.suffix = "";
        }
        nextExpiry = 0;                                                  // work out the first expiry on the next check
    }

    public String toJson() { return GSON.toJson(this); }

    /** the client's copy for the menu (never saved) */
    public static PermStore fromJson(String json) {
        PermStore s = null;
        try { s = GSON.fromJson(json, PermStore.class); } catch (Exception ignored) {}
        if (s == null) s = new PermStore();
        if (s.groups == null) s.groups = new LinkedHashMap<>();
        if (s.players == null) s.players = new LinkedHashMap<>();
        s.fillMissing();
        s.nextExpiry = Long.MAX_VALUE;                                   // the menu's copy never prunes/saves
        return s;
    }

    /** a copy of a group under a new name: same rules, parents, prefix, meta — no members */
    public Group cloneGroup(String from, String to) {
        Group g = GSON.fromJson(GSON.toJson(groups.get(from)), Group.class);
        g.name = to;
        groups.put(to, g);
        fillMissing();
        changed();
        return g;
    }

    /** rename a group everywhere: members, temporary members, children's parents, tracks */
    public void renameGroup(String from, String to) {
        Group g = groups.remove(from);
        g.name = to;
        groups.put(to, g);
        for (Player p : players.values()) {
            int i = p.groups.indexOf(from);
            if (i >= 0) p.groups.set(i, to);
            Long until = p.tempGroups.remove(from);
            if (until != null) p.tempGroups.put(to, until);
        }
        for (Group o : groups.values()) { int i = o.parents.indexOf(from); if (i >= 0) o.parents.set(i, to); }
        for (List<String> steps : tracks.values()) { int i = steps.indexOf(from); if (i >= 0) steps.set(i, to); }
        changed();
    }

    public List<String> groupNames() {
        List<String> n = new ArrayList<>(groups.keySet());
        Collections.sort(n);
        return n;
    }
}
