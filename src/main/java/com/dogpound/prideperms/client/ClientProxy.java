package com.dogpound.prideperms.client;

import com.dogpound.prideperms.CommonProxy;
import com.dogpound.prideperms.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class ClientProxy extends CommonProxy {
    /** opens the command button menu (J by default, changeable in Controls) */
    public static final KeyBinding MENU_KEY = new KeyBinding("Command menu", Keyboard.KEY_J, "PridePerms");

    @Override
    public void init() {
        ClientRegistry.registerKeyBinding(MENU_KEY);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void key(InputEvent.KeyInputEvent e) {
        if (MENU_KEY.isPressed() && Minecraft.getMinecraft().currentScreen == null) Net.CH.sendToServer(new Net.CmdsAsk());
    }

    @Override
    public void rulesArrived(String json, boolean open) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            GuiStudio.accept(json);                                       // also fills the rule editor's copy
            if (open) mc.displayGuiScreen(new GuiStudio());
            else if (mc.currentScreen instanceof GuiPerms) ((GuiPerms) mc.currentScreen).refresh();
            else if (mc.currentScreen instanceof GuiStudio) ((GuiStudio) mc.currentScreen).refresh();
        });
    }

    @Override
    public void replyArrived(String text) {
        Minecraft.getMinecraft().addScheduledTask(() -> GuiStudio.replied(text));
    }

    @Override
    public void gateArrived(Net.GateScreen m) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (!m.open) { if (mc.currentScreen instanceof GuiVerify) mc.displayGuiScreen(null); return; }
            if (mc.currentScreen instanceof GuiVerify) ((GuiVerify) mc.currentScreen).update(m);
            else mc.displayGuiScreen(new GuiVerify(m));
        });
    }

    @Override
    public void cmdsArrived(String json) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> { GuiCommands.accept(json); mc.displayGuiScreen(new GuiCommands()); });
    }
}
