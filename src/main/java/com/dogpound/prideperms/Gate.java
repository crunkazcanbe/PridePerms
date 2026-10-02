package com.dogpound.prideperms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JOIN GATE (her idea, 2026-09-30): nobody plays until they have an account on the Pride website.
 *
 *   join → LOCKED: held at the gate spot (or allowed to look around within gateGuestRadius), can't break, place,
 *          use, fight, pick up, drop, chat or run commands except /verify, and can't be hurt. A Pride screen says
 *          what to do.
 *   site → the player makes an account, types their Minecraft name, gets a one-time code (the site sends it to
 *          the game through the signed web inbox: action verify.code).
 *   game → /verify <code>  (or the box on the screen) → rules quiz if one is set → UNLOCKED: rainbow fireworks,
 *          starter kit, welcome coins, the "member" rank, and the Minecraft account is linked to the site account.
 *
 *   Staff 2-step: an op who joins from a new internet address is locked again until they verify again.
 *   One site account can link gateMaxPerSiteAccount Minecraft accounts (alts are refused past that).
 *   Admins: /verify give|giveall|revoke|code|list|status|setspawn|on|off.
 * Data: <world>/prideperms-gate.json. Off until gateEnabled = true.
 */
public final class Gate {
    private Gate() {}

    // ------------------------------------------------------------------ saved data
    static final class Linked { String name, account; long at; String ip; }
    static final class Pending { String player, account; long expires; }   // player = lower-case name or uuid
    static final class Spot { int dim; double x, y, z; float yaw, pitch; }
    static final class Data {
        Map<String, Linked> verified = new LinkedHashMap<>();                // uuid → link
        Map<String, Pending> codes = new LinkedHashMap<>();                  // CODE → who it is for
        Spot spawn;
    }

    // ------------------------------------------------------------------ live state (not saved)
    static final class Lock {
        Spot anchor;
        boolean quiz;                                                        // false = waiting for a code, true = answering questions
        int question;
        String account;                                                      // the site account the accepted code belongs to
        String reason;                                                       // shown on the screen
        int ticks;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Lock> LOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> TRIES = new ConcurrentHashMap<>(); // {count, window start} — kept across relogs
    private static Data data = new Data();
    private static File file;

