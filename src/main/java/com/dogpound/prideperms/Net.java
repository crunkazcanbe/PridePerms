package com.dogpound.prideperms;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Menu traffic. The server sends the rules (gzip JSON) to an admin's screen; each click in the menu comes back as the
 * words of a /perms command and runs exactly as if typed — same permission check, same rules, nothing extra to trust.
 */
public final class Net {
    public static final SimpleNetworkWrapper CH = NetworkRegistry.INSTANCE.newSimpleChannel(PridePerms.MODID);

    private Net() {}

    static void register() {
        CH.registerMessage(Rules.Client.class, Rules.class, 0, Side.CLIENT);
        CH.registerMessage(Edit.Server.class, Edit.class, 1, Side.SERVER);
        CH.registerMessage(Cmds.Client.class, Cmds.class, 2, Side.CLIENT);
        CH.registerMessage(CmdsAsk.Server.class, CmdsAsk.class, 3, Side.SERVER);
        CH.registerMessage(GateScreen.Client.class, GateScreen.class, 4, Side.CLIENT);
        CH.registerMessage(Reply.Client.class, Reply.class, 5, Side.CLIENT);
    }

    /** may this player use the menu? same as /perms */
    static boolean admin(EntityPlayerMP p) { return p.canUseCommand(3, "perms"); }

    static void sendRules(EntityPlayerMP p, boolean open) { sendRules(p, open, ""); }

    /** the rules + everything the Studio shows; `page` = which Studio page to open on ("" = dashboard) */
    static void sendRules(EntityPlayerMP p, boolean open, String page) {
        PermStore s = PridePerms.store;
        if (s == null) return;
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.add("store", new com.google.gson.JsonParser().parse(s.toJson()));
        com.google.gson.JsonArray cmds = new com.google.gson.JsonArray();
        for (String c : new java.util.TreeSet<>(p.getServer().getCommandManager().getCommands().keySet())) cmds.add(c);
        o.add("commands", cmds);
        o.add("studio", Studio.info(p));
        o.addProperty("page", page == null ? "" : page);
        CH.sendTo(new Rules(o.toString(), open, p.getUniqueID().toString()), p);
    }

    // ------------------------------------------------------------------ command menu: the list (server → client) and "open it" (client → server)
    public static final class Cmds implements IMessage {
        public String json;
        public Cmds() {}
        public Cmds(String json) { this.json = json; }
        @Override public void toBytes(ByteBuf b) { byte[] z = gzip(json); b.writeInt(z.length); b.writeBytes(z); }
        @Override public void fromBytes(ByteBuf b) { byte[] z = new byte[Math.min(b.readInt(), 4 << 20)]; b.readBytes(z); json = gunzip(z); }
        public static final class Client implements IMessageHandler<Cmds, IMessage> {
            @Override public IMessage onMessage(Cmds m, MessageContext ctx) { PridePerms.proxy.cmdsArrived(m.json); return null; }
        }
    }

    public static final class CmdsAsk implements IMessage {
        public CmdsAsk() {}
        @Override public void toBytes(ByteBuf b) {}
        @Override public void fromBytes(ByteBuf b) {}
        public static final class Server implements IMessageHandler<CmdsAsk, IMessage> {
            @Override public IMessage onMessage(CmdsAsk m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> CH.sendTo(new Cmds(CmdMenu.listFor(p)), p));
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ join gate: open / refresh / close the lock screen
    public static final class GateScreen implements IMessage {
        public boolean open;
        public String stage, text, site, feedback;                           // stage: code | quiz
        public GateScreen() {}
        public GateScreen(boolean open, String stage, String text, String site, String feedback) {
            this.open = open; this.stage = stage; this.text = text; this.site = site; this.feedback = feedback;
        }
        @Override public void toBytes(ByteBuf b) { b.writeBoolean(open); writeStr(b, stage); writeStr(b, text); writeStr(b, site); writeStr(b, feedback); }
        @Override public void fromBytes(ByteBuf b) { open = b.readBoolean(); stage = readStr(b); text = readStr(b); site = readStr(b); feedback = readStr(b); }
        public static final class Client implements IMessageHandler<GateScreen, IMessage> {
            @Override public IMessage onMessage(GateScreen m, MessageContext ctx) { PridePerms.proxy.gateArrived(m); return null; }
        }
    }

    // ------------------------------------------------------------------ server → client: the rules
    public static final class Rules implements IMessage {
        String json; boolean open; String viewer;

        public Rules() {}
        Rules(String json, boolean open, String viewer) { this.json = json; this.open = open; this.viewer = viewer; }

