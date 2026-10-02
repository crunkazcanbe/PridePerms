package com.dogpound.prideperms.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The command button menu: every command this player may use, grouped by mod, as buttons. Plain commands run on one
 * click; commands that take details open {@link GuiCmdForm}. ★ Favorites and Recent live in config/prideperms-cmdmenu.txt.
 * Left-click = run / fill in · right-click = ★ favorite on/off.
 */
public class GuiCommands extends GuiScreen {
    public static final class Cmd {
        final String name, usage, mod;
        final List<String> aliases = new ArrayList<>();
        Cmd(String name, String usage, String mod) { this.name = name; this.usage = usage; this.mod = mod; }
    }

    static final List<Cmd> ALL = new ArrayList<>();
    static final LinkedHashSet<String> FAV = new LinkedHashSet<>(), RECENT = new LinkedHashSet<>();
    private static boolean loaded;

    private PrideFrame f;
    private GuiTextField search;
    private String mod = "★ Favorites";
    private int modScroll, cmdScroll;
    private final List<String> mods = new ArrayList<>();
    private final Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public static void accept(String json) {
        ALL.clear();
        for (JsonElement e : new JsonParser().parse(json).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String u = o.get("u").getAsString();
            if (I18n.hasKey(u)) u = I18n.format(u);                          // vanilla usages are translation keys
            Cmd c = new Cmd(o.get("n").getAsString(), u, o.get("m").getAsString());
            JsonArray al = o.getAsJsonArray("a");
            if (al != null) for (JsonElement a : al) c.aliases.add(a.getAsString());
            ALL.add(c);
        }
        ALL.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        loadPrefs();
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.fit(width, height);
        search = new GuiTextField(0, fontRenderer, f.cx + 132, f.cy, f.cw - 132, 14);
        search.setMaxStringLength(40);
        search.setFocused(true);
        counts.clear();
        for (Cmd c : ALL) counts.merge(c.mod, 1, Integer::sum);
        mods.clear();
        mods.add("★ Favorites"); mods.add("⟲ Recent"); mods.add("All");
        mods.addAll(counts.keySet());
        if (FAV.isEmpty() && mod.equals("★ Favorites")) mod = "All";
    }

