package com.dogpound.prideperms;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every mod gets permissions without knowing about this mod: each player action Forge reports becomes a node named
 * after the mod that owns the thing, e.g.  mekanism.break.digital_miner · ic2.use.te · minecraft.attack.villager ·
 * techguns.item.ak47 · command.tp · dimension.7.enter.   Nothing set anywhere = allowed (normal play keeps working);
 * an admin denies what they want, per group or per player, one thing or a whole mod ("mekanism.*").
 * Runs on the server side only (single player = the built-in server), so a modded client can't skip it.
 */
public final class Guard {
    private static final Map<UUID, Long> lastMsg = new ConcurrentHashMap<>();

    private Guard() {}

    // ------------------------------------------------------------------ node names
    static String of(ResourceLocation id, String action) {
        if (id == null) return "unknown." + action + ".unknown";
        return clean(id.getResourceDomain()) + "." + action + "." + clean(id.getResourcePath());
    }

    static String clean(String s) { return s.toLowerCase(java.util.Locale.ROOT).replace('.', '_').replace(' ', '_'); }

    static String block(Block b, String action) { return of(b.getRegistryName(), action); }

    static String item(ItemStack s, String action) { return s.isEmpty() ? null : of(s.getItem().getRegistryName(), action); }

    static String entity(Entity e, String action) {
        if (e instanceof EntityPlayer) return "minecraft." + action + ".player";
        return of(EntityList.getKey(e), action);
    }

    // ------------------------------------------------------------------ the check
    /** true = allowed. Fake players (machines) and the server console always pass unless config says otherwise. */
    static boolean allowed(EntityPlayer p, String node) {
        if (node == null || p == null || p.world.isRemote) return true;
        if (p instanceof FakePlayer) return !PermsConfig.checkFakePlayers || PridePerms.allowed(p.getGameProfile().getId(), node, true);
        return PridePerms.allowed(p.getUniqueID(), node, true);
    }

    static boolean deny(EntityPlayer p, String node) {
        if (allowed(p, node)) return false;
        long now = System.currentTimeMillis();
        Long last = lastMsg.get(p.getUniqueID());
        if (p instanceof EntityPlayerMP && !(p instanceof FakePlayer) && (last == null || now - last > 1500)) {
            lastMsg.put(p.getUniqueID(), now);
            p.sendStatusMessage(new TextComponentString("§c✖ Not allowed §7(" + node + ")"), true);
        }
        return true;
    }

