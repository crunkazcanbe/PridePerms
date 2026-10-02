package com.dogpound.prideperms.client;

import com.dogpound.prideperms.Net;
import com.dogpound.prideperms.PermStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * /perms menu — the whole permission system on one centred Pride panel.
 *   left:   Groups | Players (pick who you're editing)
 *   middle: every mod (search box), plus "Settings" (priority, parents / a player's groups) and "Commands"
 *   right:  the picked mod: whole-mod and per-action switches, then every block, item and mob with its actions.
 * Each chip cycles  · not set → ✔ allow → ✖ deny → · not set. A faint mark on "not set" shows what's inherited.
 * Every click is sent to the server as the /perms words, so it obeys the same checks as typing them.
 */
public class GuiPerms extends GuiScreen {
    // ------------------------------------------------------------------ data from the server
    static PermStore rules = new PermStore();
    static List<String> commands = new ArrayList<>();

    static void accept(String json) {
        try {
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            rules = PermStore.fromJson(o.get("store").toString());
            List<String> c = new ArrayList<>();
            JsonArray a = o.getAsJsonArray("commands");
            if (a != null) for (JsonElement e : a) c.add(e.getAsString());
            commands = c;
        } catch (Exception e) {
            rules = new PermStore();
        }
    }

    // ------------------------------------------------------------------ the catalogue (built from the game's registries)
    static final class Thing {
        final String label, path;
        final String[] actions;
        final ItemStack icon;
        Thing(String label, String path, String[] actions, ItemStack icon) { this.label = label; this.path = path; this.actions = actions; this.icon = icon; }
    }

    static final class ModCat {
        final String id, name;
        final List<Thing> blocks = new ArrayList<>(), items = new ArrayList<>(), mobs = new ArrayList<>();
        ModCat(String id, String name) { this.id = id; this.name = name; }
        int size() { return blocks.size() + items.size() + mobs.size(); }
    }

    static final String[] BLOCK_ACTIONS = {"break", "place", "use"}, ITEM_ACTIONS = {"item", "pickup", "drop"}, MOB_ACTIONS = {"attack", "interact", "ride"};
    static final String SETTINGS = "§d⚙ Settings", COMMANDS = "§b⌨ Commands", TRACKS = "§e⇅ Tracks";
    private static List<ModCat> catalogue;

    static List<ModCat> catalogue() {
        if (catalogue != null) return catalogue;
        Map<String, ModCat> m = new TreeMap<>();
        for (ModContainer mc : Loader.instance().getActiveModList()) m.put(mc.getModId(), new ModCat(mc.getModId(), mc.getName()));
        m.computeIfAbsent("minecraft", k -> new ModCat("minecraft", "Minecraft"));
        for (Block b : ForgeRegistries.BLOCKS) {
            ResourceLocation r = b.getRegistryName();
            if (r == null) continue;
            ItemStack icon = new ItemStack(Item.getItemFromBlock(b));
            String label = icon.isEmpty() ? r.getResourcePath() : safeName(icon, r.getResourcePath());
            cat(m, r).blocks.add(new Thing(label, clean(r.getResourcePath()), BLOCK_ACTIONS, icon));
        }
        for (Item i : ForgeRegistries.ITEMS) {
            ResourceLocation r = i.getRegistryName();
            if (r == null || i instanceof ItemBlock) continue;               // block items are covered by the block
            ItemStack icon = new ItemStack(i);
            cat(m, r).items.add(new Thing(safeName(icon, r.getResourcePath()), clean(r.getResourcePath()), ITEM_ACTIONS, icon));
        }
        for (EntityEntry e : ForgeRegistries.ENTITIES) {
            ResourceLocation r = e.getRegistryName();
            if (r == null) continue;
            String key = EntityList.getTranslationName(r);
            String label = key == null ? r.getResourcePath() : I18n.format("entity." + key + ".name");
            if (label.startsWith("entity.")) label = r.getResourcePath();
            cat(m, r).mobs.add(new Thing(label, clean(r.getResourcePath()), MOB_ACTIONS, ItemStack.EMPTY));
        }
        List<ModCat> out = new ArrayList<>();
        for (ModCat c : m.values()) if (c.size() > 0) out.add(c);
        for (ModCat c : out) { c.blocks.sort((a, b) -> a.label.compareToIgnoreCase(b.label)); c.items.sort((a, b) -> a.label.compareToIgnoreCase(b.label)); c.mobs.sort((a, b) -> a.label.compareToIgnoreCase(b.label)); }
        return catalogue = out;
    }

    private static ModCat cat(Map<String, ModCat> m, ResourceLocation r) {
        return m.computeIfAbsent(r.getResourceDomain(), k -> new ModCat(k, k));
    }

    private static String safeName(ItemStack s, String fallback) {
        try { String n = s.getDisplayName(); return n == null || n.isEmpty() ? fallback : n; } catch (Throwable t) { return fallback; }
    }

    static String clean(String s) { return s.toLowerCase(Locale.ROOT).replace('.', '_').replace(' ', '_'); }

    // ------------------------------------------------------------------ screen state
    private boolean playersTab;
    private String subject;                      // group name, or player name
    private String subjectUuid;                  // players only (null = not in the rules yet)
    private String mod = SETTINGS;
    private int scrollL, scrollM, scrollR;
    private GuiTextField search;
    private PrideFrame f;
    private int colL, colM, colR, top, bottom;   // column x positions / list area
    private final List<Runnable> clicks = new ArrayList<>();
    private final List<int[]> clickBoxes = new ArrayList<>();
    private String hover;

    private GuiScreen back;                      // the Studio, when opened from it (Esc returns there)

    public GuiPerms() {
        subject = rules.groups.containsKey("member") ? "member" : PermStore.DEFAULT;
    }

    /** open straight on one group or player; Esc goes back to `back` */
    static GuiPerms forSubject(boolean players, String who, GuiScreen back) {
        GuiPerms g = new GuiPerms();
        g.playersTab = players;
        g.subject = who;
        g.back = back;
        return g;
    }

    void refresh() { if (playersTab && subjectUuid == null) findUuid(); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.fit(width, height);
        colL = f.cx;
        colM = f.cx + Math.max(120, f.cw / 6) + 8;
        colR = colM + Math.max(140, f.cw / 5) + 8;
        top = f.cy + 18;
        bottom = f.cy + f.ch;
        String old = search == null ? "" : search.getText();
        search = new GuiTextField(0, fontRenderer, colM, f.cy, colR - colM - 8, 12);
        search.setMaxStringLength(40);
        search.setText(old);
        catalogue();
        if (playersTab && subjectUuid == null) findUuid();
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public boolean doesGuiPauseGame() { return false; }

    // ------------------------------------------------------------------ drawing
    @Override
    public void drawScreen(int mx, int my, float pt) {
        clicks.clear(); clickBoxes.clear(); hover = null;
        String who = playersTab ? "player " + subject : "group " + subject;
        f.draw(this, "Rule editor", "§7editing §f" + who + (back != null ? "  §8· Esc = back to the Studio" : ""));
        drawLeft(mx, my);
        drawMiddle(mx, my);
        drawRight(mx, my);
        if (hover != null) drawHoveringText(java.util.Collections.singletonList(hover), mx, my);
    }

    private void drawLeft(int mx, int my) {
        int w = colM - colL - 8;
        boolean onG = PrideFrame.button(colL, f.cy, w / 2 - 1, 14, playersTab ? "§7Groups" : "§fGroups", playersTab ? PrideFrame.BUTTON : PrideFrame.TILE_ON, mx, my);
        click(colL, f.cy, w / 2 - 1, 14, () -> { playersTab = false; subject = PermStore.DEFAULT; subjectUuid = null; scrollL = 0; });
        PrideFrame.button(colL + w / 2 + 1, f.cy, w / 2 - 1, 14, playersTab ? "§fPlayers" : "§7Players", playersTab ? PrideFrame.TILE_ON : PrideFrame.BUTTON, mx, my);
        click(colL + w / 2 + 1, f.cy, w / 2 - 1, 14, () -> { playersTab = true; List<String> ps = players(); if (!ps.isEmpty()) { subject = ps.get(0); findUuid(); } scrollL = 0; });
        List<String> names = playersTab ? players() : rules.groupNames();
        int y = top + 2, rowH = 14, bottomList = bottom - (playersTab ? 0 : 36);
        PrideFrame.clip(colL, top, w, bottomList - top);
        for (int i = scrollL; i < names.size() && y < bottomList; i++, y += rowH) {
            String n = names.get(i);
            boolean sel = n.equals(subject);
            boolean over = mx >= colL && mx < colL + w && my >= y && my < y + rowH - 1 && my < bottomList;
            Gui.drawRect(colL, y, colL + w, y + rowH - 1, sel ? PrideFrame.TILE_ON : over ? 0xFF2A2140 : PrideFrame.TILE);
            String label = playersTab ? n : colorOf(n) + n + " §7" + rules.groups.get(n).priority;
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, w - 6), colL + 4, y + 3, 0xFFFFFF);
            final String pick = n;
            click(colL, y, w, rowH - 1, () -> { subject = pick; subjectUuid = null; if (playersTab) findUuid(); });
        }
        PrideFrame.unclip();
        PrideFrame.scrollbar(colL + w - 3, top, bottomList - top, scrollL, (bottomList - top) / rowH, names.size());
        if (!playersTab) {                                            // new / delete group
            PrideFrame.button(colL, bottom - 32, w, 14, "§a+ New group", PrideFrame.BUTTON, mx, my);
            click(colL, bottom - 32, w, 14, () -> mc.displayGuiScreen(new GuiAsk(this, "Name of the new group", "", s -> {
                String n = clean(s.trim());
                if (!n.isEmpty()) { send("group " + n + " create"); subject = n; }
            })));
            boolean canDelete = !PermStore.DEFAULT.equals(subject);
            PrideFrame.button(colL, bottom - 16, w, 14, canDelete ? "§c✖ Delete " + subject : "§8(can't delete default)", PrideFrame.BUTTON, mx, my);
            if (canDelete) click(colL, bottom - 16, w, 14, () -> mc.displayGuiScreen(new GuiAsk(this, "Type " + subject + " to delete it", "", s -> {
                if (s.trim().equals(subject)) { send("group " + subject + " delete"); subject = PermStore.DEFAULT; }
            })));
        }
    }

    private void drawMiddle(int mx, int my) {
        int w = colR - colM - 8;
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused()) fontRenderer.drawString("§8search mods, blocks, items…", colM + 4, f.cy + 2, 0xFFFFFF);
        List<String[]> rows = new ArrayList<>();                       // {key, label}
        rows.add(new String[]{SETTINGS, SETTINGS});
        rows.add(new String[]{COMMANDS, COMMANDS + " §7" + commands.size()});
        rows.add(new String[]{TRACKS, TRACKS + " §7" + rules.tracks.size()});
        String q = search.getText().toLowerCase(Locale.ROOT);
        for (ModCat c : catalogue()) {
            if (!q.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(q) && !c.id.contains(q) && !hasMatch(c, q)) continue;
            int set = countRules(c.id + ".");
            rows.add(new String[]{c.id, c.name + (set > 0 ? " §d" + set : "")});
        }
        int y = top + 2, rowH = 13;
        PrideFrame.clip(colM, top, w, bottom - top);
        for (int i = scrollM; i < rows.size() && y < bottom; i++, y += rowH) {
            String[] r = rows.get(i);
            boolean sel = r[0].equals(mod);
            boolean over = mx >= colM && mx < colM + w && my >= y && my < y + rowH - 1;
            Gui.drawRect(colM, y, colM + w, y + rowH - 1, sel ? PrideFrame.TILE_ON : over ? 0xFF2A2140 : PrideFrame.TILE);
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(r[1], w - 6), colM + 4, y + 2, 0xFFFFFF);
            final String key = r[0];
            click(colM, y, w, rowH - 1, () -> { mod = key; scrollR = 0; });
        }
        PrideFrame.unclip();
        PrideFrame.scrollbar(colM + w - 3, top, bottom - top, scrollM, (bottom - top) / rowH, rows.size());
    }

    private void drawRight(int mx, int my) {
        int x = colR, w = f.cx + f.cw - colR, y = f.cy - scrollR * 14;
        PrideFrame.clip(x, f.cy, w, bottom - f.cy);
        if (SETTINGS.equals(mod)) y = drawSettings(x, y, w, mx, my);
        else if (COMMANDS.equals(mod)) y = drawCommands(x, y, w, mx, my);
        else if (TRACKS.equals(mod)) y = drawTracks(x, y, w, mx, my);
        else {
            ModCat c = null;
            for (ModCat k : catalogue()) if (k.id.equals(mod)) c = k;
            if (c != null) y = drawMod(c, x, y, w, mx, my);
        }
        PrideFrame.unclip();
        int total = (y + scrollR * 14) - f.cy;
        PrideFrame.scrollbar(x + w - 3, f.cy, bottom - f.cy, scrollR * 14, bottom - f.cy, Math.max(total, 1));
    }

    private int drawSettings(int x, int y, int w, int mx, int my) {
        y = heading(x, y, w, playersTab ? "§fGroups " + subject + " is in" : "§fGroup settings");
        if (playersTab) {
            PermStore.Player p = subjectUuid == null ? null : rules.players.get(subjectUuid);
            for (String g : rules.groupNames()) {
                if (g.equals(PermStore.DEFAULT)) continue;
                boolean in = p != null && p.groups.contains(g);
                if (inView(y)) {
                    PrideFrame.button(x, y, w - 8, 14, (in ? "§a✔ " : "§8· ") + colorOf(g) + g, in ? PrideFrame.TILE_ON : PrideFrame.BUTTON, mx, my);
                    click(x, y, w - 8, 14, () -> send("user " + subject + (in ? " remove " : " add ") + g));
                }
                y += 16;
            }
            y += 4;
            if (inView(y)) fontRenderer.drawStringWithShadow("§7Everyone is also in §fdefault§7. Ops count as §dadmin§7.", x, y, 0xFFFFFF);
            y += 16;
            y = heading(x, y, w, "§fTemporary ranks §8(end by themselves)");
            if (p != null) for (java.util.Map.Entry<String, Long> e : p.tempGroups.entrySet()) {
                if (inView(y)) {
                    fontRenderer.drawStringWithShadow("§b⏱ " + colorOf(e.getKey()) + e.getKey() + " §7" + left(e.getValue()) + " left", x, y + 3, 0xFFFFFF);
                    PrideFrame.button(x + w - 60, y, 52, 14, "§cEnd", PrideFrame.BUTTON, mx, my);
                    final String gname = e.getKey();
                    click(x + w - 60, y, 52, 14, () -> send("user " + subject + " addtemp " + gname + " 1s"));
                }
                y += 16;
            }
            y = settingRow(x, y, w, "§7Give a temporary rank", "", "Give", mx, my, () -> mc.displayGuiScreen(new GuiAsk(this,
                    "Rank and time, e.g.  vip 7d   or   moderator 2h30m", "", t -> send("user " + subject + " addtemp " + t.trim()))));
            y += 6;
            y = heading(x, y, w, "§fThis player's own name look");
            PermStore.Player pp = p;
            y = settingRow(x, y, w, "§7Prefix", pp == null || pp.prefix.isEmpty() ? "§8(from their group)" : pp.prefix + subject, "Change", mx, my,
                    () -> mc.displayGuiScreen(new GuiAsk(this, "Prefix (& colours; empty = use their group's)", pp == null ? "" : pp.prefix.replace('§', '&'), t -> send("user " + subject + " prefix " + t))));
            y = settingRow(x, y, w, "§7Suffix", pp == null || pp.suffix.isEmpty() ? "§8(from their group)" : subject + pp.suffix, "Change", mx, my,
                    () -> mc.displayGuiScreen(new GuiAsk(this, "Suffix (& colours; empty = use their group's)", pp == null ? "" : pp.suffix.replace('§', '&'), t -> send("user " + subject + " suffix " + t))));
            y += 6;
            return specialRules(x, y, w, "user", pp == null ? null : pp.timed, mx, my);
        }
        PermStore.Group g = rules.groups.get(subject);
        if (g == null) return y;
        if (inView(y)) {
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§7Priority §f" + g.priority + " §8(higher wins when groups disagree)", w - 68), x, y + 3, 0xFFFFFF);
            PrideFrame.button(x + w - 60, y, 24, 14, "−", PrideFrame.BUTTON, mx, my);
            click(x + w - 60, y, 24, 14, () -> send("group " + subject + " priority " + (g.priority - 1)));
            PrideFrame.button(x + w - 32, y, 24, 14, "+", PrideFrame.BUTTON, mx, my);
            click(x + w - 32, y, 24, 14, () -> send("group " + subject + " priority " + (g.priority + 1)));
        }
        y += 18;
        if (inView(y)) {
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§7Prefix: " + g.prefix + subject, w - 70), x, y + 3, 0xFFFFFF);
            PrideFrame.button(x + w - 60, y, 52, 14, "Change", PrideFrame.BUTTON, mx, my);
            click(x + w - 60, y, 52, 14, () -> mc.displayGuiScreen(new GuiAsk(this, "Prefix (use & for colours, e.g. &d[Mod] )", g.prefix.replace('§', '&'),
                    s -> send("group " + subject + " prefix " + s))));
        }
        y += 18;
        y = settingRow(x, y, w, "§7Suffix", g.suffix.isEmpty() ? "§8none" : subject + g.suffix, "Change", mx, my,
                () -> mc.displayGuiScreen(new GuiAsk(this, "Suffix (use & for colours)", g.suffix.replace('§', '&'), t -> send("group " + subject + " suffix " + t))));
        y += 4;
        y = heading(x, y, w, "§fSell this rank §8(/rank buy — Pride Realms coins)");
        y = metaRow(x, y, w, g, "price", "§7Price in coins", "not for sale", "Price in coins (empty = not for sale)", mx, my);
        y = metaRow(x, y, w, g, "duration", "§7Rental time", "forever", "How long a bought rank lasts, e.g. 30d (empty = forever)", mx, my);
        y = metaRow(x, y, w, g, "requires", "§7Must already have", "nothing", "Rank a buyer must already have (empty = none)", mx, my);
        y += 4;
        y = heading(x, y, w, "§fAuto rank-up §8(needs this group on a track)");
        y = metaRow(x, y, w, g, "hours", "§7After this many hours played", "off", "Hours of playtime to reach this rank (empty = off)", mx, my);
        y += 4;
        y = specialRules(x, y, w, "group", g.timed, mx, my);
        y += 4;
        y = heading(x, y, w, "§fInherits from §8(gets their permissions)");
        for (String o : rules.groupNames()) {
            if (o.equals(subject)) continue;
            boolean on = g.parents.contains(o);
            if (inView(y)) {
                PrideFrame.button(x, y, w - 8, 14, (on ? "§a✔ " : "§8· ") + colorOf(o) + o, on ? PrideFrame.TILE_ON : PrideFrame.BUTTON, mx, my);
                click(x, y, w - 8, 14, () -> send("group " + subject + " parent " + (on ? "remove " : "add ") + o));
            }
            y += 16;
        }
        y += 6;
        y = heading(x, y, w, "§fEverything this group sets §8(" + g.nodes.size() + ")");
        for (String node : new ArrayList<>(g.nodes.keySet())) {
            if (inView(y)) y = chipRow(x, y, w, node, node, mx, my);
            else y += 14;
        }
        return y;
    }

    private int drawCommands(int x, int y, int w, int mx, int my) {
        y = heading(x, y, w, "§fCommands §8(✔ gives it even without op · ✖ takes it away)");
        y = chipRow(x, y, w, "§dAll commands", "command.*", mx, my);
        String q = search.getText().toLowerCase(Locale.ROOT);
        for (String c : commands) {
            if (!q.isEmpty() && !c.contains(q)) continue;
            if (inView(y)) y = chipRow(x, y, w, "/" + c, "command." + clean(c), mx, my);
            else y += 14;
        }
        return y;
    }

    private int drawMod(ModCat c, int x, int y, int w, int mx, int my) {
        y = heading(x, y, w, "§f" + c.name + " §8" + c.id + " · " + c.size() + " things");
        y = chipRow(x, y, w, "§dThe whole mod", c.id + ".*", mx, my);
        if (!c.blocks.isEmpty()) y = actionRow(x, y, w, "§7Every block", c.id, BLOCK_ACTIONS, mx, my);
        if (!c.items.isEmpty()) y = actionRow(x, y, w, "§7Every item", c.id, ITEM_ACTIONS, mx, my);
        if (!c.mobs.isEmpty()) y = actionRow(x, y, w, "§7Every mob", c.id, MOB_ACTIONS, mx, my);
        y += 4;
        String q = search.getText().toLowerCase(Locale.ROOT);
        String[][] sections = {{"Blocks", "0"}, {"Items", "1"}, {"Mobs", "2"}};
        List<List<Thing>> lists = java.util.Arrays.asList(c.blocks, c.items, c.mobs);
        for (int s = 0; s < 3; s++) {
            if (lists.get(s).isEmpty()) continue;
            y = heading(x, y, w, "§f" + sections[s][0] + " §8" + lists.get(s).size());
            for (Thing t : lists.get(s)) {
                if (!q.isEmpty() && !t.label.toLowerCase(Locale.ROOT).contains(q) && !t.path.contains(q) && !c.name.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (!inView(y)) { y += 14; continue; }
                if (!t.icon.isEmpty()) {
                    net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
                    net.minecraft.client.renderer.GlStateManager.pushMatrix();
                    net.minecraft.client.renderer.GlStateManager.translate(x, y - 1, 0);
                    net.minecraft.client.renderer.GlStateManager.scale(0.75, 0.75, 1);
                    itemRender.renderItemAndEffectIntoGUI(t.icon, 0, 0);
                    net.minecraft.client.renderer.GlStateManager.popMatrix();
                    net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
                }
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(t.label, w - 30 - t.actions.length * 44), x + 14, y + 2, 0xFFFFFF);
                int cx = x + w - 8 - t.actions.length * 44;
                for (String a : t.actions) { chip(cx, y, 42, a, c.id + "." + a + "." + t.path, mx, my); cx += 44; }
                y += 14;
            }
            y += 4;
        }
        return y;
    }

    private int drawTracks(int x, int y, int w, int mx, int my) {
        y = heading(x, y, w, "§fTracks §8(promotion ladders, lowest first)");
        if (rules.tracks.isEmpty() && inView(y)) { fontRenderer.drawStringWithShadow("§7None yet — make one below.", x, y + 2, 0xFFFFFF); y += 14; }
        for (java.util.Map.Entry<String, List<String>> t : rules.tracks.entrySet()) {
            if (inView(y)) {
                StringBuilder sb = new StringBuilder("§e" + t.getKey() + "§7: ");
                for (int i = 0; i < t.getValue().size(); i++) sb.append(i == 0 ? "" : " §8→ ").append(colorOf(t.getValue().get(i))).append(t.getValue().get(i));
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(sb.toString(), w - (playersTab ? 150 : 70)), x, y + 3, 0xFFFFFF);
                final String tn = t.getKey();
                if (playersTab) {
                    PrideFrame.button(x + w - 146, y, 68, 14, "§aPromote", PrideFrame.BUTTON, mx, my);
                    click(x + w - 146, y, 68, 14, () -> send("promote " + subject + " " + tn));
                    PrideFrame.button(x + w - 76, y, 68, 14, "§cDemote", PrideFrame.BUTTON, mx, my);
                    click(x + w - 76, y, 68, 14, () -> send("demote " + subject + " " + tn));
                } else {
                    PrideFrame.button(x + w - 60, y, 52, 14, "§cDelete", PrideFrame.BUTTON, mx, my);
                    click(x + w - 60, y, 52, 14, () -> send("track " + tn + " delete"));
                }
            }
            y += 16;
        }
        y += 4;
        y = settingRow(x, y, w, "§7Import ranks from LuckPerms or PermissionsEx", "", "Import", mx, my, () -> mc.displayGuiScreen(new GuiAsk(this,
                "luckperms <export.json(.gz)>   or   pex <permissions.yml>", "luckperms ", t -> send("import " + t.trim()))));
        return settingRow(x, y, w, "§7New track", "", "Make", mx, my, () -> mc.displayGuiScreen(new GuiAsk(this,
                "Name then groups lowest first, e.g.  staff member moderator admin", "", t -> {
                    String[] p = t.trim().split("\\s+", 2);
                    if (p.length == 2) send("track " + p[0] + " set " + p[1]);
                })));
    }

    private static final List<String> LANDS = java.util.Arrays.asList("own", "trusted", "claimed", "others", "wild");

    /** rules that only count sometimes: on a kind of Pride Realms land, in one world, or until a time — list + add */
    private int specialRules(int x, int y, int w, String kind, List<PermStore.Rule> timed, int mx, int my) {
        y = heading(x, y, w, "§fSpecial rules §8(only on some land · one world · for a time)");
        if (timed != null) for (PermStore.Rule r : timed) {
            if (inView(y)) {
                StringBuilder when = new StringBuilder();
                if (r.land != null) when.append(" §bon ").append(r.land).append(" land");
                if (r.dim != PermStore.ANY_DIM) when.append(" §bin world ").append(r.dim);
                if (r.until > 0) when.append(" §b").append(left(r.until)).append(" left");
                String text = (r.value ? "§a✔ " : "§c✖ ") + "§f" + r.node + when;
                fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(text, w - 70), x, y + 3, 0xFFFFFF);
                if (fontRenderer.getStringWidth(text) > w - 70 && mx >= x && mx < x + w - 64 && my >= y && my < y + 14) hover = text;
                PrideFrame.button(x + w - 60, y, 52, 14, "§cRemove", PrideFrame.BUTTON, mx, my);
                final String node = r.node;
                click(x + w - 60, y, 52, 14, () -> send(kind + " " + subject + " unset " + node));
            }
            y += 16;
        }
        return settingRow(x, y, w, "§7Add a land rule §8(Pride Realms)", "", "Add", mx, my, () -> mc.displayGuiScreen(new GuiAsk(this,
                "permission allow|deny land, e.g.  minecraft.break.* deny others", "", t -> {
                    String[] p = t.trim().split("\\s+");
                    if (p.length != 3 || !LANDS.contains(p[2].toLowerCase(Locale.ROOT))) return;
                    String v = p[1].equalsIgnoreCase("allow") || p[1].equalsIgnoreCase("true") ? "true" : "false";
                    send(kind + " " + subject + " set " + p[0] + " " + v + " land=" + p[2].toLowerCase(Locale.ROOT));
                })));
    }

    /** one line: label on the left, value, a button on the right */
    private int settingRow(int x, int y, int w, String label, String value, String button, int mx, int my, Runnable onClick) {
        if (inView(y)) {
            String text = label + (value.isEmpty() ? "" : ": §f" + value);
            String fit = fontRenderer.trimStringToWidth(text, w - 70);
            fontRenderer.drawStringWithShadow(fit.length() < text.length() ? fontRenderer.trimStringToWidth(text, w - 78) + "§7…" : text, x, y + 3, 0xFFFFFF);
            if (fit.length() < text.length() && mx >= x && mx < x + w - 64 && my >= y && my < y + 14) hover = text;   // full text on hover
            PrideFrame.button(x + w - 60, y, 52, 14, button, PrideFrame.BUTTON, mx, my);
            click(x + w - 60, y, 52, 14, onClick);
        }
        return y + 16;
    }

    /** a group meta value with a Change button (empty answer = remove it) */
    private int metaRow(int x, int y, int w, PermStore.Group g, String key, String label, String emptyText, String question, int mx, int my) {
        String v = g.meta.getOrDefault(key, "");
        return settingRow(x, y, w, label, v.isEmpty() ? "§8" + emptyText : v, "Change", mx, my, () -> mc.displayGuiScreen(new GuiAsk(this, question, v,
                t -> send("group " + subject + (t.trim().isEmpty() ? " meta unset " + key : " meta set " + key + " " + t.trim())))));
    }

    private static String left(long until) {
        long s = Math.max(0, (until - System.currentTimeMillis()) / 1000);
        return s >= 86400 ? s / 86400 + "d " + s % 86400 / 3600 + "h" : s >= 3600 ? s / 3600 + "h " + s % 3600 / 60 + "m" : s / 60 + "m " + s % 60 + "s";
    }

    // ------------------------------------------------------------------ rows and chips
    private int heading(int x, int y, int w, String text) {
        if (inView(y)) { fontRenderer.drawStringWithShadow(text, x, y + 2, 0xFFFFFF); Gui.drawRect(x, y + 12, x + w - 8, y + 13, 0x30FFFFFF); }
        return y + 16;
    }

    private int chipRow(int x, int y, int w, String label, String node, int mx, int my) {
        if (inView(y)) {
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, w - 70), x, y + 2, 0xFFFFFF);
            chip(x + w - 60, y, 52, "rule", node, mx, my);
        }
        return y + 14;
    }

    private int actionRow(int x, int y, int w, String label, String mod, String[] actions, int mx, int my) {
        if (inView(y)) {
            fontRenderer.drawStringWithShadow(label, x, y + 2, 0xFFFFFF);
            int cx = x + w - 8 - actions.length * 44;
            for (String a : actions) { chip(cx, y, 42, a, mod + "." + a + ".*", mx, my); cx += 44; }
        }
        return y + 14;
    }

    /** one switch: own rule (✔ / ✖ / ·) + a faint inherited answer when not set */
    private void chip(int x, int y, int w, String word, String node, int mx, int my) {
        Boolean own = own(node), eff = own != null ? own : inherited(node);
        int col = own == null ? 0xFF2A2238 : own ? 0xFF1F7A3A : 0xFF8A1F2A;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
        Gui.drawRect(x, y, x + w, y + 12, over ? PrideFrame.brighten(col) : col);
        String mark = own == null ? (eff == null ? "§8·" : eff ? "§2✔" : "§4✖") : own ? "§f✔" : "§f✖";
        fontRenderer.drawString(mark + " " + (own == null ? "§7" : "§f") + word, x + 3, y + 2, 0xFFFFFF);
        if (over) hover = node + "  §7" + (own == null ? "not set here" + (eff == null ? " (allowed by default)" : eff ? " — inherited: allowed" : " — inherited: denied")
                : own ? "— allowed here" : "— denied here") + " · click to change";
        click(x, y, w, 12, () -> cycle(node, own));
    }

    private void cycle(String node, Boolean own) {
        String who = (playersTab ? "user " : "group ") + subject;
        if (own == null) send(who + " set " + node + " true");
        else if (own) send(who + " set " + node + " false");
        else send(who + " unset " + node);
    }

    private Boolean own(String node) {
        if (playersTab) {
            PermStore.Player p = subjectUuid == null ? null : rules.players.get(subjectUuid);
            return p == null ? null : p.nodes.get(node);
        }
        PermStore.Group g = rules.groups.get(subject);
        return g == null ? null : g.nodes.get(node);
    }

    /** what would apply with no rule on this row: wildcards here, then groups/parents */
    private Boolean inherited(String node) {
        if (playersTab) {
            PermStore.Player p = subjectUuid == null ? null : rules.players.get(subjectUuid);
            return rules.decideFor(p, node);
        }
        PermStore.Group g = rules.groups.get(subject);
        return g == null ? null : rules.groupDecidesPublic(g, node);
    }

    // ------------------------------------------------------------------ helpers
    private boolean inView(int y) { return y + 14 > f.cy && y < bottom; }

    private void click(int x, int y, int w, int h, Runnable r) { clickBoxes.add(new int[]{x, y, w, h}); clicks.add(r); }

    private void send(String args) { Net.CH.sendToServer(new Net.Edit(args)); }

    private String colorOf(String group) { PermStore.Group g = rules.groups.get(group); return g == null ? "" : g.prefix; }

    private int countRules(String prefix) {
        Map<String, Boolean> n = own();
        int k = 0;
        if (n != null) for (String s : n.keySet()) if (s.startsWith(prefix)) k++;
        return k;
    }

    private Map<String, Boolean> own() {
        if (playersTab) { PermStore.Player p = subjectUuid == null ? null : rules.players.get(subjectUuid); return p == null ? null : p.nodes; }
        PermStore.Group g = rules.groups.get(subject);
        return g == null ? null : g.nodes;
    }

    private static boolean hasMatch(ModCat c, String q) {
        for (Thing t : c.blocks) if (t.label.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Thing t : c.items) if (t.label.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Thing t : c.mobs) if (t.label.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    /** players with rules + everyone online, sorted */
    private List<String> players() {
        java.util.Set<String> n = new HashSet<>();
        for (PermStore.Player p : rules.players.values()) if (p.name != null && !p.name.isEmpty()) n.add(p.name);
        if (mc.getConnection() != null) for (NetworkPlayerInfo i : mc.getConnection().getPlayerInfoMap()) n.add(i.getGameProfile().getName());
        List<String> out = new ArrayList<>(n);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void findUuid() {
        subjectUuid = null;
        for (Map.Entry<String, PermStore.Player> e : rules.players.entrySet()) if (subject.equals(e.getValue().name)) subjectUuid = e.getKey();
        if (subjectUuid == null && mc.getConnection() != null) {
            NetworkPlayerInfo i = mc.getConnection().getPlayerInfo(subject);
            if (i != null) subjectUuid = i.getGameProfile().getId().toString();
        }
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        search.mouseClicked(mx, my, button);
        if (button != 0) return;
        for (int i = clickBoxes.size() - 1; i >= 0; i--) {
            int[] b = clickBoxes.get(i);
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3] && my >= f.cy - 2 && my < bottom + 2) {
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1f));
                clicks.get(i).run();
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (search.textboxKeyTyped(c, key)) { scrollM = 0; scrollR = 0; return; }
        if (key == Keyboard.KEY_ESCAPE && back != null) { mc.displayGuiScreen(back); return; }
        super.keyTyped(c, key);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Integer.signum(Mouse.getEventDWheel());
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx < colM) scrollL = Math.max(0, scrollL - d);
        else if (mx < colR) scrollM = Math.max(0, scrollM - d * 3);
        else scrollR = Math.max(0, scrollR - d * 3);
    }

    @Override public void updateScreen() { search.updateCursorCounter(); }
}
