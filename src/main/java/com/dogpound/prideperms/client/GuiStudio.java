package com.dogpound.prideperms.client;

import com.dogpound.prideperms.Net;
import com.dogpound.prideperms.PermStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.network.NetworkPlayerInfo;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PERMISSIONS STUDIO — /perms menu (ops and admins). Every rank, player, rule, track, price, join-gate setting and
 * switch in one Pride panel, with no website needed:
 *   Dashboard · Groups · Players · Rule editor · Tracks · Rank shop · Join gate · Checker · Change log · Backups · Settings
 * Every button sends the same /perms (or /verify) words an admin could type, so the server checks it the same way;
 * what the server answers shows in the bar at the bottom (and in full on the Checker page).
 */
public class GuiStudio extends GuiScreen {
    // ------------------------------------------------------------------ data from the server
    static Map<String, String> config = new LinkedHashMap<>();
    static List<String[]> verified = new ArrayList<>();                  // {uuid, name, account, at}
    static int codesWaiting;
    static List<String> locked = new ArrayList<>(), log = new ArrayList<>(), backups = new ArrayList<>(), nodes = new ArrayList<>();
    static List<String[]> online = new ArrayList<>();                     // {name, uuid, op}
    static boolean handlerOurs, spotSet;
    static String startPage = "";

    static String reply = "";
    static long replyAt;

