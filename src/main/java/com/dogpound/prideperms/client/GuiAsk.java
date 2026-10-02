package com.dogpound.prideperms.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.function.Consumer;

/** A small centred Pride dialog with one text box: Enter/OK = answer, Esc/Cancel = back. */
public class GuiAsk extends GuiScreen {
    private final GuiScreen back;
    private final String question, start;
    private final Consumer<String> answer;
    private GuiTextField box;
    private PrideFrame f;

    GuiAsk(GuiScreen back, String question, String start, Consumer<String> answer) {
        this.back = back; this.question = question; this.start = start; this.answer = answer;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.sized(width, height, 320, 110);
        box = new GuiTextField(0, fontRenderer, f.cx, f.cy + 18, f.cw, 16);
        box.setMaxStringLength(60);
        box.setText(start);
        box.setFocused(true);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        f.draw(this, "Permissions", "");
        fontRenderer.drawStringWithShadow(question, f.cx, f.cy + 4, 0xFFFFFF);
        box.drawTextBox();
        PrideFrame.button(f.cx, f.cy + f.ch - 16, f.cw / 2 - 2, 16, "§aOK", PrideFrame.BUTTON, mx, my);
        PrideFrame.button(f.cx + f.cw / 2 + 2, f.cy + f.ch - 16, f.cw / 2 - 2, 16, "Cancel", PrideFrame.BUTTON, mx, my);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        box.mouseClicked(mx, my, button);
        int y = f.cy + f.ch - 16;
        if (my >= y && my < y + 16) {
            if (mx >= f.cx && mx < f.cx + f.cw / 2 - 2) done(true);
            else if (mx >= f.cx + f.cw / 2 + 2 && mx < f.cx + f.cw) done(false);
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { done(true); return; }
        if (key == Keyboard.KEY_ESCAPE) { done(false); return; }
        box.textboxKeyTyped(c, key);
    }

    private void done(boolean ok) {
        mc.displayGuiScreen(back);
        if (ok) answer.accept(box.getText());
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public void updateScreen() { box.updateCursorCounter(); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