    static void load(File worldDir) {
        file = new File(worldDir, "prideperms-gate.json");
        data = new Data();
        LOCKS.clear();
        try {
            if (file.isFile()) {
                Data d = GSON.fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), Data.class);
                if (d != null) data = d;
            }
        } catch (Exception e) { PridePerms.LOG.warn("gate data unreadable, starting empty: " + e); }
        if (data.verified == null) data.verified = new LinkedHashMap<>();
        if (data.codes == null) data.codes = new LinkedHashMap<>();
    }

    static synchronized void save() {
        if (file == null) return;
        try { WebHook.write(file, GSON.toJson(data)); } catch (Exception e) { PridePerms.LOG.warn("gate save failed: " + e); }
        WebHook.dirty = true;
    }

    static boolean locked(EntityPlayer p) { return p != null && !p.world.isRemote && LOCKS.containsKey(p.getUniqueID()); }

    static Map<String, Linked> verified() { return data.verified; }

    static int codesWaiting() { synchronized (Gate.class) { prune(); return data.codes.size(); } }

    static boolean spotSet() { return data.spawn != null; }

    static List<String> lockedNames(MinecraftServer srv) {
        List<String> out = new ArrayList<>();
        for (UUID id : LOCKS.keySet()) { EntityPlayerMP p = srv.getPlayerList().getPlayerByUUID(id); if (p != null) out.add(p.getName()); }
        return out;
    }

    // ------------------------------------------------------------------ pure rules (GateCheck tests these)
    /** codes are typed by people: case, spaces and dashes don't matter */
    static String normalize(String code) { return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }

    /** is `p` a live code for this player (by name or uuid)? */
    static boolean codeFits(Pending p, String name, UUID id, long now) {
        return p != null && p.expires > now && (p.player.equalsIgnoreCase(name) || p.player.equalsIgnoreCase(id.toString()));
    }

    /** how many OTHER Minecraft accounts this site account already has */
    static int linkedCount(Map<String, Linked> verified, String account, UUID except) {
        int n = 0;
        for (Map.Entry<String, Linked> e : verified.entrySet())
            if (account != null && account.equalsIgnoreCase(e.getValue().account) && !e.getKey().equals(except.toString())) n++;
        return n;
    }

    /** "question | answer" → answer matches (case, spaces and a trailing period don't matter) */
    static boolean answerFits(String line, String given) {
        int bar = line.lastIndexOf('|');
        if (bar < 0) return true;
        String want = line.substring(bar + 1).trim().toLowerCase(Locale.ROOT);
        String got = given.trim().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
        for (String w : want.split("/")) if (w.trim().equals(got)) return true;
        return false;
    }

    static String question(String line) { int bar = line.lastIndexOf('|'); return (bar < 0 ? line : line.substring(0, bar)).trim(); }

    // ------------------------------------------------------------------ codes from the site (WebHook action verify.code)
    /** args: {name: "<minecraft name>" | uuid: "<uuid>", code: "<code>", account: "<site account id or email>", minutes?} */
    static String siteCode(JsonObject a) {
        String who = a.has("uuid") ? a.get("uuid").getAsString() : a.get("name").getAsString();
        String code = normalize(a.get("code").getAsString());
        if (code.length() < 4 || code.length() > 16) throw new IllegalArgumentException("code must be 4-16 letters/digits");
        Pending p = new Pending();
        p.player = who.toLowerCase(Locale.ROOT);
        p.account = a.has("account") ? a.get("account").getAsString() : "site";
        int minutes = a.has("minutes") ? Math.max(1, Math.min(60, a.get("minutes").getAsInt())) : PermsConfig.gateCodeMinutes;
        p.expires = System.currentTimeMillis() + minutes * 60_000L;
        synchronized (Gate.class) { prune(); data.codes.put(code, p); }
        save();
        return "code ready for " + who + " (" + minutes + " min)";
    }

    /** args: {name | uuid} — the site account was closed: unlink (they are locked next join) */
    static String siteRevoke(JsonObject a) {
        MinecraftServer srv = server();
        String who = a.has("uuid") ? a.get("uuid").getAsString() : a.get("name").getAsString();
        String id = uuidOf(srv, who);
        if (id == null || data.verified.remove(id) == null) return "was not linked";
        save();
        EntityPlayerMP p = srv.getPlayerList().getPlayerByUUID(UUID.fromString(id));
        if (p != null) lock(p, "Your website account was closed.");
        return "unlinked";
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        data.codes.values().removeIf(p -> p.expires <= now);
    }

    // ------------------------------------------------------------------ lock / unlock
    static boolean exempt(EntityPlayerMP p) {
        MinecraftServer srv = p.getServer();
        if (Owner.isOwner(p.getUniqueID())) return true;                                            // the server owner (config ownerUuids)
        return PermsConfig.gateExemptOwner && srv != null && srv.isSinglePlayer() && p.getName().equals(srv.getServerOwner());   // your own world
    }

    static void lock(EntityPlayerMP p, String reason) {
        Lock l = new Lock();
        l.anchor = data.spawn != null && data.spawn.dim == p.dimension ? data.spawn : here(p);
        l.reason = reason;
        LOCKS.put(p.getUniqueID(), l);
        if (data.spawn != null && data.spawn.dim == p.dimension) put(p, l.anchor);
        screen(p, "");
    }

    static void unlock(EntityPlayerMP p, String account, boolean welcome) {
        LOCKS.remove(p.getUniqueID());
        TRIES.remove(p.getUniqueID());
        Linked l = data.verified.get(p.getUniqueID().toString());
        boolean first = l == null;
        if (l == null) l = new Linked();
        l.name = p.getName();
        l.account = account != null ? account : l.account;
        l.at = System.currentTimeMillis();
        l.ip = ip(p);
        data.verified.put(p.getUniqueID().toString(), l);
        save();
        Net.CH.sendTo(new Net.GateScreen(false, "", "", "", ""), p);
        if (!welcome) return;
        celebrate(p);
        if (first) {
            giveRank(p);
            giveKit(p);
            giveCoins(p);
        }
        Verbose.log(p.getServer(), p.getName(), "verified (site account " + l.account + ")");
    }

    private static void giveRank(EntityPlayerMP p) {
        PermStore st = PridePerms.store;
        String g = PermsConfig.gateVerifiedGroup.toLowerCase(Locale.ROOT);
        if (st == null || g.isEmpty() || !st.groups.containsKey(g)) return;
        PermStore.Player pd = st.player(p.getUniqueID(), p.getName());
        if (!pd.groups.contains(g)) { pd.groups.add(g); st.save(); }
    }

    private static void giveKit(EntityPlayerMP p) {
        for (String line : PermsConfig.gateStarterKit) {
            String[] w = line.trim().split("\\s+");
            if (w.length == 0 || w[0].isEmpty()) continue;
            String[] id = w[0].split(":");
            Item item = Item.getByNameOrId(id.length >= 2 ? id[0] + ":" + id[1] : w[0]);
            if (item == null) { PridePerms.LOG.warn("starter kit: no item " + w[0]); continue; }
            int meta = id.length >= 3 ? parse(id[2], 0) : 0, count = w.length > 1 ? parse(w[1], 1) : 1;
            while (count > 0) {
                ItemStack s = new ItemStack(item, Math.min(count, item.getItemStackLimit()), meta);
                count -= s.getCount();
                if (!p.inventory.addItemStackToInventory(s)) p.dropItem(s, false);
            }
        }
    }

    private static void giveCoins(EntityPlayerMP p) {
        if (PermsConfig.gateWelcomeCoins <= 0) return;
        String r = RankShop.move(p, Math.round(PermsConfig.gateWelcomeCoins * 100), "Welcome to Pride", true);
        if ("OK".equals(r)) p.sendMessage(new TextComponentString("§d✦ §f" + PermsConfig.gateWelcomeCoins + " welcome coins §7are in your account."));
        else if (r != null) PridePerms.LOG.warn("welcome coins not paid: " + r);
    }

    private static final int[] RAINBOW = {0xE40303, 0xFF8C00, 0xFFED00, 0x008026, 0x24408E, 0x732982, 0x5BCEFA, 0xF5A9B8};

    private static void celebrate(EntityPlayerMP p) {
        p.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TIMES, null, 10, 70, 20));
        p.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, new TextComponentString(rainbow("Welcome to Pride!"))));
        p.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, new TextComponentString("§fYou're verified — have fun ❤")));
        p.world.playSound(null, p.posX, p.posY, p.posZ, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
        for (int i = 0; i < 3; i++) {
            NBTTagCompound boom = new NBTTagCompound();
            boom.setByte("Type", (byte) (i == 1 ? 4 : 1));
            boom.setIntArray("Colors", RAINBOW);
            boom.setIntArray("FadeColors", new int[]{0xFFFFFF});
            boom.setBoolean("Trail", true);
            boom.setBoolean("Flicker", true);
            NBTTagList list = new NBTTagList();
            list.appendTag(boom);
            NBTTagCompound fw = new NBTTagCompound();
            fw.setTag("Explosions", list);
            fw.setByte("Flight", (byte) 1);
            ItemStack rocket = new ItemStack(Items.FIREWORKS);
            rocket.setTagInfo("Fireworks", fw);
            p.world.spawnEntity(new EntityFireworkRocket(p.world, p.posX + (i - 1) * 2, p.posY + 1, p.posZ + (i - 1), rocket));
        }
    }

    static String rainbow(String s) {
        String[] c = {"§c", "§6", "§e", "§a", "§b", "§9", "§d"};
        StringBuilder b = new StringBuilder("§l");
        int n = 0;
        for (char ch : s.toCharArray()) { b.append(ch == ' ' ? "" : c[n++ % c.length]).append(ch == ' ' ? "" : "§l").append(ch); }
        return b.toString();
    }

    // ------------------------------------------------------------------ what the player types: /verify <code or answer>
    static void attempt(EntityPlayerMP p, String text) {
        Lock l = LOCKS.get(p.getUniqueID());
        if (l == null) { p.sendMessage(new TextComponentString("§aYou're already verified.")); return; }
        long now = System.currentTimeMillis();
        long[] t = TRIES.computeIfAbsent(p.getUniqueID(), k -> new long[]{0, now});
        if (now - t[1] > 600_000) { t[0] = 0; t[1] = now; }
        if (t[0] >= 8) { screen(p, "§cToo many tries — wait a few minutes."); return; }

        if (l.quiz) {
            String[] qs = PermsConfig.gateQuiz;
            if (!answerFits(qs[l.question], text)) { t[0]++; screen(p, "§cNot quite — read the question again."); return; }
            l.question++;
            if (l.question >= qs.length) unlock(p, l.account, true);
            else screen(p, "§aRight!");
            return;
        }

        String code = normalize(text);
        Pending pend;
        synchronized (Gate.class) { prune(); pend = data.codes.get(code); }
        if (!codeFits(pend, p.getName(), p.getUniqueID(), now)) { t[0]++; screen(p, "§cThat code isn't right (or it ran out). Get a new one on the website."); return; }
        if (PermsConfig.gateMaxPerSiteAccount > 0 && linkedCount(data.verified, pend.account, p.getUniqueID()) >= PermsConfig.gateMaxPerSiteAccount) {
            screen(p, "§cThat website account already has a Minecraft account linked.");
            return;
        }
        synchronized (Gate.class) { data.codes.remove(code); }
        save();
        l.account = pend.account;
        if (PermsConfig.gateQuiz.length > 0 && !data.verified.containsKey(p.getUniqueID().toString())) {
            l.quiz = true;
            l.question = 0;
            screen(p, "§aCode accepted! §fA few quick questions about the rules:");
        } else unlock(p, l.account, true);
    }

    /** send (or refresh) the Pride screen for a locked player */
    static void screen(EntityPlayerMP p, String feedback) {
        Lock l = LOCKS.get(p.getUniqueID());
        if (l == null) return;
        String stage, text;
        if (l.quiz) {
            stage = "quiz";
            text = "Question " + (l.question + 1) + " of " + PermsConfig.gateQuiz.length + ": " + question(PermsConfig.gateQuiz[l.question]);
        } else {
            stage = "code";
            text = l.reason == null ? "" : l.reason;
        }
        Net.CH.sendTo(new Net.GateScreen(true, stage, text, PermsConfig.gateSiteUrl, feedback), p);
        if (!feedback.isEmpty()) p.sendMessage(new TextComponentString(feedback));
    }

    // ------------------------------------------------------------------ joining, leaving, the hold
    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.player instanceof EntityPlayerMP) || !PermsConfig.gateEnabled || file == null) return;
        EntityPlayerMP p = (EntityPlayerMP) e.player;
        if (exempt(p)) return;
        Linked l = data.verified.get(p.getUniqueID().toString());
        if (l == null) { lock(p, ""); return; }
        if (!l.name.equals(p.getName())) { l.name = p.getName(); save(); }
        if (PermsConfig.gateStaffIpCheck && PridePerms.isOp(p.getUniqueID()) && l.ip != null && !l.ip.equals(ip(p)))
            lock(p, "Staff check: you joined from a new place. Get a new code on the website to confirm it's you.");
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { LOCKS.remove(e.player.getUniqueID()); }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP p = (EntityPlayerMP) e.player;
        Lock l = LOCKS.get(p.getUniqueID());
        if (l == null) return;
        if (!PermsConfig.gateEnabled) { LOCKS.remove(p.getUniqueID()); Net.CH.sendTo(new Net.GateScreen(false, "", "", "", ""), p); return; }
        double r = Math.max(0.6, PermsConfig.gateGuestRadius);
        if (p.dimension != l.anchor.dim || p.getDistanceSq(l.anchor.x, l.anchor.y, l.anchor.z) > r * r) put(p, l.anchor);
        p.extinguish();
        if (++l.ticks % 600 == 1) {                                          // every 30 s: the reminder, with the link
            ITextComponent m = new TextComponentString("§d✪ §fYou're locked until you verify. §7Get your code at ");
            ITextComponent link = new TextComponentString("§b§n" + (PermsConfig.gateSiteUrl.isEmpty() ? "the Pride website" : PermsConfig.gateSiteUrl));
            if (!PermsConfig.gateSiteUrl.isEmpty()) link.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, PermsConfig.gateSiteUrl));
            m.appendSibling(link).appendSibling(new TextComponentString(" §7then type §f/verify <code>"));
            p.sendMessage(m);
        }
    }

    // ------------------------------------------------------------------ a locked player touches nothing
    private static boolean held(EntityPlayer p) {
        if (!locked(p)) return false;
        p.sendStatusMessage(new TextComponentString("§d✪ Verify first — §f/verify <code>"), true);
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void breakBlock(BlockEvent.BreakEvent e) { if (held(e.getPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void place(BlockEvent.PlaceEvent e) { if (held(e.getPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useBlock(PlayerInteractEvent.RightClickBlock e) { if (held(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useItem(PlayerInteractEvent.RightClickItem e) { if (held(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void hitBlock(PlayerInteractEvent.LeftClickBlock e) { if (held(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(PlayerInteractEvent.EntityInteract e) { if (held(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interactAt(PlayerInteractEvent.EntityInteractSpecific e) { if (held(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent e) { if (held(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void pickup(EntityItemPickupEvent e) { if (locked(e.getEntityPlayer())) e.setCanceled(true); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void toss(ItemTossEvent e) {
        if (!held(e.getPlayer())) return;
        ItemStack s = e.getEntityItem().getItem();                          // give it back instead of deleting it
        e.setCanceled(true);
        if (!e.getPlayer().inventory.addItemStackToInventory(s)) e.getPlayer().dropItem(s, false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void openMenu(PlayerContainerEvent.Open e) { if (held(e.getEntityPlayer())) e.getEntityPlayer().closeScreen(); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mount(EntityMountEvent e) {
        if (e.isMounting() && e.getEntityMounting() instanceof EntityPlayer && held((EntityPlayer) e.getEntityMounting())) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void travel(EntityTravelToDimensionEvent e) {
        if (e.getEntity() instanceof EntityPlayer && held((EntityPlayer) e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void hurt(LivingAttackEvent e) {                         // locked players can't be hurt (and can't hurt)
        if (e.getEntityLiving() instanceof EntityPlayer && locked((EntityPlayer) e.getEntityLiving())) e.setCanceled(true);
        else if (e.getSource().getTrueSource() instanceof EntityPlayer && locked((EntityPlayer) e.getSource().getTrueSource())) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void chat(ServerChatEvent e) {
        if (!locked(e.getPlayer())) return;
        e.setCanceled(true);
        screen(e.getPlayer(), "§7Chat opens once you're verified. Type your code with §f/verify <code>");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void command(CommandEvent e) {
        if (!(e.getSender() instanceof EntityPlayer) || !locked((EntityPlayer) e.getSender())) return;
        String name = e.getCommand().getName();
        for (String ok : PermsConfig.gateAllowedCommands) if (ok.equalsIgnoreCase(name)) return;
        e.setCanceled(true);
        held((EntityPlayer) e.getSender());
    }

    // ------------------------------------------------------------------ /verify
    public static final class Command extends CommandBase {
        @Override public String getName() { return "verify"; }
        @Override public String getUsage(ICommandSender s) { return "/verify <code>  ·  admins: /verify give|giveall|revoke|code|list|status|setspawn|on|off"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean checkPermission(MinecraftServer server, ICommandSender s) { return true; }

        private static boolean admin(ICommandSender s) { return s.canUseCommand(3, "perms"); }

        private static void say(ICommandSender s, String m) { s.sendMessage(new TextComponentString(m)); }

        @Override
        public void execute(MinecraftServer srv, ICommandSender s, String[] a) throws CommandException {
            if (file == null) throw new CommandException("No world loaded.");
            String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
            if (admin(s) && ADMIN.contains(sub)) { adminCmd(srv, s, sub, a); return; }
            EntityPlayerMP p = getCommandSenderAsPlayer(s);
            if (a.length == 0) {
                if (locked(p)) screen(p, "");
                else say(s, data.verified.containsKey(p.getUniqueID().toString()) ? "§aYou're verified. ❤" : "§7The join gate is off.");
                return;
            }
            attempt(p, String.join(" ", a));
        }

        private static final List<String> ADMIN = Arrays.asList("give", "giveall", "revoke", "code", "list", "status", "setspawn", "on", "off");

        private void adminCmd(MinecraftServer srv, ICommandSender s, String sub, String[] a) throws CommandException {
            switch (sub) {
                case "on": case "off":
                    PermsConfig.gateEnabled = sub.equals("on");
                    ConfigManager.sync(PridePerms.MODID, Config.Type.INSTANCE);
                    if (PermsConfig.gateEnabled) for (EntityPlayerMP p : srv.getPlayerList().getPlayers())
                        if (!exempt(p) && !data.verified.containsKey(p.getUniqueID().toString())) lock(p, "");
                    say(s, "§d✦ Join gate " + (PermsConfig.gateEnabled ? "§aON §7— unverified players are locked until they verify." : "§cOFF"));
                    return;
                case "setspawn": {
                    EntityPlayerMP me = getCommandSenderAsPlayer(s);
                    data.spawn = here(me);
                    save();
                    say(s, "§d✦ Locked players wait here now.");
                    return;
                }
                case "list": {
                    say(s, "§d✦ Verified: §f" + data.verified.size() + " §7· waiting codes: §f" + data.codes.size() + " §7· locked now: §f" + LOCKS.size());
                    for (Map.Entry<String, Linked> e : data.verified.entrySet())
                        say(s, " §f" + e.getValue().name + " §7→ " + e.getValue().account);
                    return;
                }
                case "giveall": {
                    int n = 0;
                    for (EntityPlayerMP p : srv.getPlayerList().getPlayers()) if (locked(p)) { unlock(p, "admin", false); n++; }
                    for (EntityPlayerMP p : srv.getPlayerList().getPlayers())
                        if (!data.verified.containsKey(p.getUniqueID().toString())) { unlock(p, "admin", false); n++; }
                    say(s, "§d✦ Verified " + n + " online player(s).");
                    return;
                }
            }
            if (a.length < 2) throw new CommandException("/verify " + sub + " <player>");
            String target = a[1];
            EntityPlayerMP online = srv.getPlayerList().getPlayerByUsername(target);
            switch (sub) {
                case "give":
                    if (online == null) throw new CommandException(target + " must be online.");
                    unlock(online, "admin:" + s.getName(), true);
                    say(s, "§d✦ " + online.getName() + " is verified.");
                    return;
                case "revoke": {
                    String id = online != null ? online.getUniqueID().toString() : uuidOf(srv, target);
                    if (id == null || data.verified.remove(id) == null) throw new CommandException(target + " wasn't verified.");
                    save();
                    if (online != null) lock(online, "An admin reset your verification.");
                    say(s, "§d✦ " + target + " must verify again.");
                    return;
                }
                case "code": {                                           // an admin hands a code out by hand (no website needed)
                    String code = newCode();
                    JsonObject o = new JsonObject();
                    o.addProperty("name", target);
                    o.addProperty("code", code);
                    o.addProperty("account", a.length > 2 ? a[2] : "admin:" + s.getName());
                    siteCode(o);
                    say(s, "§d✦ Code for " + target + ": §f§l" + code + " §7(" + PermsConfig.gateCodeMinutes + " min, one use)");
                    return;
                }
                case "status": {
                    String id = online != null ? online.getUniqueID().toString() : uuidOf(srv, target);
                    Linked l = id == null ? null : data.verified.get(id);
                    say(s, l == null ? "§7" + target + " is not verified." + (online != null && locked(online) ? " §d(locked now)" : "")
                            : "§a" + l.name + " §7verified " + new Date(l.at) + " · site account §f" + l.account);
                    return;
                }
            }
            throw new CommandException(getUsage(s));
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer srv, ICommandSender s, String[] a, @Nullable BlockPos pos) {
            if (!admin(s)) return Collections.emptyList();
            if (a.length == 1) return getListOfStringsMatchingLastWord(a, ADMIN);
            if (a.length == 2) return getListOfStringsMatchingLastWord(a, srv.getOnlinePlayerNames());
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ helpers
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";    // no 0/O or 1/I mix-ups

    static String newCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return b.toString();
    }

    private static Spot here(EntityPlayerMP p) {
        Spot s = new Spot();
        s.dim = p.dimension; s.x = p.posX; s.y = p.posY; s.z = p.posZ; s.yaw = p.rotationYaw; s.pitch = p.rotationPitch;
        return s;
    }

    private static void put(EntityPlayerMP p, Spot s) {
        if (p.dimension == s.dim) p.connection.setPlayerLocation(s.x, s.y, s.z, p.rotationYaw, p.rotationPitch);
    }

    private static String ip(EntityPlayerMP p) {
        String a = p.getPlayerIP();
        return a == null ? "" : a;
    }

    private static int parse(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }

    private static MinecraftServer server() { return FMLCommonHandler.instance().getMinecraftServerInstance(); }

    private static String uuidOf(MinecraftServer srv, String nameOrId) {
        try { return UUID.fromString(nameOrId).toString(); } catch (IllegalArgumentException ignored) {}
        for (Map.Entry<String, Linked> e : data.verified.entrySet()) if (e.getValue().name.equalsIgnoreCase(nameOrId)) return e.getKey();
        com.mojang.authlib.GameProfile gp = srv.getPlayerProfileCache().getGameProfileForUsername(nameOrId);
        return gp == null ? null : gp.getId().toString();
    }
}
