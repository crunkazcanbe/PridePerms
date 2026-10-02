package com.dogpound.prideperms;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.UUID;

/**
 * PridePerms — ranks and permissions for every mod in the pack. Rules live in the world folder (prideperms.json),
 * edited with /perms (and the menu). It also answers Forge's PermissionAPI, so mods that already ask Forge
 * (Ender IO, Vampirism, Custom NPCs, FVTM, MineColonies, Techguns…) use these ranks too.
 */
@Mod(modid = PridePerms.MODID, name = "PridePerms", version = PridePerms.VERSION, acceptableRemoteVersions = "*")
public class PridePerms {
    public static final String MODID = "prideperms", VERSION = "0.1.0";
    public static final Logger LOG = LogManager.getLogger("PridePerms");

    static volatile PermStore store;                                  // null = no world loaded

    @net.minecraftforge.fml.common.SidedProxy(clientSide = "com.dogpound.prideperms.client.ClientProxy", serverSide = "com.dogpound.prideperms.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void pre(FMLPreInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(Guard.class);
        MinecraftForge.EVENT_BUS.register(AutoRank.class);
        MinecraftForge.EVENT_BUS.register(ChatStyle.class);
        MinecraftForge.EVENT_BUS.register(WebHook.class);
        MinecraftForge.EVENT_BUS.register(Gate.class);
        MinecraftForge.EVENT_BUS.register(Owner.class);
        Net.register();
        if (PermsConfig.forgeHandler) Handler.install();
    }

    @Mod.EventHandler
    public void init(net.minecraftforge.fml.common.event.FMLInitializationEvent e) { proxy.init(); LOG.info("[PridePerms] loaded OK"); }

    @Mod.EventHandler
    public void start(FMLServerStartingEvent e) {
        File dir = e.getServer().getEntityWorld().getSaveHandler().getWorldDirectory();
        store = PermStore.load(new File(dir, "prideperms.json"));
        Owner.ensureGroup(store);
        Gate.load(dir);
        e.registerServerCommand(new PermsCommand());
        if (!e.getServer().getCommandManager().getCommands().containsKey("namecolor")) e.registerServerCommand(new ChatStyle.NameColor());
        if (!e.getServer().getCommandManager().getCommands().containsKey("rank")) e.registerServerCommand(new RankShop());
        if (!e.getServer().getCommandManager().getCommands().containsKey("cmds")) e.registerServerCommand(new CmdMenu.Command());
        if (!e.getServer().getCommandManager().getCommands().containsKey("verify")) e.registerServerCommand(new Gate.Command());
        LOG.info("{} groups, {} players with rules", store.groups.size(), store.players.size());
    }

    @Mod.EventHandler
    public void stop(FMLServerStoppedEvent e) {
        if (store != null) store.save();
        store = null;
    }

    // ------------------------------------------------------------------ the API other mods (and ours) call
    /** TRUE / FALSE if a rule decides, null if nobody said. Ops count as members of "admin" (config). */
    public static Boolean decide(UUID id, String node) {
        PermStore s = store;
        if (s == null || id == null || node == null) return null;
        Known.seen(node);
        Boolean r = s.decide(id, node, PermsConfig.opsAreAdmin && isOp(id), dimOf(id), landOf(id));
        Verbose.checked(id, node, r);
        return r;
    }

    /** for command permissions: ops do NOT automatically count as admin here (so a level-1 op can't get /stop from
     *  admin's "*"); ops keep vanilla's op levels unless a rule is written for them or their groups. */
    public static Boolean decideCommand(UUID id, String node) {
        PermStore s = store;
        if (s == null || id == null || node == null) return null;
        Known.seen(node);
        Boolean r = s.decide(id, node, false, dimOf(id), landOf(id));
        Verbose.checked(id, node, r);
        return r;
    }

    /** the answer, or `fallback` when no rule says anything */
    public static boolean allowed(UUID id, String node, boolean fallback) {
        Boolean d = decide(id, node);
        return d != null ? d : fallback;
    }

    /** is the player in this group, or in a higher group that inherits it? (PrideQuests "reach a rank" tasks) */
    public static boolean inGroup(UUID id, String group) {
        PermStore s = store;
        if (s == null || id == null || group == null) return false;
        boolean op = false;
        try {
            MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
            net.minecraft.entity.player.EntityPlayerMP p = srv == null ? null : srv.getPlayerList().getPlayerByUUID(id);
            op = p != null && srv.getPlayerList().canSendCommands(p.getGameProfile());
        } catch (Throwable ignored) {}
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Deque<PermStore.Group> todo = new java.util.ArrayDeque<>(s.groupsOf(s.player(id, null), op));
        while (!todo.isEmpty()) {
            PermStore.Group g = todo.pop();
            if (g == null || !seen.add(g.name)) continue;
            if (g.name.equalsIgnoreCase(group)) return true;
            for (String par : g.parents) todo.push(s.groups.get(par));
        }
        return false;
    }

    /** the kind of Pride Realms land an online player stands on (own / trusted / others / wild), null if unknown */
    static String landOf(UUID id) {
        try {
            MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
            net.minecraft.entity.player.EntityPlayerMP p = srv == null ? null : srv.getPlayerList().getPlayerByUUID(id);
            return p == null ? null : RealmLand.at(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** the dimension an online player is in (per-dimension rules), or "anywhere" when offline */
    static int dimOf(UUID id) {
        try {
            MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
            net.minecraft.entity.player.EntityPlayerMP p = srv == null ? null : srv.getPlayerList().getPlayerByUUID(id);
            return p == null ? PermStore.ANY_DIM : p.dimension;
        } catch (Throwable t) {
            return PermStore.ANY_DIM;
        }
    }

    static boolean isOp(UUID id) {
        try {
            MinecraftServer srv = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (srv == null) return false;
            GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(id);
            return gp != null && srv.getPlayerList().canSendCommands(gp);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean handlerIsOurs() { return PermissionAPI.getPermissionHandler() instanceof Handler; }
}