    static void accept(String json) {
        GuiPerms.accept(json);
        try {
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            startPage = o.has("page") ? o.get("page").getAsString() : "";
            JsonObject s = o.getAsJsonObject("studio");
            if (s == null) return;
            Map<String, String> c = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : s.getAsJsonObject("config").entrySet()) c.put(e.getKey(), e.getValue().getAsString());
            config = c;
            handlerOurs = s.get("handler").getAsBoolean();
            JsonObject g = s.getAsJsonObject("gate");
            List<String[]> v = new ArrayList<>();
            for (JsonElement e : g.getAsJsonArray("verified")) {
                JsonObject j = e.getAsJsonObject();
                v.add(new String[]{j.get("uuid").getAsString(), j.get("name").getAsString(), str(j, "account"), String.valueOf(j.get("at").getAsLong())});
            }
            verified = v;
            codesWaiting = g.get("codes").getAsInt();
            locked = strings(g.getAsJsonArray("locked"));
            spotSet = g.get("spot").getAsBoolean();
            List<String[]> on = new ArrayList<>();
            for (JsonElement e : s.getAsJsonArray("online")) {
                JsonObject j = e.getAsJsonObject();
                on.add(new String[]{j.get("name").getAsString(), j.get("uuid").getAsString(), String.valueOf(j.get("op").getAsBoolean())});
            }
            online = on;
            log = strings(s.getAsJsonArray("log"));
            backups = strings(s.getAsJsonArray("backups"));
            nodes = strings(s.getAsJsonArray("nodes"));
        } catch (Exception ignored) {}
    }

    private static String str(JsonObject j, String k) { return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : ""; }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    static void replied(String text) { reply = text == null ? "" : text; replyAt = System.currentTimeMillis(); }

    // ------------------------------------------------------------------ pages
    private static final String[][] PAGES = {
            // id, label, icon, accent colour
            {"dashboard", "Dashboard", "✦", "F5A9B8"}, {"groups", "Groups", "♛", "E40303"}, {"players", "Players", "☺", "FF8C00"},
            {"rules", "Rule editor", "⚙", "FFED00"}, {"tracks", "Tracks", "⇅", "008026"}, {"shop", "Rank shop", "$", "24408E"},
            {"gate", "Join gate", "✪", "732982"}, {"checker", "Checker", "?", "5BCEFA"}, {"log", "Change log", "✎", "F5A9B8"},
            {"backups", "Backups", "⟲", "FF8C00"}, {"settings", "Settings", "☰", "8A8499"}};

    private String page = "dashboard";
    private String group, player;                                        // selected group / player (null = the list)
    private int scroll;
    private PrideFrame f;
    private int railW, px, pw, top, bottom;
    private GuiTextField search, nodeBox, whoBox;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();
    private String hover;
    private int contentH;

    /** opens on the page the server asked for: "gate", or "groups:vip" / "players:Steve" for one group or player */
    public GuiStudio() {
        String[] want = startPage.split(":", 2);
        for (String[] p : PAGES) if (p[0].equals(want[0])) page = want[0];
        if (want.length == 2 && page.equals("groups") && GuiPerms.rules.groups.containsKey(want[1].toLowerCase(Locale.ROOT))) group = want[1].toLowerCase(Locale.ROOT);
        if (want.length == 2 && page.equals("players")) player = want[1];
        startPage = "";
    }

    void refresh() { if (group != null && !GuiPerms.rules.groups.containsKey(group)) group = null; }

    static PermStore rules() { return GuiPerms.rules; }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.fit(width, height);
        railW = Math.max(96, Math.min(124, f.cw / 7));
        px = f.cx + railW + 10;
        pw = f.cx + f.cw - px;
        top = f.cy;
        bottom = f.cy + f.ch - 18;
        search = field(search, 0, 60);
        nodeBox = field(nodeBox, 1, 120);
        whoBox = field(whoBox, 2, 16);
        if (whoBox.getText().isEmpty() && mc.player != null) whoBox.setText(mc.player.getName());
    }

    private GuiTextField field(GuiTextField old, int id, int max) {
        GuiTextField t = new GuiTextField(id, fontRenderer, 0, 0, 100, 14);
        t.setMaxStringLength(max);
        if (old != null) { t.setText(old.getText()); t.setFocused(old.isFocused()); }
        return t;
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public boolean doesGuiPauseGame() { return false; }

    // ------------------------------------------------------------------ drawing
    @Override
    public void drawScreen(int mx, int my, float pt) {
        boxes.clear(); clicks.clear(); hover = null;
        String gate = "true".equals(config.get("gateEnabled")) ? "§agate ON" : "§8gate off";
        f.draw(this, "Permissions Studio", "§7" + online.size() + " online · " + GuiPerms.rules.groups.size() + " ranks · " + gate);
        drawRail(mx, my);

        PrideFrame.clip(px, top, pw, bottom - top);
        int y = top - scroll;
        switch (page) {
            case "dashboard": y = dashboard(y, mx, my); break;
            case "groups": y = group == null ? groups(y, mx, my) : groupDetail(y, mx, my); break;
            case "players": y = players(y, mx, my); break;
            case "rules": y = rulesPage(y, mx, my); break;
            case "tracks": y = tracks(y, mx, my); break;
            case "shop": y = shop(y, mx, my); break;
            case "gate": y = gatePage(y, mx, my); break;
            case "checker": y = checker(y, mx, my); break;
            case "log": y = logPage(y, mx, my); break;
            case "backups": y = backupsPage(y, mx, my); break;
            case "settings": y = settings(y, mx, my); break;
        }
        PrideFrame.unclip();
        contentH = y + scroll - top;
        PrideFrame.scrollbar(px + pw - 3, top, bottom - top, scroll, bottom - top, Math.max(contentH, 1));
        drawReplyBar(mx, my);
        if (hover != null) drawHoveringText(Arrays.asList(hover.split("\n")), mx, my);
    }

    private void drawRail(int mx, int my) {
        int y = f.cy, step = Math.max(12, Math.min(19, (f.ch - 2) / PAGES.length)), h = step - 2;
        for (String[] p : PAGES) {
            boolean on = p[0].equals(page), over = mx >= f.cx && mx < f.cx + railW && my >= y && my < y + h;
            int accent = 0xFF000000 | Integer.parseInt(p[3], 16);
            Gui.drawRect(f.cx, y, f.cx + railW, y + h, on ? PrideFrame.TILE_ON : over ? 0xFF2A2140 : PrideFrame.TILE);
            Gui.drawRect(f.cx, y, f.cx + 2, y + h, accent);
            fontRenderer.drawStringWithShadow((on ? "§f" : "§7") + p[2] + "  " + p[1], f.cx + 7, y + (h - 8) / 2 + 1, 0xFFFFFF);
            final String id = p[0];
            click(f.cx, y, railW, h, () -> { page = id; scroll = 0; group = null; player = null; });
            y += step;
        }
        if (y + 24 < f.cy + f.ch) {                                          // room left: a little status
            fontRenderer.drawStringWithShadow("§8" + (handlerOurs ? "Forge perms: ours ✔" : "Forge perms: other mod"), f.cx + 2, y + 6, 0xFFFFFF);
            fontRenderer.drawStringWithShadow("§8/perms menu <page>", f.cx + 2, y + 16, 0xFFFFFF);
        }
    }

    private void drawReplyBar(int mx, int my) {
        int y = f.cy + f.ch - 14;
        Gui.drawRect(px, y, px + pw, y + 13, 0x70000000);
        long age = System.currentTimeMillis() - replyAt;
        if (reply.isEmpty() || age > 20000) {
            fontRenderer.drawStringWithShadow("§8Changes save instantly · everything here also works as a /perms command", px + 4, y + 3, 0xFFFFFF);
            return;
        }
        String[] lines = reply.split("\n");
        String first = lines[0] + (lines.length > 1 ? " §8(+" + (lines.length - 1) + " more — hover)" : "");
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(first, pw - 8), px + 4, y + 3, 0xFFFFFF);
        if (lines.length > 1 && mx >= px && mx < px + pw && my >= y && my < y + 13) hover = reply;
    }

    // ------------------------------------------------------------------ widgets
    private void click(int x, int y, int w, int h, Runnable r) { boxes.add(new int[]{x, y, w, h}); clicks.add(r); }

    private boolean visible(int y, int h) { return y + h > top && y < bottom; }

    private void send(String args) { Net.CH.sendToServer(new Net.Edit(args)); }

    private void ask(String question, String start, java.util.function.Consumer<String> answer) { mc.displayGuiScreen(new GuiAsk(this, question, start, answer)); }

    private boolean btn(int x, int y, int w, int h, String label, Runnable r, int mx, int my) {
        if (!visible(y, h)) return false;
        boolean over = PrideFrame.button(x, y, w, h, label, PrideFrame.BUTTON, mx, my);
        click(x, y, w, h, r);
        return over;
    }

    private int heading(int y, String text) {
        if (visible(y, 14)) {
            fontRenderer.drawStringWithShadow(text, px, y + 2, 0xFFFFFF);
            Gui.drawRect(px, y + 12, px + pw - 8, y + 13, 0x30FFFFFF);
        }
        return y + 17;
    }

    private int text(int y, String t) {
        for (String line : fontRenderer.listFormattedStringToWidth(t, pw - 10)) {
            if (visible(y, 10)) fontRenderer.drawStringWithShadow(line, px, y, 0xFFFFFF);
            y += 10;
        }
        return y + 2;
    }

    /** a stat tile: big number, small caption */
    private void tile(int x, int y, int w, String value, String caption, int accent, Runnable onClick, int mx, int my) {
        if (!visible(y, 34)) return;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 34;
        PrideFrame.tile(x, y, w, 34, accent, over && onClick != null, false);
        fontRenderer.drawStringWithShadow("§l" + value, x + 6, y + 7, 0xFFFFFF);
        fontRenderer.drawStringWithShadow("§7" + fontRenderer.trimStringToWidth(caption, w - 10), x + 6, y + 21, 0xFFFFFF);
        if (onClick != null) click(x, y, w, 34, onClick);
    }

    /** label (+ grey description) with an on/off switch on the right */
    private int toggle(int y, String label, String desc, boolean on, Runnable flip, int mx, int my) {
        if (visible(y, 20)) {
            fontRenderer.drawStringWithShadow(label, px, y + 1, 0xFFFFFF);
            if (!desc.isEmpty()) fontRenderer.drawStringWithShadow("§8" + fontRenderer.trimStringToWidth(desc, pw - 60), px, y + 11, 0xFFFFFF);
            int sx = px + pw - 42;
            boolean over = mx >= sx && mx < sx + 30 && my >= y && my < y + 14;
            Gui.drawRect(sx, y + 1, sx + 30, y + 13, on ? (over ? 0xFF2FA055 : 0xFF1F7A3A) : (over ? 0xFF4A4060 : 0xFF2A2238));
            Gui.drawRect(on ? sx + 17 : sx + 2, y + 3, on ? sx + 28 : sx + 13, y + 11, 0xFFFFFFFF);
            click(sx, y, 30, 14, flip);
        }
        return y + (desc.isEmpty() ? 16 : 24);
    }

    /** label: value [button] */
    private int row(int y, String label, String value, String button, Runnable r, int mx, int my) {
        if (visible(y, 14)) {
            String t = label + (value.isEmpty() ? "" : "§7: §f" + value);
            String fit = fontRenderer.trimStringToWidth(t, pw - 84);
            fontRenderer.drawStringWithShadow(fit.length() < t.length() ? fontRenderer.trimStringToWidth(t, pw - 92) + "§7…" : t, px, y + 3, 0xFFFFFF);
            if (fit.length() < t.length() && mx >= px && mx < px + pw - 80 && my >= y && my < y + 14) hover = t;
            btn(px + pw - 76, y, 66, 14, button, r, mx, my);
        }
        return y + 16;
    }

    /** label [−] value [+] */
    private int stepper(int y, String label, String value, Runnable minus, Runnable plus, int mx, int my) {
        if (visible(y, 14)) {
            String fit = fontRenderer.trimStringToWidth(label, pw - 118);
            fontRenderer.drawStringWithShadow(fit.length() < label.length() ? fontRenderer.trimStringToWidth(label, pw - 126) + "§7…" : label, px, y + 3, 0xFFFFFF);
            if (fit.length() < label.length() && mx >= px && mx < px + pw - 118 && my >= y && my < y + 14) hover = label;
            int x = px + pw - 110;
            btn(x, y, 20, 14, "−", minus, mx, my);
            String v = "§f" + value;
            fontRenderer.drawStringWithShadow(v, x + 24 + (56 - fontRenderer.getStringWidth(v)) / 2, y + 3, 0xFFFFFF);
            btn(x + 84, y, 20, 14, "+", plus, mx, my);
        }
        return y + 16;
    }

    /** a row of chips that wrap; returns the y under them */
    private int chips(int y, List<String> labels, List<Boolean> on, List<Runnable> actions, int mx, int my) {
        int x = px;
        for (int i = 0; i < labels.size(); i++) {
            int w = fontRenderer.getStringWidth(labels.get(i)) + 14;
            if (x + w > px + pw - 8) { x = px; y += 16; }
            if (visible(y, 14)) {
                boolean over = mx >= x && mx < x + w && my >= y && my < y + 14;
                Gui.drawRect(x, y, x + w, y + 14, on.get(i) ? (over ? 0xFF7F4FB8 : PrideFrame.TILE_ON) : (over ? 0xFF3A3050 : PrideFrame.BUTTON));
                fontRenderer.drawStringWithShadow((on.get(i) ? "§a✔ " : "§8· ") + labels.get(i), x + 3, y + 3, 0xFFFFFF);
                click(x, y, w, 14, actions.get(i));
            }
            x += w + 4;
        }
        return y + 18;
    }

    private String cfg(String k) { return config.getOrDefault(k, ""); }

    private boolean cfgOn(String k) { return "true".equals(config.get(k)); }

    private void setCfg(String k, String v) { send("config " + k + " " + v); }

    private List<String> cfgList(String k) {
        List<String> out = new ArrayList<>();
        for (String s : cfg(k).split("\\s*;;\\s*")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private void setCfgList(String k, List<String> lines) { setCfg(k, String.join(" ;; ", lines)); }

    private static String colorOf(String g) { PermStore.Group x = GuiPerms.rules.groups.get(g); return x == null ? "" : x.prefix; }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        return s < 60 ? "just now" : s < 3600 ? s / 60 + "m ago" : s < 86400 ? s / 3600 + "h ago" : s / 86400 + "d ago";
    }

    private static String left(long until) {
        long s = Math.max(0, (until - System.currentTimeMillis()) / 1000);
        return s >= 86400 ? s / 86400 + "d " + s % 86400 / 3600 + "h" : s >= 3600 ? s / 3600 + "h " + s % 3600 / 60 + "m" : s / 60 + "m " + s % 60 + "s";
    }

    private List<String> membersOf(String g) {
        List<String> out = new ArrayList<>();
        for (PermStore.Player p : rules().players.values()) if (p.groups.contains(g) || p.tempGroups.containsKey(g)) out.add(p.name);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<String> allPlayers() {
        Set<String> n = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PermStore.Player p : rules().players.values()) if (p.name != null && !p.name.isEmpty()) n.add(p.name);
        for (String[] o : online) n.add(o[0]);
        if (mc.getConnection() != null) for (NetworkPlayerInfo i : mc.getConnection().getPlayerInfoMap()) n.add(i.getGameProfile().getName());
        return new ArrayList<>(n);
    }

    private boolean isOnline(String name) { for (String[] o : online) if (o[0].equalsIgnoreCase(name)) return true; return false; }

    private PermStore.Player playerData(String name) {
        for (PermStore.Player p : rules().players.values()) if (name.equalsIgnoreCase(p.name)) return p;
        return null;
    }

    private String[] verifiedRow(String name) { for (String[] v : verified) if (v[1].equalsIgnoreCase(name)) return v; return null; }

    private String topPrefix(String name) {
        PermStore.Player p = playerData(name);
        if (p != null && !p.prefix.isEmpty()) return p.prefix;
        String best = ""; int pri = Integer.MIN_VALUE;
        if (p != null) for (String g : p.groups) { PermStore.Group x = rules().groups.get(g); if (x != null && x.priority > pri && !x.prefix.isEmpty()) { pri = x.priority; best = x.prefix; } }
        if (best.isEmpty()) { PermStore.Group d = rules().groups.get(PermStore.DEFAULT); if (d != null) best = d.prefix; }
        return best;
    }

    private String styled(String name) {
        String pre = topPrefix(name);
        PermStore.Player p = playerData(name);
        String nc = p == null ? "" : p.meta.getOrDefault("namecolor", "");
        return pre + (pre.matches("(§.)*") || pre.endsWith(" ") ? "" : " ") + "§r" + (nc.isEmpty() ? "§f" : nc) + name;
    }

    // ================================================================== DASHBOARD
    private int dashboard(int y, int mx, int my) {
        PermStore r = rules();
        int forSale = 0;
        for (PermStore.Group g : r.groups.values()) if (g.meta.containsKey("price")) forSale++;
        int n = pw >= 560 ? 6 : 3, gap = 6, w = (pw - 8 - gap * (n - 1)) / n;
        String[][] tiles = {{String.valueOf(r.groups.size()), "ranks", "groups"}, {String.valueOf(r.players.size()), "players with rules", "players"},
                {String.valueOf(online.size()), "online now", "players"}, {String.valueOf(r.tracks.size()), "tracks", "tracks"},
                {String.valueOf(forSale), "ranks for sale", "shop"}, {String.valueOf(verified.size()), cfgOn("gateEnabled") ? "verified · gate ON" : "verified · gate off", "gate"}};
        int[] colours = {PrideFrame.RAINBOW[0], PrideFrame.RAINBOW[1], PrideFrame.RAINBOW[3], PrideFrame.RAINBOW[4], PrideFrame.RAINBOW[5], PrideFrame.RAINBOW[6]};
        for (int i = 0; i < tiles.length; i++) {
            final String to = tiles[i][2];
            tile(px + (i % n) * (w + gap), y + (i / n) * 40, w, tiles[i][0], tiles[i][1], colours[i], () -> { page = to; scroll = 0; }, mx, my);
        }
        y += (tiles.length / n) * 40 + 2;

        int half = (pw - 16) / 2, lx = px, rx = px + half + 8, ly = y, ry = y;
        // left: online now
        if (visible(ly, 14)) fontRenderer.drawStringWithShadow("§fOnline now", lx, ly + 2, 0xFFFFFF);
        ly += 14;
        if (online.isEmpty() && visible(ly, 12)) { fontRenderer.drawStringWithShadow("§8nobody", lx, ly, 0xFFFFFF); ly += 12; }
        for (String[] o : online) {
            if (visible(ly, 14)) {
                PrideFrame.card(lx, ly, half, 14, Boolean.parseBoolean(o[2]) ? PrideFrame.PINK : PrideFrame.BLUE);
                boolean locked = GuiStudio.locked.contains(o[0]);
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§a● " + styled(o[0]) + (Boolean.parseBoolean(o[2]) ? " §d(op)" : "") + (locked ? " §5✪" : ""), half - 50), lx + 3, ly + 3, 0xFFFFFF);
                final String name = o[0];
                PrideFrame.button(lx + half - 44, ly + 1, 42, 12, "Edit", PrideFrame.BUTTON, mx, my);
                click(lx + half - 44, ly + 1, 42, 12, () -> { page = "players"; player = name; scroll = 0; });
            }
            ly += 16;
        }
        // right: recent changes
        if (visible(ry, 14)) fontRenderer.drawStringWithShadow("§fRecent changes", rx, ry + 2, 0xFFFFFF);
        ry += 14;
        if (log.isEmpty() && visible(ry, 12)) { fontRenderer.drawStringWithShadow("§8none yet", rx, ry, 0xFFFFFF); ry += 12; }
        for (String l : log.subList(0, Math.min(10, log.size()))) {
            if (visible(ry, 12)) {
                String t = l.length() > 11 ? "§8" + l.substring(11, Math.min(16, l.length())) + " §7" + l.substring(Math.min(21, l.length())) : l;
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(t, half), rx, ry, 0xFFFFFF);
                if (fontRenderer.getStringWidth(t) > half && mx >= rx && mx < rx + half && my >= ry && my < ry + 10) hover = l;
            }
            ry += 11;
        }
        y = Math.max(ly, ry) + 8;

        y = heading(y, "§fQuick actions");
        String[][] acts = {
                {"§a✦ Add ready-made ranks", "preset"}, {"⟲ Backup now", "backup dashboard"}, {"↻ Reload from file", "reload"},
                {"◉ Watch checks live", "verbose on"}, {"✖ Stop watching", "verbose off"}, {"⚙ Rule editor", ""}};
        int bw = (pw - 8 - 5 * 4) / 3;
        for (int i = 0; i < acts.length; i++) {
            final String cmd = acts[i][1];
            int bx = px + (i % 3) * (bw + 4), by = y + (i / 3) * 18;
            btn(bx, by, bw, 15, acts[i][0], () -> { if (cmd.isEmpty()) openRules(false, PermStore.DEFAULT); else send(cmd); }, mx, my);
        }
        y += 40;
        y = heading(y, "§fHow it works");
        y = text(y, "§7Everyone is in §fdefault§7. Groups (ranks) give or take permissions and pass them to groups that inherit them. "
                + "A player's own rules beat their groups; the higher-priority group wins when groups disagree. Nothing set anywhere = allowed. "
                + "Ops count as §dadmin§7 unless you turn that off in Settings.");
        return y;
    }

    // ================================================================== GROUPS
    private int groups(int y, int mx, int my) {
        int bw = 110;
        btn(px, y, bw, 15, "§a+ New group", () -> ask("Name of the new group", "", s -> { String n = GuiPerms.clean(s.trim()); if (!n.isEmpty()) { send("group " + n + " create"); group = n; } }), mx, my);
        btn(px + bw + 4, y, bw, 15, "✦ Ready-made ranks", () -> send("preset"), mx, my);
        y += 22;
        List<PermStore.Group> gs = new ArrayList<>(rules().groups.values());
        gs.sort((a, b) -> b.priority - a.priority);
        int cols = Math.max(1, (pw - 8) / 140), cw = (pw - 8 - (cols - 1) * 6) / cols, ch = 62;
        for (int i = 0; i < gs.size(); i++) {
            PermStore.Group g = gs.get(i);
            int x = px + (i % cols) * (cw + 6), cy = y + (i / cols) * (ch + 6);
            if (!visible(cy, ch)) continue;
            boolean over = mx >= x && mx < x + cw && my >= cy && my < cy + ch;
            PrideFrame.tile(x, cy, cw, ch, PrideFrame.RAINBOW[i % PrideFrame.RAINBOW.length], over, false);
            String look = g.prefix + (g.prefix.matches("(§.)*") || g.prefix.endsWith(" ") ? "" : " ") + "§r§f" + g.name + g.suffix;
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(look, cw - 10), x + 6, cy + 7, 0xFFFFFF);
            fontRenderer.drawStringWithShadow("§7priority §f" + g.priority + " §7· §f" + membersOf(g.name).size() + " §7members", x + 6, cy + 20, 0xFFFFFF);
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§7" + (g.nodes.size() + g.timed.size()) + " rules · inherits §f" + (g.parents.isEmpty() ? "—" : String.join(", ", g.parents)), cw - 10), x + 6, cy + 31, 0xFFFFFF);
            StringBuilder badges = new StringBuilder();
            if (g.meta.containsKey("price")) badges.append("§e$").append(g.meta.get("price")).append("  ");
            if (g.meta.containsKey("hours")) badges.append("§b⏱ ").append(g.meta.get("hours")).append("h  ");
            for (Map.Entry<String, List<String>> t : rules().tracks.entrySet()) if (t.getValue().contains(g.name)) badges.append("§a⇅ ").append(t.getKey()).append("  ");
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(badges.toString(), cw - 10), x + 6, cy + 45, 0xFFFFFF);
            final String name = g.name;
            click(x, cy, cw, ch, () -> { group = name; scroll = 0; });
        }
        return y + ((gs.size() + cols - 1) / cols) * (ch + 6) + 6;
    }

    private int groupDetail(int y, int mx, int my) {
        PermStore.Group g = rules().groups.get(group);
        if (g == null) { group = null; return y; }
        final String name = g.name;
        btn(px, y, 90, 14, "‹ All groups", () -> { group = null; scroll = 0; }, mx, my);
        if (visible(y, 14)) fontRenderer.drawStringWithShadow("§l" + g.prefix + (g.prefix.matches("(§.)*") || g.prefix.endsWith(" ") ? "" : " ") + "§r§f§l" + name + g.suffix, px + 100, y + 3, 0xFFFFFF);
        y += 22;

        y = heading(y, "§fLook §8(how the rank shows in chat and the tab list)");
        y = row(y, "§7Prefix", g.prefix.isEmpty() ? "§8none" : g.prefix + "§r§fName", "Style…", () -> mc.displayGuiScreen(new GuiStyle(this, "Prefix for " + name,
                g.prefix.replace('§', '&'), "Name", false, t -> send("group " + name + " prefix " + t))), mx, my);
        y = row(y, "§7Suffix", g.suffix.isEmpty() ? "§8none" : "§fName" + g.suffix, "Style…", () -> mc.displayGuiScreen(new GuiStyle(this, "Suffix for " + name,
                g.suffix.replace('§', '&'), "Name", true, t -> send("group " + name + " suffix " + t))), mx, my);
        y = stepper(y, "§7Priority §8(higher wins when groups disagree)", String.valueOf(g.priority),
                () -> send("group " + name + " priority " + (g.priority - 1)), () -> send("group " + name + " priority " + (g.priority + 1)), mx, my);
        y += 4;

        y = heading(y, "§fInherits from §8(gets everything those groups have)");
        List<String> labels = new ArrayList<>(); List<Boolean> on = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
        for (String o : rules().groupNames()) {
            if (o.equals(name)) continue;
            boolean in = g.parents.contains(o);
            labels.add(colorOf(o) + o); on.add(in);
            acts.add(() -> send("group " + name + " parent " + (in ? "remove " : "add ") + o));
        }
        y = chips(y, labels, on, acts, mx, my);

        List<String> members = membersOf(name);
        y = heading(y, "§fMembers §8(" + members.size() + ")");
        for (String m : members) {
            if (visible(y, 14)) {
                PermStore.Player p = playerData(m);
                Long until = p == null ? null : p.tempGroups.get(name);
                fontRenderer.drawStringWithShadow((isOnline(m) ? "§a● " : "§8○ ") + "§f" + m + (until != null ? " §b⏱ " + left(until) : ""), px, y + 3, 0xFFFFFF);
                btn(px + pw - 150, y, 66, 14, "Open", () -> { page = "players"; player = m; scroll = 0; }, mx, my);
                btn(px + pw - 80, y, 66, 14, "§cRemove", () -> send(until != null ? "user " + m + " addtemp " + name + " 1s" : "user " + m + " remove " + name), mx, my);
            }
            y += 16;
        }
        y = row(y, "§7Add a player to " + name, "", "§a+ Add", () -> ask("Player name", "", s -> send("user " + s.trim() + " add " + name)), mx, my);
        y = row(y, "§7Add for a time", "", "§b⏱ Add", () -> ask("Player and time, e.g.  Steve 7d", "", s -> {
            String[] w = s.trim().split("\\s+"); if (w.length == 2) send("user " + w[0] + " addtemp " + name + " " + w[1]); }), mx, my);
        y += 4;

        y = heading(y, "§fSell it §8(/rank buy — in-game Pride Realms coins only)");
        y = meta(y, g, "price", "§7Price in coins", "not for sale", "Price in coins (empty = not for sale)", mx, my);
        y = meta(y, g, "duration", "§7Rental time", "forever", "How long a bought rank lasts, e.g. 30d (empty = forever)", mx, my);
        y = meta(y, g, "requires", "§7Must already have", "nothing", "Rank a buyer must already have (empty = none)", mx, my);
        y = heading(y, "§fAuto rank-up §8(put the group on a track too)");
        y = meta(y, g, "hours", "§7After this many hours played", "off", "Hours of playtime to reach this rank (empty = off)", mx, my);
        y += 4;

        y = heading(y, "§fRules §8(" + g.nodes.size() + " normal · " + g.timed.size() + " special)");
        int shown = 0;
        for (Map.Entry<String, Boolean> e : g.nodes.entrySet()) {
            if (shown++ >= 12) break;
            if (visible(y, 12)) {
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth((e.getValue() ? "§a✔ " : "§c✖ ") + "§f" + e.getKey(), pw - 90), px, y + 2, 0xFFFFFF);
                final String node = e.getKey();
                btn(px + pw - 76, y, 66, 12, "§cRemove", () -> send("group " + name + " unset " + node), mx, my);
            }
            y += 14;
        }
        if (g.nodes.size() > 12) y = text(y, "§8…and " + (g.nodes.size() - 12) + " more in the rule editor");
        for (PermStore.Rule rr : g.timed) {
            if (visible(y, 12)) {
                String when = (rr.land != null ? " §bon " + rr.land + " land" : "") + (rr.dim != PermStore.ANY_DIM ? " §bin world " + rr.dim : "") + (rr.until > 0 ? " §b" + left(rr.until) + " left" : "");
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth((rr.value ? "§a✔ " : "§c✖ ") + "§f" + rr.node + when, pw - 90), px, y + 2, 0xFFFFFF);
                final String node = rr.node;
                btn(px + pw - 76, y, 66, 12, "§cRemove", () -> send("group " + name + " unset " + node), mx, my);
            }
            y += 14;
        }
        y = row(y, "§7Add a rule by name", "", "§a+ Rule", () -> ask("permission allow|deny [7d] [land=own] [dim=-1]", "", s -> {
            String[] w = s.trim().split("\\s+", 3);
            if (w.length >= 2) send("group " + name + " set " + w[0] + " " + (w[1].equalsIgnoreCase("allow") || w[1].equals("true") ? "true" : "false") + (w.length > 2 ? " " + w[2] : ""));
        }), mx, my);
        y = row(y, "§7Every mod, block, item, mob and command switch", "", "Open…", () -> openRules(false, name), mx, my);
        y += 4;

        y = heading(y, "§fTools");
        int bw = (pw - 8 - 3 * 4) / 4;
        btn(px, y, bw, 15, "⧉ Clone…", () -> ask("Name for the copy of " + name, name + "_copy", s -> send("group " + name + " clone " + s.trim())), mx, my);
        if (!name.equals(PermStore.DEFAULT)) {
            btn(px + bw + 4, y, bw, 15, "✎ Rename…", () -> ask("New name for " + name, name, s -> { send("group " + name + " rename " + s.trim()); group = GuiPerms.clean(s.trim()); }), mx, my);
            btn(px + 2 * (bw + 4), y, bw, 15, "§c✖ Delete…", () -> ask("Type " + name + " to delete it", "", s -> { if (s.trim().equals(name)) { send("group " + name + " delete"); group = null; } }), mx, my);
        }
        btn(px + 3 * (bw + 4), y, bw, 15, "ℹ Info", () -> send("group " + name + " info"), mx, my);
        return y + 24;
    }

    private int meta(int y, PermStore.Group g, String key, String label, String empty, String question, int mx, int my) {
        String v = g.meta.getOrDefault(key, "");
        return row(y, label, v.isEmpty() ? "§8" + empty : v, "Change", () -> ask(question, v,
                t -> send("group " + g.name + (t.trim().isEmpty() ? " meta unset " + key : " meta set " + key + " " + t.trim()))), mx, my);
    }

    // ================================================================== PLAYERS
    private int players(int y, int mx, int my) {
        int listW = Math.max(120, pw / 4), dx = px + listW + 10, dw = pw - listW - 10;
        search.x = px; search.y = y; search.width = listW - 8;
        if (visible(y, 14)) {
            search.drawTextBox();
            if (search.getText().isEmpty() && !search.isFocused()) fontRenderer.drawString("§8search players…", px + 4, y + 3, 0xFFFFFF);
        }
        int ly = y + 18;
        String q = search.getText().toLowerCase(Locale.ROOT);
        List<String> names = allPlayers();
        names.sort((a, b) -> Boolean.compare(isOnline(b), isOnline(a)));
        for (String n : names) {
            if (!q.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (visible(ly, 13)) {
                boolean sel = n.equals(player), over = mx >= px && mx < px + listW - 8 && my >= ly && my < ly + 13;
                Gui.drawRect(px, ly, px + listW - 8, ly + 13, sel ? PrideFrame.TILE_ON : over ? 0xFF2A2140 : PrideFrame.TILE);
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth((isOnline(n) ? "§a● " : "§8○ ") + styled(n), listW - 14), px + 3, ly + 3, 0xFFFFFF);
                click(px, ly, listW - 8, 13, () -> player = n);
            }
            ly += 14;
        }
        btn(px, ly + 2, listW - 8, 14, "§a+ Someone offline…", () -> ask("Their Minecraft name", "", s -> { player = s.trim(); send("user " + player + " info"); }), mx, my);
        ly += 20;
        if (player == null) {
            if (visible(y, 12)) fontRenderer.drawStringWithShadow("§7Pick a player on the left.", dx, y + 3, 0xFFFFFF);
            return ly;
        }
        return Math.max(ly, playerDetail(y, dx, dw, mx, my));
    }

    /** the right half of the Players page (uses px/pw swapped in for its column) */
    private int playerDetail(int y, int dx, int dw, int mx, int my) {
        int opx = px, opw = pw;
        px = dx; pw = dw;
        try {
            final String name = player;
            PermStore.Player p = playerData(name);
            String[] v = verifiedRow(name);
            if (visible(y, 14)) fontRenderer.drawStringWithShadow("§l" + styled(name) + (isOnline(name) ? "  §a● online" : "  §8○ offline")
                    + (locked.contains(name) ? "  §5✪ locked" : v != null ? "  §d✔ verified" : ""), px, y + 3, 0xFFFFFF);
            y += 20;

            y = heading(y, "§fRanks");
            List<String> labels = new ArrayList<>(); List<Boolean> on = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
            for (String g : rules().groupNames()) {
                if (g.equals(PermStore.DEFAULT)) continue;
                boolean in = p != null && p.groups.contains(g);
                labels.add(colorOf(g) + g); on.add(in);
                acts.add(() -> send("user " + name + (in ? " remove " : " add ") + g));
            }
            y = chips(y, labels, on, acts, mx, my);
            if (p != null) for (Map.Entry<String, Long> e : p.tempGroups.entrySet()) {
                final String g = e.getKey();
                y = row(y, "§b⏱ " + colorOf(g) + g, left(e.getValue()) + " left", "§cEnd", () -> send("user " + name + " addtemp " + g + " 1s"), mx, my);
            }
            y = row(y, "§7Give a rank for a time", "", "§b⏱ Give", () -> ask("Rank and time, e.g.  vip 7d", "", s -> send("user " + name + " addtemp " + s.trim())), mx, my);
            for (Map.Entry<String, List<String>> t : rules().tracks.entrySet()) {
                final String tn = t.getKey();
                if (visible(y, 14)) {
                    fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§e⇅ " + tn + " §8" + String.join(" → ", t.getValue()), pw - 150), px, y + 3, 0xFFFFFF);
                    btn(px + pw - 146, y, 66, 14, "§a▲ Promote", () -> send("promote " + name + " " + tn), mx, my);
                    btn(px + pw - 76, y, 66, 14, "§c▼ Demote", () -> send("demote " + name + " " + tn), mx, my);
                }
                y += 16;
            }
            y += 4;

            y = heading(y, "§fName look");
            String nc = p == null ? "" : p.meta.getOrDefault("namecolor", "");
            if (visible(y, 14)) {
                fontRenderer.drawStringWithShadow("§7Name colour", px, y + 3, 0xFFFFFF);
                String codes = "0123456789abcdef";
                int sx = px + 74, sw = Math.min(14, (pw - 140) / 17);
                for (int i = 0; i < 16; i++) {
                    int bx = sx + i * (sw + 2);
                    char c = codes.charAt(i);
                    boolean cur = nc.equals("§" + c);
                    Gui.drawRect(bx - 1, y - 1, bx + sw + 1, y + 13, cur ? 0xFFFFFFFF : 0xFF3A3050);
                    Gui.drawRect(bx, y, bx + sw, y + 12, 0xFF000000 | fontRenderer.getColorCode(c));
                    click(bx, y, sw, 12, () -> send("user " + name + " meta set namecolor §" + c));
                }
                btn(sx + 16 * (sw + 2) + 4, y, 44, 12, "Reset", () -> send("user " + name + " meta unset namecolor"), mx, my);
            }
            y += 18;
            PermStore.Player pp = p;
            y = row(y, "§7Own prefix", pp == null || pp.prefix.isEmpty() ? "§8(from their rank)" : pp.prefix + "§r" + name, "Style…", () -> mc.displayGuiScreen(new GuiStyle(this,
                    "Prefix for " + name, pp == null ? "" : pp.prefix.replace('§', '&'), name, false, t -> send("user " + name + " prefix " + t))), mx, my);
            y = row(y, "§7Own suffix", pp == null || pp.suffix.isEmpty() ? "§8(from their rank)" : name + pp.suffix, "Style…", () -> mc.displayGuiScreen(new GuiStyle(this,
                    "Suffix for " + name, pp == null ? "" : pp.suffix.replace('§', '&'), name, true, t -> send("user " + name + " suffix " + t))), mx, my);
            y += 4;

            y = heading(y, "§fJoin gate");
            if (v != null) {
                y = row(y, "§d✔ Verified", "site account " + v[2] + " · " + ago(Long.parseLong(v[3])), "§cRevoke", () -> send("verify revoke " + name), mx, my);
            } else {
                y = row(y, "§7Not verified", locked.contains(name) ? "§5locked right now" : "", "§aVerify", () -> send("verify give " + name), mx, my);
                y = row(y, "§7Hand them a one-time code", "", "Make code", () -> send("verify code " + name), mx, my);
            }
            y += 4;

            y = heading(y, "§fOwn rules §8(beat their ranks)");
            if (p != null) {
                for (Map.Entry<String, Boolean> e : p.nodes.entrySet()) {
                    final String node = e.getKey();
                    y = row(y, (e.getValue() ? "§a✔ " : "§c✖ ") + "§f" + node, "", "§cRemove", () -> send("user " + name + " unset " + node), mx, my);
                }
                for (PermStore.Rule rr : p.timed) {
                    final String node = rr.node;
                    String when = (rr.land != null ? "on " + rr.land + " land " : "") + (rr.dim != PermStore.ANY_DIM ? "in world " + rr.dim + " " : "") + (rr.until > 0 ? left(rr.until) + " left" : "");
                    y = row(y, (rr.value ? "§a✔ " : "§c✖ ") + "§f" + node, "§b" + when, "§cRemove", () -> send("user " + name + " unset " + node), mx, my);
                }
            }
            y = row(y, "§7Add a rule by name", "", "§a+ Rule", () -> ask("permission allow|deny [7d] [land=own] [dim=-1]", "", s -> {
                String[] w = s.trim().split("\\s+", 3);
                if (w.length >= 2) send("user " + name + " set " + w[0] + " " + (w[1].equalsIgnoreCase("allow") || w[1].equals("true") ? "true" : "false") + (w.length > 2 ? " " + w[2] : ""));
            }), mx, my);
            y = row(y, "§7Every mod, block, item, mob and command switch", "", "Open…", () -> openRules(true, name), mx, my);
            y = row(y, "§7Can they…? (checker)", "", "Check…", () -> { page = "checker"; whoBox.setText(name); scroll = 0; }, mx, my);
            y = row(y, "§7Everything about them", "", "ℹ Info", () -> send("user " + name + " info"), mx, my);
            return y + 6;
        } finally {
            px = opx; pw = opw;
        }
    }

    // ================================================================== RULE EDITOR (the per-mod switch board)
    private int rulesPage(int y, int mx, int my) {
        y = text(y, "§7The rule editor has a switch for every mod, block, item, mob and command in the pack — "
                + "§a✔ allow§7, §c✖ deny§7 or §8· not set§7 — for a group or one player. Pick who to edit:");
        y += 4;
        y = heading(y, "§fGroups");
        List<String> labels = new ArrayList<>(); List<Boolean> on = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
        for (String g : rules().groupNames()) { labels.add(colorOf(g) + g); on.add(false); acts.add(() -> openRules(false, g)); }
        y = chips(y, labels, on, acts, mx, my);
        y = heading(y, "§fPlayers online");
        labels = new ArrayList<>(); on = new ArrayList<>(); acts = new ArrayList<>();
        for (String[] o : online) { labels.add("§f" + o[0]); on.add(false); acts.add(() -> openRules(true, o[0])); }
        y = chips(y, labels, on, acts, mx, my);
        y = row(y, "§7Someone else", "", "Pick…", () -> ask("Player name", "", s -> openRules(true, s.trim())), mx, my);
        return y;
    }

    private void openRules(boolean players, String who) { mc.displayGuiScreen(GuiPerms.forSubject(players, who, this)); }

    // ================================================================== TRACKS
    private int tracks(int y, int mx, int my) {
        y = text(y, "§7A track is a ladder of ranks, lowest first. §f/perms promote <player> <track>§7 moves them up one step. "
                + "Groups with an §bauto rank-up§7 time on a track promote players by themselves.");
        btn(px, y, 120, 15, "§a+ New track", () -> ask("Name then ranks lowest first, e.g.  staff helper moderator admin", "", s -> {
            String[] w = s.trim().split("\\s+", 2); if (w.length == 2) send("track " + w[0] + " set " + w[1]); }), mx, my);
        y += 22;
        for (Map.Entry<String, List<String>> t : rules().tracks.entrySet()) {
            final String tn = t.getKey();
            List<String> steps = t.getValue();
            int h = 30 + steps.size() * 16;
            if (visible(y, h)) PrideFrame.card(px, y, pw - 8, h, PrideFrame.RAINBOW[3]);
            if (visible(y, 14)) {
                fontRenderer.drawStringWithShadow("§e§l⇅ " + tn, px + 6, y + 6, 0xFFFFFF);
                btn(px + pw - 150, y + 3, 66, 13, "§a+ Step", () -> ask("Rank to add at the top of " + tn, "", s -> send("track " + tn + " add " + s.trim())), mx, my);
                btn(px + pw - 80, y + 3, 66, 13, "§cDelete", () -> ask("Type " + tn + " to delete the track", "", s -> { if (s.trim().equals(tn)) send("track " + tn + " delete"); }), mx, my);
            }
            int sy = y + 22;
            for (int i = 0; i < steps.size(); i++) {
                final String g = steps.get(i);
                if (visible(sy, 14)) {
                    fontRenderer.drawStringWithShadow("§8" + (i + 1) + ". " + colorOf(g) + g + " §8(" + membersOf(g).size() + ")", px + 12, sy + 3, 0xFFFFFF);
                    int bx = px + pw - 150;
                    btn(bx, sy, 20, 13, "▲", () -> send("track " + tn + " up " + g), mx, my);
                    btn(bx + 24, sy, 20, 13, "▼", () -> send("track " + tn + " down " + g), mx, my);
                    btn(bx + 48, sy, 20, 13, "§c✖", () -> send("track " + tn + " remove " + g), mx, my);
                }
                sy += 16;
            }
            y += h + 8;
        }
        if (rules().tracks.isEmpty()) y = text(y, "§8No tracks yet.");
        return y;
    }

    // ================================================================== RANK SHOP
    private int shop(int y, int mx, int my) {
        y = text(y, "§7Players buy these with §fin-game Pride Realms coins§7 (never real money): §f/rank list§7, §f/rank buy <rank>§7. "
                + "A rental ends by itself. \"Needs\" = they must already have that rank.");
        y += 4;
        for (String gname : rules().groupNames()) {
            PermStore.Group g = rules().groups.get(gname);
            boolean sale = g.meta.containsKey("price");
            int h = sale ? 66 : 22;
            if (visible(y, h)) PrideFrame.card(px, y, pw - 8, h, sale ? 0xFFFFED00 : 0xFF3A3050);
            if (visible(y, 14)) {
                fontRenderer.drawStringWithShadow(colorOf(gname) + gname + (sale ? "  §e$" + g.meta.get("price") : "  §8not for sale"), px + 6, y + 6, 0xFFFFFF);
                btn(px + pw - 110, y + 3, 96, 14, sale ? "§cStop selling" : "§a$ Sell it…", () -> {
                    if (sale) send("group " + gname + " meta unset price");
                    else ask("Price in coins for " + gname, "1000", s -> { if (!s.trim().isEmpty()) send("group " + gname + " meta set price " + s.trim()); });
                }, mx, my);
            }
            if (sale) {
                int opx = px, opw = pw;
                px = opx + 8; pw = opw - 16;
                int ry = y + 22;
                ry = meta(ry, g, "price", "§7Price", "—", "Price in coins", mx, my);
                ry = meta(ry, g, "duration", "§7Rental", "forever", "How long it lasts, e.g. 30d (empty = forever)", mx, my);
                meta(ry, g, "requires", "§7Needs", "nothing", "Rank they must already have (empty = none)", mx, my);
                px = opx; pw = opw;
            }
            y += h + 6;
        }
        return y;
    }

    // ================================================================== JOIN GATE
    private int gatePage(int y, int mx, int my) {
        boolean on = cfgOn("gateEnabled");
        int per = pw >= 480 ? 4 : 2, w = (pw - 8 - (per - 1) * 6) / per;
        tile(px, y, w, on ? "§aON" : "§8OFF", "join gate — click", on ? 0xFF1F7A3A : 0xFF8A1F2A, () -> send("verify " + (on ? "off" : "on")), mx, my);
        tile(px + (1 % per) * (w + 6), y + (1 / per) * 40, w, String.valueOf(verified.size()), "verified", PrideFrame.RAINBOW[5], null, mx, my);
        tile(px + (2 % per) * (w + 6), y + (2 / per) * 40, w, String.valueOf(codesWaiting), "codes waiting", PrideFrame.RAINBOW[6], null, mx, my);
        tile(px + (3 % per) * (w + 6), y + (3 / per) * 40, w, String.valueOf(locked.size()), "locked now", PrideFrame.RAINBOW[7], null, mx, my);
        y += (4 / per) * 40 + 2;
        if (!on) y = text(y, "§e⚠ Only turn the gate on once the website hands out codes — otherwise everyone joining is locked. "
                + "You can always hand codes out by hand below, or verify someone directly.");
        if (!locked.isEmpty()) y = text(y, "§5✪ Locked now: §f" + String.join(", ", locked));
        y += 4;

        y = heading(y, "§fThe gate");
        y = row(y, "§7Website page for codes", cfg("gateSiteUrl").isEmpty() ? "§8not set" : cfg("gateSiteUrl"), "Change",
                () -> ask("Website address players get codes from", cfg("gateSiteUrl"), s -> setCfg("gateSiteUrl", s.trim())), mx, my);
        y = stepper(y, "§7Look-around distance while locked §8(0 = frozen)", cfg("gateGuestRadius"),
                () -> setCfg("gateGuestRadius", String.valueOf(Math.max(0, num("gateGuestRadius") - 2))), () -> setCfg("gateGuestRadius", String.valueOf(num("gateGuestRadius") + 2)), mx, my);
        y = stepper(y, "§7Minutes a code works", cfg("gateCodeMinutes"),
                () -> setCfg("gateCodeMinutes", String.valueOf((int) Math.max(1, num("gateCodeMinutes") - 5))), () -> setCfg("gateCodeMinutes", String.valueOf((int) num("gateCodeMinutes") + 5)), mx, my);
        y = row(y, "§7Where locked players wait", spotSet ? "§aset" : "§8where they joined", "Here", () -> send("verify setspawn"), mx, my);
        y = toggle(y, "Staff 2-step", "an op joining from a new internet address must verify again", cfgOn("gateStaffIpCheck"), () -> setCfg("gateStaffIpCheck", String.valueOf(!cfgOn("gateStaffIpCheck"))), mx, my);
        y = stepper(y, "§7Minecraft accounts per website account §8(0 = any)", cfg("gateMaxPerSiteAccount"),
                () -> setCfg("gateMaxPerSiteAccount", String.valueOf((int) Math.max(0, num("gateMaxPerSiteAccount") - 1))), () -> setCfg("gateMaxPerSiteAccount", String.valueOf((int) num("gateMaxPerSiteAccount") + 1)), mx, my);
        y = toggle(y, "Never lock the world owner", "single-player / LAN host; turn off only to try the gate yourself", cfgOn("gateExemptOwner"), () -> setCfg("gateExemptOwner", String.valueOf(!cfgOn("gateExemptOwner"))), mx, my);
        y = row(y, "§7Commands a locked player may use", String.join(", ", cfgList("gateAllowedCommands")), "Change",
                () -> ask("Commands, separated by spaces", String.join(" ", cfgList("gateAllowedCommands")), s -> setCfgList("gateAllowedCommands", Arrays.asList(s.trim().split("\\s+")))), mx, my);
        y += 4;

        y = heading(y, "§fWelcome §8(given once, the first time)");
        y = row(y, "§7Rank they get", cfg("gateVerifiedGroup").isEmpty() ? "§8none" : colorOf(cfg("gateVerifiedGroup")) + cfg("gateVerifiedGroup"), "Change",
                () -> ask("Rank to give when someone verifies (empty = none)", cfg("gateVerifiedGroup"), s -> setCfg("gateVerifiedGroup", s.trim())), mx, my);
        y = row(y, "§7Welcome coins", cfg("gateWelcomeCoins"), "Change", () -> ask("In-game coins (0 = none)", cfg("gateWelcomeCoins"), s -> setCfg("gateWelcomeCoins", s.trim())), mx, my);
        y = listEditor(y, "gateStarterKit", "§7Starter kit", "Item and amount, e.g.  minecraft:bread 16", mx, my);
        y += 4;

        y = heading(y, "§fRules quiz §8(after the code; empty = no quiz)");
        y = listEditor(y, "gateQuiz", "§7Question", "Question | answer   (several answers: yes/yeah)", mx, my);
        y += 4;

        y = heading(y, "§fVerified players §8(" + verified.size() + ")");
        y = row(y, "§7Hand someone a one-time code", "", "Make code", () -> ask("Their Minecraft name", "", s -> send("verify code " + s.trim())), mx, my);
        y = row(y, "§7Verify someone online right now", "", "§aVerify…", () -> ask("Their Minecraft name", "", s -> send("verify give " + s.trim())), mx, my);
        y = row(y, "§7Verify everyone online", "", "§aAll", () -> send("verify giveall"), mx, my);
        for (String[] v : verified) {
            final String n = v[1];
            y = row(y, "§f" + n, "§7" + v[2] + " · " + ago(Long.parseLong(v[3])), "§cRevoke", () -> send("verify revoke " + n), mx, my);
        }
        return y;
    }

    private double num(String k) { try { return Double.parseDouble(cfg(k)); } catch (NumberFormatException e) { return 0; } }

    /** a config list: one row per line with edit / remove, and an Add button */
    private int listEditor(int y, String key, String label, String question, int mx, int my) {
        List<String> lines = cfgList(key);
        for (int i = 0; i < lines.size(); i++) {
            final int at = i;
            if (visible(y, 14)) {
                String t = label + " " + (i + 1) + "§7: §f" + lines.get(i);
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(t, pw - 90), px, y + 3, 0xFFFFFF);
                if (fontRenderer.getStringWidth(t) > pw - 90 && mx >= px && mx < px + pw - 90 && my >= y && my < y + 14) hover = t;
                btn(px + pw - 76, y, 30, 14, "✎", () -> ask(question, lines.get(at), s -> { List<String> l = new ArrayList<>(lines); l.set(at, s.trim()); setCfgList(key, l); }), mx, my);
                btn(px + pw - 42, y, 30, 14, "§c✖", () -> { List<String> l = new ArrayList<>(lines); l.remove(at); setCfgList(key, l); }, mx, my);
            }
            y += 16;
        }
        return row(y, "§8" + (lines.isEmpty() ? "none yet" : ""), "", "§a+ Add", () -> ask(question, "", s -> {
            if (s.trim().isEmpty()) return; List<String> l = new ArrayList<>(lines); l.add(s.trim()); setCfgList(key, l); }), mx, my);
    }

    // ================================================================== CHECKER
    private int checker(int y, int mx, int my) {
        y = text(y, "§7Ask whether a player may do something and which rule decides it. Node names look like "
                + "§fmekanism.break.digital_miner§7, §fminecraft.attack.villager§7, §fcommand.tp§7, §fdimension.-1.enter§7.");
        if (visible(y, 14)) fontRenderer.drawStringWithShadow("§7Player", px, y + 3, 0xFFFFFF);
        whoBox.x = px + 50; whoBox.y = y; whoBox.width = 120;
        if (visible(y, 14)) whoBox.drawTextBox();
        y += 18;
        if (visible(y, 14)) fontRenderer.drawStringWithShadow("§7Node", px, y + 3, 0xFFFFFF);
        nodeBox.x = px + 50; nodeBox.y = y; nodeBox.width = pw - 140;
        if (visible(y, 14)) {
            nodeBox.drawTextBox();
            btn(px + pw - 82, y, 72, 14, "§a✔ Check", () -> { if (!nodeBox.getText().trim().isEmpty()) send("check " + whoBox.getText().trim() + " " + nodeBox.getText().trim()); }, mx, my);
        }
        y += 20;
        String q = nodeBox.getText().toLowerCase(Locale.ROOT).trim();
        if (!q.isEmpty()) {
            List<String> match = new ArrayList<>();
            for (String n : nodes) if (n.contains(q)) { match.add(n); if (match.size() >= 12) break; }
            if (!match.isEmpty()) {
                y = heading(y, "§fSuggestions §8(nodes seen in this world)");
                List<Boolean> on = new ArrayList<>(); List<Runnable> acts = new ArrayList<>();
                for (String n : match) { on.add(false); acts.add(() -> nodeBox.setText(n)); }
                y = chips(y, match, on, acts, mx, my);
            }
        }
        y = heading(y, "§fAnswer");
        boolean fresh = System.currentTimeMillis() - replyAt < 120000 && !reply.isEmpty();
        y = text(y, fresh ? reply.replace("\n", "\n") : "§8Nothing checked yet.");
        y += 6;
        y = heading(y, "§fWatch live");
        y = text(y, "§7Every permission question the server asks shows in your chat — useful to find a node name.");
        int bw = (pw - 8 - 8) / 3;
        btn(px, y, bw, 15, "◉ Watch all", () -> send("verbose on"), mx, my);
        btn(px + bw + 4, y, bw, 15, "◉ This player", () -> send("verbose on " + whoBox.getText().trim()), mx, my);
        btn(px + 2 * (bw + 4), y, bw, 15, "✖ Stop", () -> send("verbose off"), mx, my);
        return y + 22;
    }

    // ================================================================== CHANGE LOG
    private int logPage(int y, int mx, int my) {
        search.x = px; search.y = y; search.width = 200;
        if (visible(y, 14)) {
            search.drawTextBox();
            if (search.getText().isEmpty() && !search.isFocused()) fontRenderer.drawString("§8filter…", px + 4, y + 3, 0xFFFFFF);
        }
        btn(px + 210, y, 90, 14, "↻ Refresh", () -> send("log 1"), mx, my);
        y += 20;
        String q = search.getText().toLowerCase(Locale.ROOT);
        if (log.isEmpty()) y = text(y, "§8No changes logged yet.");
        for (String l : log) {
            if (!q.isEmpty() && !l.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (visible(y, 11)) {
                String t = l.length() > 21 ? "§8" + l.substring(5, 16) + "  §d" + l.substring(21).replaceFirst("  ", "  §7") : l;
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(t, pw - 10), px, y, 0xFFFFFF);
                if (fontRenderer.getStringWidth(t) > pw - 10 && mx >= px && mx < px + pw && my >= y && my < y + 10) hover = l;
            }
            y += 11;
        }
        return y;
    }

    // ================================================================== BACKUPS
    private int backupsPage(int y, int mx, int my) {
        y = text(y, "§7A backup is a copy of every rank, player and rule. Restoring one saves the current rules as a backup first, so nothing is ever lost.");
        btn(px, y, 130, 15, "§a⟲ Backup now", () -> ask("A short note for this backup (optional)", "manual", s -> send("backup " + (s.trim().isEmpty() ? "manual" : s.trim().replace(' ', '-')))), mx, my);
        btn(px + 134, y, 130, 15, "↻ Reload from file", () -> send("reload"), mx, my);
        y += 24;
        y = heading(y, "§fBackups §8(newest first, the last 40 are kept)");
        if (backups.isEmpty()) y = text(y, "§8None yet.");
        for (String b : backups) {
            String when = b.length() >= 15 ? b.substring(0, 4) + "-" + b.substring(4, 6) + "-" + b.substring(6, 8) + " " + b.substring(9, 11) + ":" + b.substring(11, 13) : b;
            String note = b.length() > 16 ? b.substring(16).replace(".json", "") : "";
            y = row(y, "§f" + when + " §7" + note, "", "Restore", () -> ask("Type RESTORE to bring back " + b, "", s -> { if (s.trim().equals("RESTORE")) send("restore " + b); }), mx, my);
        }
        y += 6;
        y = heading(y, "§fImport from another permissions plugin");
        y = text(y, "§7Put the file in the world folder, config/ or the server folder, then:");
        y = row(y, "§7LuckPerms export (.json or .json.gz)", "", "Import…", () -> ask("File name", "luckperms-export.json.gz", s -> send("import luckperms " + s.trim())), mx, my);
        y = row(y, "§7PermissionsEx (permissions.yml)", "", "Import…", () -> ask("File name", "permissions.yml", s -> send("import pex " + s.trim())), mx, my);
        return y;
    }

    // ================================================================== SETTINGS
    private int settings(int y, int mx, int my) {
        y = heading(y, "§fHow permissions are decided");
        y = toggle(y, "Ops are admins", "server operators count as the admin group (admin has everything unless a rule says otherwise)", cfgOn("opsAreAdmin"), () -> setCfg("opsAreAdmin", String.valueOf(!cfgOn("opsAreAdmin"))), mx, my);
        y = toggle(y, "Answer Forge's permission system", "mods that ask Forge (Ender IO, Vampirism, Custom NPCs, FVTM…) use these ranks — restart to change", cfgOn("forgeHandler"), () -> setCfg("forgeHandler", String.valueOf(!cfgOn("forgeHandler"))), mx, my);
        y = toggle(y, "Check machines too", "quarries, deployers and other fake players follow the rules (most packs leave this off)", cfgOn("checkFakePlayers"), () -> setCfg("checkFakePlayers", String.valueOf(!cfgOn("checkFakePlayers"))), mx, my);
        y = toggle(y, "Ranks can GIVE commands", "command.<name> = allow lets a rank use a command without being op", cfgOn("grantCommands"), () -> setCfg("grantCommands", String.valueOf(!cfgOn("grantCommands"))), mx, my);
        y += 4;
        y = heading(y, "§fChat");
        y = toggle(y, "Rank prefixes in chat", "show each player's top rank prefix (and suffix) around their name", cfgOn("namePrefixes"), () -> setCfg("namePrefixes", String.valueOf(!cfgOn("namePrefixes"))), mx, my);
        y += 4;
        y = heading(y, "§fAll settings §8(config/prideperms.cfg)");
        for (Map.Entry<String, String> e : config.entrySet()) {
            final String k = e.getKey();
            y = row(y, "§7" + k, e.getValue(), "Change", () -> ask(k + (e.getValue().contains(";;") ? "  (lines split by ;;)" : ""), e.getValue(), s -> setCfg(k, s.trim())), mx, my);
        }
        return y;
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        boolean textPage = page.equals("players") || page.equals("log") || page.equals("checker");
        if (textPage) {
            if (page.equals("checker")) { whoBox.mouseClicked(mx, my, button); nodeBox.mouseClicked(mx, my, button); }
            else search.mouseClicked(mx, my, button);
        }
        if (button != 0) return;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            boolean inRail = b[0] < px;
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3] && (inRail || (my >= top && my < bottom))) {
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1f));
                clicks.get(i).run();
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (page.equals("checker")) {
            if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && !nodeBox.getText().trim().isEmpty()) {
                send("check " + whoBox.getText().trim() + " " + nodeBox.getText().trim());
                return;
            }
            if (whoBox.textboxKeyTyped(c, key) || nodeBox.textboxKeyTyped(c, key)) return;
        } else if ((page.equals("players") || page.equals("log")) && search.textboxKeyTyped(c, key)) { scroll = 0; return; }
        if (key == Keyboard.KEY_ESCAPE && page.equals("groups") && group != null) { group = null; scroll = 0; return; }
        super.keyTyped(c, key);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Integer.signum(Mouse.getEventDWheel());
        if (d == 0) return;
        int max = Math.max(0, contentH - (bottom - top) + 10);
        scroll = Math.max(0, Math.min(max, scroll - d * 24));
    }

    @Override
    public void updateScreen() { search.updateCursorCounter(); nodeBox.updateCursorCounter(); whoBox.updateCursorCounter(); }

    static String when(long at) { return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(at)); }
}
