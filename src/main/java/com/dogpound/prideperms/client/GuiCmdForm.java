package com.dogpound.prideperms.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fill in a command's details before running it. The usage text is read like a player would read it:
 * words stay as they are, <needed> and [optional] become boxes, <a|b|c> becomes a button that cycles the choices,
 * names with "player"/"target" get a » button that cycles the online players, and "x | y | z" usages offer each form.
 */
public class GuiCmdForm extends GuiScreen {
    /** one piece of a usage: a fixed word, or a field to fill in */
    static final class Tok {
        final String text; final boolean field, optional; final String[] choices;
        Tok(String text, boolean field, boolean optional, String[] choices) { this.text = text; this.field = field; this.optional = optional; this.choices = choices; }
    }

    private static final Pattern PIECE = Pattern.compile("<[^>]*>|\\[[^]]*]|\\S+");

    /** every form of the usage, each a list of pieces after the command name (empty list = runs as is) */
    static List<List<Tok>> parse(GuiCommands.Cmd c) {
        String u = TextFormatting.getTextWithoutFormattingCodes(c.usage == null ? "" : c.usage).trim();
        int dash = u.indexOf(" — ");
        if (dash < 0) dash = u.indexOf(" - ");
        if (dash >= 0) u = u.substring(0, dash).trim();                        // "… — description" is not part of the command
        List<List<Tok>> forms = new ArrayList<>();
        if (u.startsWith("commands.") || u.isEmpty()) { forms.add(new ArrayList<>()); return trimEmpty(forms); }   // untranslated key
        for (String variant : splitTopLevel(u)) {
            List<Tok> toks = new ArrayList<>();
            Matcher m = PIECE.matcher(variant.trim());
            boolean first = true;
            while (m.find()) {
                String t = m.group();
                if (first) {
                    first = false;
                    String bare = t.startsWith("/") ? t.substring(1) : t;
                    if (bare.equalsIgnoreCase(c.name) || c.aliases.contains(bare)) continue;   // the command's own name
                }
                if (t.startsWith("<") || t.startsWith("[")) {
                    String inner = t.substring(1, t.length() - 1).trim();
                    String[] ch = inner.contains("|") ? inner.split("\\s*\\|\\s*") : null;
                    toks.add(new Tok(inner, true, t.startsWith("["), ch));
                } else toks.add(new Tok(t, false, false, null));
            }
            forms.add(toks);
        }
        return trimEmpty(forms);
    }

    /** a usage with no pieces at all needs no form */
    private static List<List<Tok>> trimEmpty(List<List<Tok>> forms) {
        for (List<Tok> f : forms) if (!f.isEmpty()) return forms;
        return new ArrayList<>();
    }