        @Override public void toBytes(ByteBuf b) {
            byte[] z = gzip(json);
            b.writeBoolean(open);
            writeStr(b, viewer);
            b.writeInt(z.length);
            b.writeBytes(z);
        }

        @Override public void fromBytes(ByteBuf b) {
            open = b.readBoolean();
            viewer = readStr(b);
            byte[] z = new byte[Math.min(b.readInt(), 4 << 20)];
            b.readBytes(z);
            json = gunzip(z);
        }

        public static final class Client implements IMessageHandler<Rules, IMessage> {
            @Override public IMessage onMessage(Rules m, MessageContext ctx) {
                PridePerms.proxy.rulesArrived(m.json, m.open);
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ client → server: one edit = one /perms command
    public static final class Edit implements IMessage {
        String args;

        public Edit() {}
        public Edit(String args) { this.args = args; }

        @Override public void toBytes(ByteBuf b) { writeStr(b, args); }
        @Override public void fromBytes(ByteBuf b) { args = readStr(b); }

        public static final class Server implements IMessageHandler<Edit, IMessage> {
            @Override public IMessage onMessage(Edit m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> {
                    if (!admin(p)) { p.sendMessage(new TextComponentString("§cYou can't change permissions.")); return; }
                    if (m.args.length() > 2000) return;
                    // "verify …" runs /verify (the join gate); everything else is a /perms command. The words run
                    // exactly as if typed, and what they answer is shown in the menu instead of chat.
                    String line = m.args.startsWith("verify ") ? "/" + m.args : "/perms " + m.args;
                    Capture c = new Capture(p);
                    p.getServer().getCommandManager().executeCommand(c, line);
                    CH.sendTo(new Reply(String.join("\n", c.lines)), p);
                    sendRules(p, false);                                   // refresh the open menu
                });
                return null;
            }
        }
    }

    /** a stand-in for the admin that keeps what the command says (so the menu can show it) */
    static final class Capture implements net.minecraft.command.ICommandSender {
        final EntityPlayerMP p;
        final java.util.List<String> lines = new java.util.ArrayList<>();
        Capture(EntityPlayerMP p) { this.p = p; }
        @Override public String getName() { return p.getName(); }
        @Override public net.minecraft.util.text.ITextComponent getDisplayName() { return p.getDisplayName(); }
        @Override public void sendMessage(net.minecraft.util.text.ITextComponent c) { lines.add(c.getFormattedText()); }
        @Override public boolean canUseCommand(int level, String cmd) { return p.canUseCommand(level, cmd); }
        @Override public net.minecraft.util.math.BlockPos getPosition() { return p.getPosition(); }
        @Override public net.minecraft.util.math.Vec3d getPositionVector() { return p.getPositionVector(); }
        @Override public net.minecraft.world.World getEntityWorld() { return p.getEntityWorld(); }
        @Override public net.minecraft.entity.Entity getCommandSenderEntity() { return p; }
        @Override public boolean sendCommandFeedback() { return true; }
        @Override public net.minecraft.server.MinecraftServer getServer() { return p.getServer(); }
    }

    /** server → client: what the last menu action said */
    public static final class Reply implements IMessage {
        public String text;
        public Reply() {}
        public Reply(String text) { this.text = text; }
        @Override public void toBytes(ByteBuf b) { writeStr(b, text); }
        @Override public void fromBytes(ByteBuf b) { text = readStr(b); }
        public static final class Client implements IMessageHandler<Reply, IMessage> {
            @Override public IMessage onMessage(Reply m, MessageContext ctx) { PridePerms.proxy.replyArrived(m.text); return null; }
        }
    }

    // ------------------------------------------------------------------ helpers
    static void writeStr(ByteBuf b, String s) {
        byte[] d = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        b.writeInt(d.length);
        b.writeBytes(d);
    }

    static String readStr(ByteBuf b) {
        int n = Math.min(b.readInt(), 1 << 16);
        byte[] d = new byte[n];
        b.readBytes(d);
        return new String(d, StandardCharsets.UTF_8);
    }

    static byte[] gzip(String s) {
        try (ByteArrayOutputStream bo = new ByteArrayOutputStream(); GZIPOutputStream z = new GZIPOutputStream(bo)) {
            z.write(s.getBytes(StandardCharsets.UTF_8));
            z.finish();
            return bo.toByteArray();
        } catch (IOException e) { return new byte[0]; }
    }

    static String gunzip(byte[] d) {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(d)); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = z.read(buf)) > 0; ) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (IOException e) { return "{}"; }
    }
}
