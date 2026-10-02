package com.dogpound.prideperms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Move to PridePerms from LuckPerms (its `/lp export` file, .json or .json.gz) or PermissionsEx (permissions.yml):
 * groups, inheritance, weights, prefixes/suffixes, meta, permissions (with expiry) and players' groups/permissions.
 * Existing PridePerms groups with the same name are merged into, never wiped. Bukkit plugin nodes come across as-is
 * (they're harmless on Forge; mod nodes are what PridePerms checks) — contexts other than "world" are skipped.
 */
public final class Importer {
    private Importer() {}

    /** returns a one-line report */
    public static String run(PermStore st, String kind, File f) throws Exception {
        if (!f.isFile()) throw new IllegalArgumentException("no file " + f);
        int[] n = new int[3];                                                 // groups, players, rules
        if (kind.startsWith("l")) luckperms(st, f, n);
        else if (kind.startsWith("p")) pex(st, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), n);
        else throw new IllegalArgumentException("luckperms or pex");
        st.changed();
        st.save();
        return String.format(Locale.ROOT, "Imported %d groups, %d players, %d rules from %s", n[0], n[1], n[2], f.getName());
    }

    // ------------------------------------------------------------------ LuckPerms
    static void luckperms(PermStore st, File f, int[] n) throws Exception {
        try (InputStream in = f.getName().endsWith(".gz") ? new GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f);
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            if (root.has("groups")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("groups").entrySet()) {
                PermStore.Group g = st.groups.containsKey(e.getKey()) ? st.groups.get(e.getKey()) : st.group(e.getKey(), 0, "");   // group() adds it to the map itself
                for (JsonElement nd : nodes(e.getValue())) lpNode(nd.getAsJsonObject(), g.nodes, g.timed, g.meta, g.parents, null, g, n);
                n[0]++;
            }
            if (root.has("users")) for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("users").entrySet()) {
                UUID id;
                try { id = UUID.fromString(e.getKey()); } catch (Exception ex) { continue; }
                JsonObject u = e.getValue().getAsJsonObject();
                PermStore.Player p = st.player(id, u.has("username") ? u.get("username").getAsString() : "");
                if (u.has("primaryGroup")) addOnce(p.groups, u.get("primaryGroup").getAsString());
                for (JsonElement nd : nodes(u)) lpNode(nd.getAsJsonObject(), p.nodes, p.timed, p.meta, p.groups, p, null, n);
                n[1]++;
            }
        }
    }

    private static Iterable<JsonElement> nodes(JsonElement holder) {
        JsonObject o = holder.getAsJsonObject();
        return o.has("nodes") ? o.getAsJsonArray("nodes") : new com.google.gson.JsonArray();
    }

    /** one LuckPerms node: permission, group.X (inheritance), prefix.P.text, suffix.P.text, weight.N, meta.k.v */
    private static void lpNode(JsonObject nd, Map<String, Boolean> plain, List<PermStore.Rule> timed, Map<String, String> meta,
                               List<String> parents, PermStore.Player p, PermStore.Group g, int[] n) {
        String key = nd.get("key").getAsString();
        boolean value = !nd.has("value") || nd.get("value").getAsBoolean();
        long expiry = nd.has("expiry") ? nd.get("expiry").getAsLong() * 1000L : 0;   // LuckPerms stores seconds
        if (expiry != 0 && expiry < System.currentTimeMillis()) return;        // already ran out
        JsonObject ctx = nd.has("context") && nd.get("context").isJsonObject() ? nd.getAsJsonObject("context") : null;
        if (ctx != null && (ctx.has("server") || ctx.has("gamemode"))) return; // contexts Forge has no meaning for
        if (key.startsWith("group.")) {
            String grp = key.substring(6);
            if (p != null && expiry != 0) p.tempGroups.put(grp, expiry); else if (value) addOnce(parents, grp);
            return;
        }
        String[] k = key.split("\\.", 3);
        if ((k[0].equals("prefix") || k[0].equals("suffix")) && k.length == 3) {
            String text = k[2].replace('&', '§');
            if (g != null) { if (k[0].equals("prefix")) g.prefix = text; else g.suffix = text; }
            if (p != null) { if (k[0].equals("prefix")) p.prefix = text; else p.suffix = text; }
            return;
        }
        if (k[0].equals("weight") && k.length == 2 && g != null) { try { g.priority = Integer.parseInt(k[1]); } catch (NumberFormatException ignored) {} return; }
        if (k[0].equals("meta") && k.length == 3) { meta.put(k[1], k[2]); return; }                      // meta.<key>.<value>
        if (k[0].equals("displayname")) return;
        if (expiry == 0 && ctx == null) plain.put(key, value);
        else {
            PermStore.Rule r = new PermStore.Rule();
            r.node = key; r.value = value; r.until = expiry;
            timed.add(r);
        }
        n[2]++;
    }

    // ------------------------------------------------------------------ PermissionsEx (permissions.yml)
    static void pex(PermStore st, String yaml, int[] n) {
        Map<String, Object> root = Yaml.parse(yaml);
        Map<String, Object> groups = Yaml.map(root.get("groups"));
        for (Map.Entry<String, Object> e : groups.entrySet()) {
            PermStore.Group g = st.groups.containsKey(e.getKey()) ? st.groups.get(e.getKey()) : st.group(e.getKey(), 0, "");   // group() adds it to the map itself
            Map<String, Object> gm = Yaml.map(e.getValue());
            for (String parent : Yaml.list(gm.get("inheritance"))) addOnce(g.parents, parent);
            n[2] += pexPerms(Yaml.list(gm.get("permissions")), g.nodes);
            Map<String, Object> opt = Yaml.map(gm.get("options"));
            if (opt.containsKey("prefix")) g.prefix = String.valueOf(opt.get("prefix")).replace('&', '§');
            if (opt.containsKey("suffix")) g.suffix = String.valueOf(opt.get("suffix")).replace('&', '§');
            if (opt.containsKey("rank")) try { g.priority = 1000 - Integer.parseInt(String.valueOf(opt.get("rank"))); } catch (NumberFormatException ignored) {}   // PEX: lower rank = higher
            for (Map.Entry<String, Object> o : opt.entrySet()) if (!o.getKey().matches("prefix|suffix|rank|default")) g.meta.put(o.getKey(), String.valueOf(o.getValue()));
            n[0]++;
        }
        Map<String, Object> users = Yaml.map(root.get("users"));
        for (Map.Entry<String, Object> e : users.entrySet()) {
            UUID id;
            try { id = UUID.fromString(e.getKey()); } catch (Exception ex) { continue; }
            Map<String, Object> um = Yaml.map(e.getValue());
            Map<String, Object> opt = Yaml.map(um.get("options"));
            PermStore.Player p = st.player(id, opt.containsKey("name") ? String.valueOf(opt.get("name")) : "");
            for (String grp : Yaml.list(um.get("group"))) addOnce(p.groups, grp);
            n[2] += pexPerms(Yaml.list(um.get("permissions")), p.nodes);
            n[1]++;
        }
    }

    private static int pexPerms(List<String> perms, Map<String, Boolean> into) {
        for (String s : perms) { if (s.startsWith("-")) into.put(s.substring(1), false); else into.put(s, true); }
        return perms.size();
    }

    private static void addOnce(List<String> l, String v) { if (!l.contains(v)) l.add(v); }

    /**
     * Just enough YAML for permissions.yml: nested maps by indentation, "- item" lists, quoted or plain scalars,
     * # comments. ponytail: no anchors, flow style or multi-line strings; add if a real file needs them.
     */
    static final class Yaml {
        static Map<String, Object> parse(String text) {
            List<String[]> lines = new ArrayList<>();                          // {indent, content}
            for (String raw : text.split("\r?\n")) {
                String l = stripComment(raw);
                if (l.trim().isEmpty()) continue;
                int ind = 0;
                while (ind < l.length() && l.charAt(ind) == ' ') ind++;
                lines.add(new String[]{String.valueOf(ind), l.trim()});
            }
            int[] at = {0};
            Object o = block(lines, at, 0);
            return map(o);
        }

        private static Object block(List<String[]> lines, int[] at, int indent) {
            if (at[0] >= lines.size()) return new LinkedHashMap<String, Object>();
            boolean list = lines.get(at[0])[1].startsWith("- ") || lines.get(at[0])[1].equals("-");
            if (list) {
                List<Object> out = new ArrayList<>();
                while (at[0] < lines.size() && Integer.parseInt(lines.get(at[0])[0]) == indent && lines.get(at[0])[1].startsWith("-")) {
                    out.add(scalar(lines.get(at[0])[1].substring(1).trim()));
                    at[0]++;
                }
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            while (at[0] < lines.size() && Integer.parseInt(lines.get(at[0])[0]) == indent) {
                String c = lines.get(at[0])[1];
                int colon = keyColon(c);
                if (colon < 0) { at[0]++; continue; }
                String key = unquote(c.substring(0, colon).trim()), rest = c.substring(colon + 1).trim();
                at[0]++;
                if (!rest.isEmpty()) out.put(key, rest.startsWith("[") ? flowList(rest) : scalar(rest));
                else if (at[0] < lines.size() && Integer.parseInt(lines.get(at[0])[0]) > indent) out.put(key, block(lines, at, Integer.parseInt(lines.get(at[0])[0])));
                else if (at[0] < lines.size() && Integer.parseInt(lines.get(at[0])[0]) == indent && lines.get(at[0])[1].startsWith("- ")) out.put(key, block(lines, at, indent));   // list at the same indent
                else out.put(key, "");
            }
            return out;
        }

        private static int keyColon(String c) {
            boolean q = false; char qc = 0;
            for (int i = 0; i < c.length(); i++) {
                char ch = c.charAt(i);
                if (q) { if (ch == qc) q = false; continue; }
                if (ch == '\'' || ch == '"') { q = true; qc = ch; continue; }
                if (ch == ':' && (i + 1 == c.length() || c.charAt(i + 1) == ' ')) return i;
            }
            return -1;
        }

        private static List<Object> flowList(String s) {
            List<Object> out = new ArrayList<>();
            String in = s.substring(1, s.lastIndexOf(']')).trim();
            if (!in.isEmpty()) for (String p : in.split(",")) out.add(scalar(p.trim()));
            return out;
        }

        private static String stripComment(String l) {
            boolean q = false; char qc = 0;
            for (int i = 0; i < l.length(); i++) {
                char ch = l.charAt(i);
                if (q) { if (ch == qc) q = false; continue; }
                if (ch == '\'' || ch == '"') { q = true; qc = ch; continue; }
                if (ch == '#' && (i == 0 || l.charAt(i - 1) == ' ')) return l.substring(0, i);
            }
            return l;
        }

        private static Object scalar(String s) { return unquote(s); }
        private static String unquote(String s) {
            if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) return s.substring(1, s.length() - 1);
            return s;
        }

        @SuppressWarnings("unchecked") static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }
        static List<String> list(Object o) {
            List<String> out = new ArrayList<>();
            if (o instanceof List) for (Object x : (List<?>) o) out.add(String.valueOf(x));
            else if (o instanceof String && !((String) o).isEmpty()) out.add((String) o);
            return out;
        }
    }
}
