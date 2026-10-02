package com.dogpound.prideperms.client;

import com.dogpound.prideperms.Net;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.net.URI;

/**
 * The join-gate lock screen: the three steps (website → code → type it), or the current rules question, a text box
 * and a Verify button. What is typed goes to the server as /verify <text>, the same as typing it in chat.
 * Esc hides it to look around (when the server allows walking); /verify brings it back.
 */
public class GuiVerify extends GuiScreen {
    private Net.GateScreen m;
    private GuiTextField box;
    private PrideFrame f;

    GuiVerify(Net.GateScreen m) { this.m = m; }

    void update(Net.GateScreen next) {
        boolean newQuestion = !next.text.equals(m.text) || !next.stage.equals(m.stage);
        m = next;
        if (box != null && newQuestion) box.setText("");
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.sized(width, height, 440, 230);
        String keep = box == null ? "" : box.getText();
        box = new GuiTextField(0, fontRenderer, f.cx, f.cy + f.ch - 42, f.cw, 18);
        box.setMaxStringLength(64);
        box.setText(keep);
        box.setFocused(true);
    }

    private boolean quiz() { return "quiz".equals(m.stage); }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        f.draw(this, "Welcome to Pride", "§d✪ locked");
        int y = f.cy + 4;
        if (quiz()) {
            for (String line : fontRenderer.listFormattedStringToWidth("§f" + m.text, f.cw)) { fontRenderer.drawStringWithShadow(line, f.cx, y, 0xFFFFFF); y += 11; }
            fontRenderer.drawStringWithShadow("§7Type your answer below.", f.cx, y + 4, 0xFFFFFF);
        } else {
            if (!m.text.isEmpty()) {
                for (String line : fontRenderer.listFormattedStringToWidth("§e" + m.text, f.cw)) { fontRenderer.drawStringWithShadow(line, f.cx, y, 0xFFFFFF); y += 11; }
                y += 4;
            }
            String site = m.site.isEmpty() ? "the Pride website" : m.site;
            String[] steps = {
                    "§d1 §fGo to §b" + site + " §fand make your free account.",
                    "§d2 §fType your Minecraft name there — you get a code.",
                    "§d3 §fType the code below and press Verify."};
            for (String s : steps) {
                for (String line : fontRenderer.listFormattedStringToWidth(s, f.cw)) { fontRenderer.drawStringWithShadow(line, f.cx, y, 0xFFFFFF); y += 11; }
                y += 3;
            }
        }
        if (!m.feedback.isEmpty()) fontRenderer.drawStringWithShadow(m.feedback, f.cx, f.cy + f.ch - 56, 0xFFFFFF);
        box.drawTextBox();
        int by = f.cy + f.ch - 18, third = (f.cw - 8) / 3;
        PrideFrame.button(f.cx, by, third, 16, quiz() ? "§aAnswer" : "§aVerify", PrideFrame.BUTTON, mx, my);
        PrideFrame.button(f.cx + third + 4, by, third, 16, m.site.isEmpty() || quiz() ? "§8Open website" : "Open website", PrideFrame.BUTTON, mx, my);
        PrideFrame.button(f.cx + 2 * (third + 4), by, f.cw - 2 * (third + 4), 16, "Look around", PrideFrame.BUTTON, mx, my);
        super.drawScreen(mx, my, pt);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        box.mouseClicked(mx, my, button);
        int by = f.cy + f.ch - 18, third = (f.cw - 8) / 3;
        if (my < by || my >= by + 16) return;
        if (mx >= f.cx && mx < f.cx + third) send();
        else if (mx >= f.cx + third + 4 && mx < f.cx + 2 * third + 4) openSite();
        else if (mx >= f.cx + 2 * (third + 4) && mx < f.cx + f.cw) mc.displayGuiScreen(null);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { send(); return; }
        if (key == Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(null); return; }
        box.textboxKeyTyped(c, key);
    }

    private void send() {
        String t = box.getText().trim();
        if (t.isEmpty() || mc.player == null) return;
        mc.player.sendChatMessage("/verify " + t);
        box.setText("");
    }

    private void openSite() {
        if (m.site.isEmpty() || quiz()) return;
        try {
            Class<?> desk = Class.forName("java.awt.Desktop");
            Object d = desk.getMethod("getDesktop").invoke(null);
            desk.getMethod("browse", URI.class).invoke(d, new URI(m.site));
        } catch (Throwable t) {
            mc.player.sendMessage(new net.minecraft.util.text.TextComponentString("§7Website: §b" + m.site));
        }
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public void updateScreen() { box.updateCursorCounter(); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