    private List<Cmd> shown() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<Cmd> out = new ArrayList<>();
        if (mod.equals("⟲ Recent")) { for (String n : RECENT) for (Cmd c : ALL) if (c.name.equals(n) && matches(c, q)) out.add(c); return out; }
        for (Cmd c : ALL) {
            if (mod.equals("★ Favorites") ? !FAV.contains(c.name) : !mod.equals("All") && !c.mod.equals(mod)) continue;
            if (matches(c, q)) out.add(c);
        }
        if (!q.isEmpty()) out.sort((a, b) -> rank(a, q) - rank(b, q));      // "gamemode" → /gamemode before /defaultgamemode
        return out;
    }

    /** 0 exact name · 1 name starts with it · 2 an alias does · 3 name contains it · 4 only mod/usage mention it */
    private static int rank(Cmd c, String q) {
        String n = c.name.toLowerCase(Locale.ROOT);
        if (n.equals(q)) return 0;
        if (n.startsWith(q)) return 1;
        for (String a : c.aliases) if (a.toLowerCase(Locale.ROOT).startsWith(q)) return 2;
        return n.contains(q) ? 3 : 4;
    }

    private static boolean matches(Cmd c, String q) {
        if (q.isEmpty()) return true;
        if (c.name.toLowerCase(Locale.ROOT).contains(q) || c.mod.toLowerCase(Locale.ROOT).contains(q) || c.usage.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String a : c.aliases) if (a.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    // grid layout
    private int cols() { return Math.max(1, (f.cw - 136) / 110); }
    private int bw() { return (f.cw - 136 - (cols() - 1) * 4) / cols(); }
    private int gridY() { return f.cy + 20; }
    private int gridH() { return f.ch - 34; }
    private int rowsVisible() { return Math.max(1, gridH() / 20); }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        List<Cmd> list = shown();
        f.draw(this, "Commands", "§7" + list.size() + " of " + ALL.size() + " · left-click run · right-click ★");
        // mod list
        int ly = f.cy, lh = f.ch, vis = lh / 14;
        modScroll = Math.max(0, Math.min(modScroll, Math.max(0, mods.size() - vis)));
        PrideFrame.clip(f.cx, ly, 128, lh);
        for (int i = modScroll; i < mods.size() && i < modScroll + vis; i++) {
            String m = mods.get(i);
            int y = ly + (i - modScroll) * 14;
            boolean on = m.equals(mod), over = mx >= f.cx && mx < f.cx + 124 && my >= y && my < y + 13;
            PrideFrame.tile(f.cx, y, 124, 13, PrideFrame.RAINBOW[i % PrideFrame.RAINBOW.length], over, on);
            String label = m + (counts.containsKey(m) ? " §7" + counts.get(m) : m.equals("★ Favorites") ? " §7" + FAV.size() : m.equals("All") ? " §7" + ALL.size() : "");
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, 118), f.cx + 3, y + 3, 0xFFFFFF);
        }
        PrideFrame.unclip();
        PrideFrame.scrollbar(f.cx + 125, ly, lh, modScroll, vis, mods.size());
        // search + grid
        search.drawTextBox();
        if (search.getText().isEmpty()) fontRenderer.drawString("§8search commands, mods, usage…", search.x + 4, search.y + 3, 0);
        int cols = cols(), bw = bw(), rows = (list.size() + cols - 1) / cols, rv = rowsVisible();
        cmdScroll = Math.max(0, Math.min(cmdScroll, Math.max(0, rows - rv)));
        Cmd hover = null;
        PrideFrame.clip(f.cx + 132, gridY(), f.cw - 132, gridH());
        for (int i = cmdScroll * cols; i < list.size() && i < (cmdScroll + rv) * cols; i++) {
            Cmd c = list.get(i);
            int r = i / cols - cmdScroll, col = i % cols, x = f.cx + 132 + col * (bw + 4), y = gridY() + r * 20;
            String label = (FAV.contains(c.name) ? "§e★§r " : "") + "/" + c.name;
            if (PrideFrame.button(x, y, bw, 17, fontRenderer.trimStringToWidth(label, bw - 6), needsForm(c) ? PrideFrame.BUTTON : 0xFF2E3A2A, mx, my)) hover = c;
        }
        PrideFrame.unclip();
        PrideFrame.scrollbar(f.x + f.w - 8, gridY(), gridH(), cmdScroll, rv, rows);
        if (list.isEmpty()) fontRenderer.drawStringWithShadow(mod.equals("★ Favorites") ? "§7No favorites yet — right-click any command to ★ it." : "§7Nothing matches.", f.cx + 136, gridY() + 4, 0xFFFFFF);
        // footer: usage of the hovered command
        String foot = hover == null ? "§7Green = runs on one click · grey = asks for details first · J or /cmds opens this menu"
                : "§f/" + hover.name + " §7(" + hover.mod + ")§f  " + hover.usage + (hover.aliases.isEmpty() ? "" : "  §7aka /" + String.join(", /", hover.aliases));
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(foot, f.cw - 136), f.cx + 132, f.cy + f.ch - 10, 0xFFFFFF);
    }

    /** true when the usage asks for anything after the command name */
    static boolean needsForm(Cmd c) { return !GuiCmdForm.parse(c).isEmpty(); }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        search.mouseClicked(mx, my, button);
        int vis = f.ch / 14;
        if (mx >= f.cx && mx < f.cx + 124 && my >= f.cy && my < f.cy + f.ch) {
            int i = modScroll + (my - f.cy) / 14;
            if (i < mods.size()) { mod = mods.get(i); cmdScroll = 0; playClick(); }
            return;
        }
        List<Cmd> list = shown();
        int cols = cols(), bw = bw();
        if (mx >= f.cx + 132 && my >= gridY() && my < gridY() + gridH()) {
            int col = (mx - f.cx - 132) / (bw + 4), row = (my - gridY()) / 20;
            if (col >= cols || (mx - f.cx - 132) % (bw + 4) >= bw || (my - gridY()) % 20 >= 17) return;
            int i = (cmdScroll + row) * cols + col;
            if (i >= list.size()) return;
            Cmd c = list.get(i);
            playClick();
            if (button == 1) { if (!FAV.remove(c.name)) FAV.add(c.name); savePrefs(); return; }
            if (needsForm(c)) mc.displayGuiScreen(new GuiCmdForm(this, c));
            else run(c, "/" + c.name);
        }
    }

    /** send a finished command line, remember it in Recent */
    static void run(Cmd c, String line) {
        Minecraft mc = Minecraft.getMinecraft();
        RECENT.remove(c.name);
        RECENT.add(c.name);
        while (RECENT.size() > 15) RECENT.remove(RECENT.iterator().next());
        savePrefs();
        mc.displayGuiScreen(null);
        mc.ingameGUI.getChatGUI().addToSentMessages(line);
        if (net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.player, line) == 0) mc.player.sendChatMessage(line);
    }

    private void playClick() {
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx < f.cx + 128) modScroll += d > 0 ? -2 : 2; else cmdScroll += d > 0 ? -1 : 1;
    }

    @Override
    protected void keyTyped(char ch, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(null); return; }
        if (search.textboxKeyTyped(ch, key)) cmdScroll = 0;
    }

    @Override public void updateScreen() { search.updateCursorCounter(); }
    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public boolean doesGuiPauseGame() { return false; }

    // ------------------------------------------------------------------ ★ favorites + recent
    private static File prefs() { return new File(Minecraft.getMinecraft().mcDataDir, "config/prideperms-cmdmenu.txt"); }

    static void loadPrefs() {
        if (loaded) return;
        loaded = true;
        try {
            for (String l : Files.readAllLines(prefs().toPath(), StandardCharsets.UTF_8)) {
                if (l.startsWith("fav=")) FAV.addAll(split(l.substring(4)));
                if (l.startsWith("recent=")) RECENT.addAll(split(l.substring(7)));
            }
        } catch (Exception ignored) {}
    }

    private static List<String> split(String s) { return s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split(",")); }

    static void savePrefs() {
        try { Files.write(prefs().toPath(), ("fav=" + String.join(",", FAV) + "\nrecent=" + String.join(",", RECENT) + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }
}