    /** split "a | b <x|y> | c" on the | that are NOT inside <> or [] */
    static List<String> splitTopLevel(String u) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if (ch == '<' || ch == '[') depth++;
            else if ((ch == '>' || ch == ']') && depth > 0) depth--;
            else if (ch == '|' && depth == 0) { out.add(u.substring(start, i)); start = i + 1; }
        }
        out.add(u.substring(start));
        return out;
    }

    private final GuiScreen back;
    private final GuiCommands.Cmd cmd;
    private final List<List<Tok>> forms;
    private int form;
    private final List<GuiTextField> boxes = new ArrayList<>();
    private final List<Tok> fieldToks = new ArrayList<>();
    private final List<Integer> choice = new ArrayList<>();
    private PrideFrame f;

    GuiCmdForm(GuiScreen back, GuiCommands.Cmd cmd) { this.back = back; this.cmd = cmd; this.forms = parse(cmd); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.sized(width, height, 560, 360);
        List<String> keep = new ArrayList<>();
        for (GuiTextField b : boxes) keep.add(b.getText());
        boxes.clear(); fieldToks.clear(); choice.clear();
        int y = f.cy + (forms.size() > 1 ? 40 : 18);
        for (Tok t : forms.get(form)) {
            if (!t.field) continue;
            GuiTextField b = new GuiTextField(boxes.size(), fontRenderer, f.cx + 150, y, f.cw - 150 - (isPlayer(t) ? 22 : 0), 14);
            b.setMaxStringLength(200);
            if (keep.size() > boxes.size()) b.setText(keep.get(boxes.size()));
            boxes.add(b); fieldToks.add(t); choice.add(t.choices == null ? -1 : (t.optional ? -1 : 0));
            y += 20;
        }
        if (!boxes.isEmpty()) boxes.get(0).setFocused(true);
    }

    private static boolean isPlayer(Tok t) {
        String s = t.text.toLowerCase(Locale.ROOT);
        return t.choices == null && (s.contains("player") || s.contains("target") || s.equals("name") || s.contains("user"));
    }

    /** the finished command line */
    private String line() {
        StringBuilder sb = new StringBuilder("/" + cmd.name);
        int fi = 0;
        List<String> tail = new ArrayList<>();
        for (Tok t : forms.get(form)) {
            if (!t.field) { tail.add(t.text); continue; }
            String v = t.choices != null ? (choice.get(fi) < 0 ? "" : t.choices[choice.get(fi)]) : boxes.get(fi).getText().trim();
            fi++;
            tail.add(v);
        }
        while (!tail.isEmpty() && tail.get(tail.size() - 1).isEmpty()) tail.remove(tail.size() - 1);   // unfilled optional ends
        for (String s : tail) if (!s.isEmpty()) sb.append(' ').append(s);
        return sb.toString();
    }

    private boolean ready() {
        for (int i = 0; i < fieldToks.size(); i++) {
            Tok t = fieldToks.get(i);
            if (t.optional) continue;
            if (t.choices != null ? choice.get(i) < 0 : boxes.get(i).getText().trim().isEmpty()) return false;
        }
        return true;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        f.draw(this, "/" + cmd.name, "§7" + cmd.mod);
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§7" + TextFormatting.getTextWithoutFormattingCodes(cmd.usage), f.cw), f.cx, f.cy + 2, 0xFFFFFF);
        if (forms.size() > 1) {
            PrideFrame.button(f.cx, f.cy + 16, 20, 16, "‹", PrideFrame.BUTTON, mx, my);
            PrideFrame.button(f.cx + f.cw - 20, f.cy + 16, 20, 16, "›", PrideFrame.BUTTON, mx, my);
            StringBuilder sb = new StringBuilder();
            for (Tok t : forms.get(form)) sb.append(t.field ? (t.optional ? "[" + t.text + "] " : "<" + t.text + "> ") : t.text + " ");
            String s = "§dForm " + (form + 1) + "/" + forms.size() + ": §f" + sb.toString().trim();
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(s, f.cw - 50), f.cx + 26, f.cy + 20, 0xFFFFFF);
        }
        for (int i = 0; i < boxes.size(); i++) {
            Tok t = fieldToks.get(i);
            GuiTextField b = boxes.get(i);
            String label = (t.optional ? "§7" : "§f") + t.text + (t.optional ? " (optional)" : "");
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, 145), f.cx, b.y + 3, 0xFFFFFF);
            if (t.choices != null) {
                int c = choice.get(i);
                PrideFrame.button(b.x, b.y - 1, b.width, 16, c < 0 ? "§7(none)" : t.choices[c], PrideFrame.TILE_ON, mx, my);
            } else {
                b.drawTextBox();
                if (isPlayer(t)) PrideFrame.button(b.x + b.width + 4, b.y - 1, 18, 16, "»", PrideFrame.BUTTON, mx, my);
            }
        }
        if (boxes.isEmpty()) fontRenderer.drawStringWithShadow("§7This form needs nothing else.", f.cx, f.cy + (forms.size() > 1 ? 40 : 18), 0xFFFFFF);
        String ln = line();
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§b" + ln, f.cw), f.cx, f.cy + f.ch - 32, 0xFFFFFF);
        PrideFrame.button(f.cx, f.cy + f.ch - 16, f.cw / 2 - 2, 16, ready() ? "§aRun ⏎" : "§7Fill in the white boxes", ready() ? 0xFF2E3A2A : PrideFrame.BUTTON, mx, my);
        PrideFrame.button(f.cx + f.cw / 2 + 2, f.cy + f.ch - 16, f.cw / 2 - 2, 16, "Back", PrideFrame.BUTTON, mx, my);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        for (GuiTextField b : boxes) b.mouseClicked(mx, my, button);
        if (forms.size() > 1 && my >= f.cy + 16 && my < f.cy + 32) {
            if (mx >= f.cx && mx < f.cx + 20) { form = (form + forms.size() - 1) % forms.size(); boxes.clear(); initGui(); return; }
            if (mx >= f.cx + f.cw - 20 && mx < f.cx + f.cw) { form = (form + 1) % forms.size(); boxes.clear(); initGui(); return; }
        }
        for (int i = 0; i < boxes.size(); i++) {
            Tok t = fieldToks.get(i);
            GuiTextField b = boxes.get(i);
            if (my < b.y - 1 || my >= b.y + 15) continue;
            if (t.choices != null && mx >= b.x && mx < b.x + b.width) {       // cycle the choices (right-click = back); optional ones include (none)
                int n = t.choices.length, c = choice.get(i), lo = t.optional ? -1 : 0;
                c = button == 1 ? (c <= lo ? n - 1 : c - 1) : (c >= n - 1 ? lo : c + 1);
                choice.set(i, c);
                return;
            }
            if (isPlayer(t) && mx >= b.x + b.width + 4 && mx < b.x + b.width + 22) { b.setText(nextPlayer(b.getText())); return; }
        }
        if (my >= f.cy + f.ch - 16 && my < f.cy + f.ch) {
            if (mx < f.cx + f.cw / 2) { if (ready()) GuiCommands.run(cmd, line()); }
            else mc.displayGuiScreen(back);
        }
    }

    /** the next online player's name after `current` (alphabetical, wraps) */
    private String nextPlayer(String current) {
        List<String> names = new ArrayList<>();
        if (mc.getConnection() != null) for (NetworkPlayerInfo n : mc.getConnection().getPlayerInfoMap()) names.add(n.getGameProfile().getName());
        if (names.isEmpty()) return current;
        names.sort(String.CASE_INSENSITIVE_ORDER);
        int i = names.indexOf(current);
        return names.get((i + 1) % names.size());
    }

    @Override
    protected void keyTyped(char ch, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(back); return; }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && ready()) { GuiCommands.run(cmd, line()); return; }
        if (key == Keyboard.KEY_TAB && !boxes.isEmpty()) {                     // Tab = next box
            int i = 0;
            for (; i < boxes.size(); i++) if (boxes.get(i).isFocused()) break;
            for (GuiTextField b : boxes) b.setFocused(false);
            boxes.get((i + 1) % boxes.size()).setFocused(true);
            return;
        }
        for (GuiTextField b : boxes) b.textboxKeyTyped(ch, key);
    }

    @Override public void updateScreen() { for (GuiTextField b : boxes) b.updateCursorCounter(); }
    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