    // ------------------------------------------------------------------ blocks
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void breakBlock(BlockEvent.BreakEvent e) {
        if (deny(e.getPlayer(), block(e.getState().getBlock(), "break"))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void place(BlockEvent.PlaceEvent e) {
        if (deny(e.getPlayer(), block(e.getPlacedBlock().getBlock(), "place"))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void useBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getWorld().isRemote) return;
        Block b = e.getWorld().getBlockState(e.getPos()).getBlock();
        if (deny(e.getEntityPlayer(), block(b, "use"))) e.setUseBlock(Event.Result.DENY);
        String it = item(e.getItemStack(), "item");
        if ((it != null && deny(e.getEntityPlayer(), it)) || deny(e.getEntityPlayer(), locksAlias(e.getItemStack()))) e.setUseItem(Event.Result.DENY);
        if (e.getUseBlock() == Event.Result.DENY && e.getUseItem() == Event.Result.DENY) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void useItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getWorld().isRemote) return;
        if (deny(e.getEntityPlayer(), item(e.getItemStack(), "item")) || deny(e.getEntityPlayer(), locksAlias(e.getItemStack()))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void trample(BlockEvent.FarmlandTrampleEvent e) {
        if (e.getEntity() instanceof EntityPlayer && deny((EntityPlayer) e.getEntity(), "minecraft.trample.farmland")) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ entities
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void attack(AttackEntityEvent e) {
        if (deny(e.getEntityPlayer(), entity(e.getTarget(), "attack"))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void interact(PlayerInteractEvent.EntityInteract e) {
        if (!e.getWorld().isRemote && deny(e.getEntityPlayer(), entity(e.getTarget(), "interact"))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void interactAt(PlayerInteractEvent.EntityInteractSpecific e) {
        if (!e.getWorld().isRemote && deny(e.getEntityPlayer(), entity(e.getTarget(), "interact"))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void mount(EntityMountEvent e) {
        if (e.isMounting() && !e.getWorldObj().isRemote && e.getEntityMounting() instanceof EntityPlayer
                && deny((EntityPlayer) e.getEntityMounting(), entity(e.getEntityBeingMounted(), "ride"))) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ items
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void pickup(EntityItemPickupEvent e) {
        if (deny(e.getEntityPlayer(), item(e.getItem().getItem(), "pickup"))) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void toss(ItemTossEvent e) {
        ItemStack s = e.getEntityItem().getItem();
        if (deny(e.getPlayer(), item(s, "drop"))) {                         // give it back instead of deleting it
            e.setCanceled(true);
            if (!e.getPlayer().inventory.addItemStackToInventory(s)) e.getPlayer().dropItem(s, false);
        }
    }

    // ------------------------------------------------------------------ menus, places, commands
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void openMenu(PlayerContainerEvent.Open e) {
        String node = Owners.containerNode(e.getContainer());
        if (node != null && deny(e.getEntityPlayer(), node)) e.getEntityPlayer().closeScreen();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void travel(EntityTravelToDimensionEvent e) {
        if (e.getEntity() instanceof EntityPlayer && deny((EntityPlayer) e.getEntity(), "dimension." + e.getDimension() + ".enter"))
            e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void command(CommandEvent e) {
        if (!(e.getSender() instanceof EntityPlayer)) return;                // console and command blocks: vanilla rules
        EntityPlayer p = (EntityPlayer) e.getSender();
        Boolean said = PridePerms.decideCommand(p.getUniqueID(), "command." + clean(e.getCommand().getName()));
        if (Boolean.FALSE.equals(said)) {
            deny(p, "command." + clean(e.getCommand().getName()));
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ rank prefix in chat
    @SubscribeEvent
    public static void nameFormat(net.minecraftforge.event.entity.player.PlayerEvent.NameFormat e) {
        if (!PermsConfig.namePrefixes || e.getEntityPlayer().world.isRemote || PridePerms.store == null) return;
        java.util.UUID id = e.getEntityPlayer().getUniqueID();
        boolean op = PermsConfig.opsAreAdmin && PridePerms.isOp(id);
        String prefix = PridePerms.store.prefixOf(id, op), suffix = PridePerms.store.suffixOf(id, op);
        String name = e.getDisplayname();
        PermStore.Player pd = PridePerms.store.players.get(id.toString());
        String nc = pd == null ? "" : pd.meta.getOrDefault("namecolor", "");
        if (!nc.isEmpty()) name = nc + net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(name) + "§r";   // /namecolor
        if (!prefix.isEmpty()) name = prefix.matches("(§.)+") ? prefix + name + "§r" : prefix + " §r" + name;   // colour only, or a [Tag]
        if (!suffix.isEmpty()) name = name + "§r " + suffix + "§r";
        if (!name.equals(e.getDisplayname())) e.setDisplayname(name);
    }

    static String itemNode(Item i, String action) { return of(i.getRegistryName(), action); }

    /** Locks mod: short names on top of the per-item ones — locks.pick, locks.masterkey, locks.key, locks.lock */
    static String locksAlias(ItemStack s) {
        if (s.isEmpty() || s.getItem().getRegistryName() == null || !"locks".equals(s.getItem().getRegistryName().getResourceDomain())) return null;
        String path = s.getItem().getRegistryName().getResourcePath();
        if (path.contains("pick")) return "locks.pick";
        if (path.contains("master")) return "locks.masterkey";
        if (path.contains("key")) return "locks.key";
        if (path.contains("lock")) return "locks.lock";
        return null;
    }
}
