package com.dogpound.prideperms.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The name-look editor (prefix / suffix): a text box with & colour codes, 16 colour swatches, bold / italic /
 * underline / strike / reset, symbols, bracket presets, a one-click rainbow, and a live chat preview.
 */
public class GuiStyle extends GuiScreen {
    private static final char[] COLORS = "0123456789abcdef".toCharArray();
    private static final int[] RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String[][] FORMATS = {{"l", "§lBold"}, {"o", "§oItalic"}, {"n", "§nUnder"}, {"m", "§mStrike"}, {"r", "Reset"}};
    private static final String[] SYMBOLS = {"★", "✦", "♛", "❤", "⚔", "☀", "✿", "♪", "☠", "⚡", "✔", "➤", "◆", "•", "☯", "♦", "✧", "❀"};
    private static final String[][] SHAPES = {{"[", "]"}, {"(", ")"}, {"«", "»"}, {"⟨", "⟩"}, {"✦ ", " ✦"}, {"| ", " |"}};

    private final GuiScreen back;
    private final String title, start, who;
    private final boolean suffix;
    private final Consumer<String> done;
    private GuiTextField box;
    private PrideFrame f;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();

    /** start/answer use & codes; who = the name shown in the preview; suffix = preview puts it after the name */
    GuiStyle(GuiScreen back, String title, String start, String who, boolean suffix, Consumer<String> done) {
        this.back = back; this.title = title; this.start = start; this.who = who; this.suffix = suffix; this.done = done;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.sized(width, height, 460, 300);
        String keep = box == null ? start : box.getText();
        box = new GuiTextField(0, fontRenderer, f.cx, f.cy + 14, f.cw, 16);
        box.setMaxStringLength(80);
        box.setText(keep);
        box.setFocused(true);
    }

    private void click(int x, int y, int w, int h, Runnable r) { boxes.add(new int[]{x, y, w, h}); clicks.add(r); }

    private void insert(String s) { box.writeText(s); box.setFocused(true); }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        boxes.clear(); clicks.clear();
        f.draw(this, title, "§7& codes work too");
        int x = f.cx, y = f.cy;
        fontRenderer.drawStringWithShadow("§7Text (use the buttons or type & codes like &d)", x, y + 2, 0xFFFFFF);
        box.drawTextBox();
        y += 36;

        // live preview on a chat-like strip
        String look = box.getText().replace('&', '§');
        String line = suffix ? "§f" + who + look + "§r§f: hello everyone!" : look + (look.endsWith(" ") || look.isEmpty() ? "" : " ") + "§r§f" + who + ": hello everyone!";
        Gui.drawRect(x, y, x + f.cw, y + 16, 0x90000000);
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(line, f.cw - 8), x + 4, y + 4, 0xFFFFFF);
        y += 24;

        // colours
        fontRenderer.drawStringWithShadow("§fColours", x, y, 0xFFFFFF);
        y += 11;
        int sw = Math.min(22, (f.cw - 15 * 3) / 16);
        for (int i = 0; i < 16; i++) {
            int bx = x + i * (sw + 3);
            boolean over = mx >= bx && mx < bx + sw && my >= y && my < y + 14;
            Gui.drawRect(bx - 1, y - 1, bx + sw + 1, y + 15, over ? 0xFFFFFFFF : 0xFF3A3050);
            Gui.drawRect(bx, y, bx + sw, y + 14, 0xFF000000 | RGB[i]);
            final char c = COLORS[i];
            click(bx, y, sw, 14, () -> insert("&" + c));
        }
        y += 20;

        // formats + rainbow
        int bw = (f.cw - 6 * 4) / 7;
        for (int i = 0; i < FORMATS.length; i++) {
            final String code = FORMATS[i][0];
            PrideFrame.button(x + i * (bw + 4), y, bw, 14, FORMATS[i][1], PrideFrame.BUTTON, mx, my);
            click(x + i * (bw + 4), y, bw, 14, () -> insert("&" + code));
        }
        PrideFrame.button(x + 5 * (bw + 4), y, bw * 2 + 4, 14, rainbow("Rainbow text"), PrideFrame.BUTTON, mx, my);
        click(x + 5 * (bw + 4), y, bw * 2 + 4, 14, () -> box.setText(rainbowCodes(plain(box.getText()))));
        y += 20;

        // symbols
        fontRenderer.drawStringWithShadow("§fSymbols", x, y, 0xFFFFFF);
        y += 11;
        int symW = Math.max(16, (f.cw - (SYMBOLS.length - 1) * 2) / SYMBOLS.length);
        for (int i = 0; i < SYMBOLS.length; i++) {
            final String s = SYMBOLS[i];
            PrideFrame.button(x + i * (symW + 2), y, symW, 14, s, PrideFrame.BUTTON, mx, my);
            click(x + i * (symW + 2), y, symW, 14, () -> insert(s));
        }
        y += 20;

        // wrap the text in a shape
        fontRenderer.drawStringWithShadow("§fWrap it", x, y, 0xFFFFFF);
        y += 11;
        int shW = (f.cw - (SHAPES.length - 1) * 4) / SHAPES.length;
        for (int i = 0; i < SHAPES.length; i++) {
            final String[] sh = SHAPES[i];
            PrideFrame.button(x + i * (shW + 4), y, shW, 14, sh[0] + "Tag" + sh[1], PrideFrame.BUTTON, mx, my);
            click(x + i * (shW + 4), y, shW, 14, () -> {
                String t = box.getText().trim();
                String lead = t.replaceAll("^((?:&[0-9a-fk-or])*).*$", "$1");      // keep the colour codes in front of the bracket
                box.setText(lead + sh[0] + t.substring(lead.length()) + sh[1] + (suffix ? "" : " "));
            });
        }

        // save / clear / cancel
        int by = f.cy + f.ch - 16, third = (f.cw - 8) / 3;
        PrideFrame.button(x, by, third, 16, "§aSave", PrideFrame.BUTTON, mx, my);
        click(x, by, third, 16, () -> finish(box.getText()));
        PrideFrame.button(x + third + 4, by, third, 16, "§eClear", PrideFrame.BUTTON, mx, my);
        click(x + third + 4, by, third, 16, () -> box.setText(""));
        PrideFrame.button(x + 2 * (third + 4), by, f.cw - 2 * (third + 4), 16, "Cancel", PrideFrame.BUTTON, mx, my);
        click(x + 2 * (third + 4), by, f.cw - 2 * (third + 4), 16, () -> mc.displayGuiScreen(back));
    }

    private void finish(String text) {
        mc.displayGuiScreen(back);
        done.accept(text);
    }

    static String plain(String s) { return s.replaceAll("&[0-9a-fk-or]", ""); }

    static String rainbowCodes(String s) {
        String c = "c6eab9d";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (char ch : s.toCharArray()) {
            if (ch != ' ') b.append('&').append(c.charAt(n++ % c.length()));
            b.append(ch);
        }
        return b.toString();
    }

    static String rainbow(String s) { return rainbowCodes(s).replace('&', '§'); }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        box.mouseClicked(mx, my, button);
        if (button != 0) return;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) { clicks.get(i).run(); return; }
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { finish(box.getText()); return; }
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(back); return; }
        box.textboxKeyTyped(c, key);
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public void updateScreen() { box.updateCursorCounter(); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
