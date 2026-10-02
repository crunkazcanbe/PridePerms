package com.dogpound.prideperms;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Colours and emojis in chat, and players colouring their own names — all by permission:
 *   prideperms.chat.color   — use &0-&f colour codes in chat            (default: ops)
 *   prideperms.chat.format  — use &l bold, &o italic, &n, &m, &k in chat (default: ops)
 *   prideperms.chat.emoji   — :heart: → ❤ shortcodes                    (default: everyone)
 *   prideperms.namecolor.<colour> — /namecolor <colour> for your own name (default: everyone, except "obfuscated")
 *   prideperms.namecolor.bold / .italic / .underline — styles for your name (default: everyone)
 */
public final class ChatStyle {
    /** shortcodes → symbols Minecraft 1.12's font can draw */
    public static final Map<String, String> EMOJI = new LinkedHashMap<>();
    static {
        String[] pairs = {
            "heart", "❤", "hearts", "♥", "star", "★", "star2", "☆", "sun", "☀", "cloud", "☁", "umbrella", "☂", "snowman", "☃",
            "smile", "☺", "frown", "☹", "music", "♪", "notes", "♫", "check", "✔", "x", "✖", "skull", "☠", "zap", "⚡",
            "flower", "✿", "sparkle", "✦", "diamond", "♦", "club", "♣", "spade", "♠", "peace", "☮", "yinyang", "☯",
            "arrow", "➜", "left", "←", "right", "→", "up", "↑", "down", "↓", "crown", "♛", "sword", "⚔", "flag", "⚑",
            "phone", "☎", "mail", "✉", "pencil", "✎", "scissors", "✂", "coffee", "☕", "hot", "♨", "moon", "☾",
            "infinity", "∞", "trans", "⚧", "female", "♀", "male", "♂", "recycle", "♻", "warning", "⚠", "rainbow", "☼",
            "shrug", "¯\\_(ツ)_/¯", "tableflip", "(╯°□°)╯︵ ┻━┻", "lenny", "( ͡° ͜ʖ ͡°)"};
        for (int i = 0; i + 1 < pairs.length; i += 2) EMOJI.put(pairs[i], pairs[i + 1]);
    }

    private ChatStyle() {}

    static boolean can(UUID id, String node, boolean fallback) { return PridePerms.allowed(id, node, fallback); }

    // ------------------------------------------------------------------ chat
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void chat(ServerChatEvent e) {
        EntityPlayerMP p = e.getPlayer();
        if (p == null) return;
        UUID id = p.getUniqueID();
        boolean op = PridePerms.isOp(id);
        String msg = e.getMessage();
        String out = style(msg, can(id, "prideperms.chat.color", op), can(id, "prideperms.chat.format", op), can(id, "prideperms.chat.emoji", true));
        if (!out.equals(msg)) e.setComponent(new TextComponentTranslation("chat.type.text", p.getDisplayName(), new TextComponentString(out)));
    }

    /** turn allowed &codes into colours and :shortcodes: into symbols */
    static String style(String msg, boolean colors, boolean formats, boolean emoji) {
        StringBuilder sb = new StringBuilder(msg.length() + 16);
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (c == '&' && i + 1 < msg.length()) {
                char k = Character.toLowerCase(msg.charAt(i + 1));
                boolean isColor = (k >= '0' && k <= '9') || (k >= 'a' && k <= 'f') || k == 'r';
                boolean isFormat = k >= 'k' && k <= 'o';
                if ((isColor && colors) || (isFormat && formats)) { sb.append('§').append(k); i++; continue; }
            }
            if (c == ':' && emoji) {
                int end = msg.indexOf(':', i + 1);
                if (end > i + 1 && end - i <= 12) {
                    String sym = EMOJI.get(msg.substring(i + 1, end).toLowerCase(Locale.ROOT));
                    if (sym != null) { sb.append(sym); i = end; continue; }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ /namecolor
    public static class NameColor extends CommandBase {
        @Override public String getName() { return "namecolor"; }
        @Override public List<String> getAliases() { return Collections.singletonList("nc"); }
        @Override public String getUsage(ICommandSender s) { return "/namecolor <colour> [bold] [italic] [underline] | reset | list"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean checkPermission(MinecraftServer server, ICommandSender s) { return true; }

        @Override
        public void execute(MinecraftServer server, ICommandSender s, String[] a) throws CommandException {
            EntityPlayerMP p = getCommandSenderAsPlayer(s);
            PermStore st = PridePerms.store;
            if (st == null) throw new CommandException("No world loaded.");
            if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
                StringBuilder sb = new StringBuilder("§dColours you can use: ");
                for (TextFormatting f : TextFormatting.values())
                    if (f.isColor() && can(p.getUniqueID(), "prideperms.namecolor." + f.getFriendlyName(), true)) sb.append(f).append(f.getFriendlyName()).append("§r ");
                s.sendMessage(new TextComponentString(sb.toString()));
                s.sendMessage(new TextComponentString("§7/namecolor <colour> [bold] [italic] [underline] · /namecolor reset"));
                return;
            }
            PermStore.Player pd = st.player(p.getUniqueID(), p.getName());
            if (a[0].equalsIgnoreCase("reset")) {
                pd.meta.remove("namecolor");
                st.save();
                s.sendMessage(new TextComponentString("§dYour name colour is back to your rank's."));
                return;
            }
            TextFormatting color = TextFormatting.getValueByName(a[0].toLowerCase(Locale.ROOT));
            if (color == null || !color.isColor()) throw new CommandException("Unknown colour. /namecolor list shows yours.");
            if (!can(p.getUniqueID(), "prideperms.namecolor." + color.getFriendlyName(), true)) throw new CommandException("Your rank can't use " + color.getFriendlyName() + ".");
            StringBuilder code = new StringBuilder(color.toString());
            for (int i = 1; i < a.length; i++) {
                String st2 = a[i].toLowerCase(Locale.ROOT);
                TextFormatting f = st2.equals("bold") ? TextFormatting.BOLD : st2.equals("italic") ? TextFormatting.ITALIC
                        : st2.equals("underline") ? TextFormatting.UNDERLINE : null;
                if (f == null) throw new CommandException("Styles: bold, italic, underline");
                if (!can(p.getUniqueID(), "prideperms.namecolor." + st2, true)) throw new CommandException("Your rank can't use " + st2 + ".");
                code.append(f);
            }
            pd.meta.put("namecolor", code.toString());
            st.save();
            s.sendMessage(new TextComponentString("§dYour name now looks like: " + code + p.getName()));
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender s, String[] a, @Nullable BlockPos pos) {
            List<String> opts = new ArrayList<>();
            if (a.length == 1) { opts.add("list"); opts.add("reset"); for (TextFormatting f : TextFormatting.values()) if (f.isColor()) opts.add(f.getFriendlyName()); }
            else { opts.add("bold"); opts.add("italic"); opts.add("underline"); }
            return getListOfStringsMatchingLastWord(a, opts);
        }
    }
}
