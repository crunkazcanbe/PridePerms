package com.dogpound.prideperms.bridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.server.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Pride Bridge, the world as a Lite player sees it. Every packet to a player whose client lacks some of the server's
 * mods passes through {@link #forPlayer}: blocks from a missing mod become a vanilla look-alike (same feel: solid stays
 * solid, liquid stays liquid, nearest colour), missing biomes become the nearest vanilla biome, and anything the client
 * couldn't even decode (machine data, block events, sounds, potions, particles of missing mods) is translated or left
 * out. Mobs from missing mods are hidden for now (see {@link #hides}).
 *
 * "Missing" is decided by registry namespace: a Lite client reported its mod ids at login; a thing whose namespace
 * isn't one of them (and isn't minecraft) is missing for that player.
 * ponytail: a mod registering under a namespace that isn't its mod id is treated as missing even for clients that
 * have it (they see the look-alike) — harmless, add an owner lookup if one ever matters.
 */
public final class Translate {
    /** what one player's client lacks; null = it has everything (no translation at all) */
    public static final class View {
        final Set<String> has;
        View(Set<String> has) { this.has = has; }
        boolean missing(ResourceLocation r) { return r != null && !"minecraft".equals(r.getResourceDomain()) && !has.contains(r.getResourceDomain()); }
        boolean missing(IBlockState s) { return missing(s.getBlock().getRegistryName()); }
    }

    private static final Map<EntityPlayerMP, View> VIEWS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final View EVERYTHING = new View(Collections.emptySet());

    /** this player's view, or null when their client has every server mod */
    public static View view(EntityPlayerMP p) {
        if (p == null || p.connection == null) return null;
        View v = VIEWS.get(p);
        if (v == null) {
            Set<String> has = Bridge.clientMods(p);
            boolean lacks = false;
            if (has.contains(Bridge.MARK))
                for (ModContainer m : Loader.instance().getActiveModList()) if (!has.contains(m.getModId())) { lacks = true; break; }
            v = lacks ? new View(has) : EVERYTHING;
            VIEWS.put(p, v);
            if (lacks) System.out.println("[PridePerms] Pride Bridge: " + p.getName() + " is on a Lite pack — translating what it doesn't have");
        }
        return v == EVERYTHING ? null : v;
    }

    /** set while a packet is being re-sent after translation, so it isn't translated twice */
    public static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    /** set while a chunk packet is rebuilt for a Lite player (BlockStateContainer.write reads it) */
    public static final ThreadLocal<View> CHUNK = new ThreadLocal<>();
    /** containers we built ourselves — written as they are */
    public static final Set<BlockStateContainer> OURS = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));

    // ---------------------------------------------------------------- look-alikes

    private static final Map<IBlockState, IBlockState> LOOK = new ConcurrentHashMap<>();
    private static volatile List<IBlockState> solids, glasses;

    public static IBlockState lookalike(IBlockState s) {
        IBlockState l = LOOK.get(s);
        if (l == null) { l = pick(s); LOOK.put(s, l); }
        return l;
    }

    private static IBlockState pick(IBlockState s) {
        Material m = s.getMaterial();
        if (m == Material.AIR) return Blocks.AIR.getDefaultState();
        if (m == Material.LAVA) return Blocks.LAVA.getDefaultState();
        if (m.isLiquid()) return Blocks.WATER.getDefaultState();
        if (!m.blocksMovement()) return Blocks.AIR.getDefaultState();        // plants, wires: nothing to bump into
        int rgb = colour(s);
        boolean full;
        try { full = s.isFullCube() && s.isOpaqueCube(); } catch (Throwable t) { full = true; }
        if (!full || m == Material.GLASS || m == Material.ICE) return nearest(glasses(), rgb, null);
        return nearest(solids(), rgb, m);
    }

    private static int colour(IBlockState s) {
        try { return s.getMapColor(null, BlockPos.ORIGIN).colorValue; }
        catch (Throwable t) { return s.getMaterial().getMaterialMapColor().colorValue; }
    }

    /** nearest colour; same material wins ties easily (a different material costs like a big colour gap) */
    private static IBlockState nearest(List<IBlockState> from, int rgb, Material m) {
        IBlockState best = Blocks.STONE.getDefaultState();
        long bestD = Long.MAX_VALUE;
        for (IBlockState c : from) {
            int o = colour(c);
            long dr = ((o >> 16) & 255) - ((rgb >> 16) & 255), dg = ((o >> 8) & 255) - ((rgb >> 8) & 255), db = (o & 255) - (rgb & 255);
            long d = dr * dr * 3 + dg * dg * 4 + db * db * 2 + (m != null && c.getMaterial() != m ? 9000 : 0);
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    /** every plain vanilla full block (no machines, nothing that falls or ticks into something else) */
    private static List<IBlockState> solids() {
        if (solids == null) {
            List<IBlockState> out = new ArrayList<>();
            for (Block b : Block.REGISTRY) {
                ResourceLocation r = b.getRegistryName();
                if (r == null || !"minecraft".equals(r.getResourceDomain()) || b instanceof BlockFalling) continue;
                if (b == Blocks.TNT || b == Blocks.BEDROCK || b == Blocks.BARRIER || b == Blocks.COMMAND_BLOCK || b == Blocks.CHAIN_COMMAND_BLOCK
                        || b == Blocks.REPEATING_COMMAND_BLOCK || b == Blocks.STRUCTURE_BLOCK || b == Blocks.MOB_SPAWNER || b == Blocks.SLIME_BLOCK
                        || b == Blocks.MAGMA || b == Blocks.REDSTONE_BLOCK || b == Blocks.GRASS || b == Blocks.MYCELIUM || b == Blocks.FARMLAND) continue;
                for (IBlockState s : b.getBlockState().getValidStates()) {
                    try {
                        if (b.hasTileEntity(s) || !s.isFullCube() || !s.isOpaqueCube() || s.getMaterial().isLiquid()) continue;
                        if (b.getMetaFromState(s) != b.getMetaFromState(b.getStateFromMeta(b.getMetaFromState(s)))) continue;
                        if (s != b.getStateFromMeta(b.getMetaFromState(s))) continue;   // only states that survive the trip to the client
                        out.add(s);
                    } catch (Throwable ignored) {}
                }
            }
            solids = out;
        }
        return solids;
    }

    private static List<IBlockState> glasses() {
        if (glasses == null) {
            List<IBlockState> out = new ArrayList<>();
            out.add(Blocks.GLASS.getDefaultState());
            out.addAll(Blocks.STAINED_GLASS.getBlockState().getValidStates());
            glasses = out;
        }
        return glasses;
    }

    private static final Map<Biome, Biome> BIOME = new ConcurrentHashMap<>();

    public static Biome lookalike(Biome b) {
        return BIOME.computeIfAbsent(b, x -> {
            Biome best = Biomes.PLAINS;
            float bestD = Float.MAX_VALUE;
            boolean ocean = x.getRegistryName() != null && x.getRegistryName().getResourcePath().contains("ocean");
            for (Biome c : Biome.REGISTRY) {
                if (c.getRegistryName() == null || !"minecraft".equals(c.getRegistryName().getResourceDomain())) continue;
                float d = Math.abs(c.getDefaultTemperature() - x.getDefaultTemperature()) + Math.abs(c.getRainfall() - x.getRainfall())
                        + (c.getRegistryName().getResourcePath().contains("ocean") != ocean ? 2f : 0f) + (c.isSnowyBiome() != x.isSnowyBiome() ? 1f : 0f);
                if (d < bestD) { bestD = d; best = c; }
            }
            return best;
        });
    }

    // ---------------------------------------------------------------- chunks

    /** a copy of a chunk section with look-alikes in place of missing blocks, or the section itself if none */
    public static BlockStateContainer translated(View v, BlockStateContainer src) {
        BlockStateContainer t = null;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            IBlockState s = src.get(x, y, z);
            if (t == null) {
                if (!v.missing(s)) continue;
                t = new BlockStateContainer();                     // first missing block: copy everything so far
                for (int i = 0; i < (y << 8 | z << 4 | x); i++) t.set(i & 15, i >> 8, (i >> 4) & 15, src.get(i & 15, i >> 8, (i >> 4) & 15));
            }
            t.set(x, y, z, v.missing(s) ? lookalike(s) : s);
        }
        if (t == null) return src;
        OURS.add(t);
        return t;
    }

    // ---------------------------------------------------------------- packets

    private static final Field CH_X = f(SPacketChunkData.class, "field_149284_a"), CH_Z = f(SPacketChunkData.class, "field_149282_b"),
            CH_AVAIL = f(SPacketChunkData.class, "field_186948_c"), CH_BUF = f(SPacketChunkData.class, "field_186949_d"),
            CH_FULL = f(SPacketChunkData.class, "field_149279_g"),
            BC_STATE = f(SPacketBlockChange.class, "field_148883_d"),
            MB_LIST = f(SPacketMultiBlockChange.class, "field_179845_b"), MB_STATE = f(SPacketMultiBlockChange.BlockUpdateData.class, "field_180092_c"),
            TE_POS = f(SPacketUpdateTileEntity.class, "field_179824_a"),
            BA_BLOCK = f(SPacketBlockAction.class, "field_148871_f"),
            SE_SOUND = f(SPacketSoundEffect.class, "field_186979_a"),
            EE_ID = f(SPacketEntityEffect.class, "field_149432_b"),
            EF_TYPE = f(SPacketEffect.class, "field_149251_a"), EF_DATA = f(SPacketEffect.class, "field_149249_b"),
            PA_TYPE = f(SPacketParticles.class, "field_179751_a"), PA_ARGS = f(SPacketParticles.class, "field_179753_k"),
            SO_TYPE = f(SPacketSpawnObject.class, "field_149019_j"), SO_DATA = f(SPacketSpawnObject.class, "field_149020_k"),
            EM_LIST = f(SPacketEntityMetadata.class, "field_149378_b");

    private static Field f(Class<?> c, String srg) {
        try { return ReflectionHelper.findField(c, srg); } catch (Throwable t) { System.out.println("[PridePerms] Pride Bridge: no field " + srg + " in " + c.getSimpleName()); return null; }
    }

    /** the packet this player should get: the same one, a translated copy, or null (leave it out) */
    @SuppressWarnings("unchecked")
    public static Packet<?> forPlayer(EntityPlayerMP p, Packet<?> pk) {
        View v = view(p);
        if (v == null) return pk;
        try {
            if (pk instanceof SPacketChunkData) return chunk(p, v, (SPacketChunkData) pk);
            if (pk instanceof SPacketBlockChange) {
                IBlockState s = (IBlockState) BC_STATE.get(pk);
                if (!v.missing(s)) return pk;
                SPacketBlockChange n = new SPacketBlockChange();
                BlockPos pos = ((SPacketBlockChange) pk).getBlockPosition();
                ReflectionHelper.findField(SPacketBlockChange.class, "field_179828_a").set(n, pos);
                BC_STATE.set(n, lookalike(s));
                return n;
            }
            if (pk instanceof SPacketMultiBlockChange) {
                SPacketMultiBlockChange.BlockUpdateData[] all = (SPacketMultiBlockChange.BlockUpdateData[]) MB_LIST.get(pk);
                boolean any = false;
                for (SPacketMultiBlockChange.BlockUpdateData d : all) if (v.missing(d.getBlockState())) { any = true; break; }
                if (!any) return pk;
                // the entries belong to the shared packet — copy them, then swap the states in the copies
                SPacketMultiBlockChange n = new SPacketMultiBlockChange();
                ReflectionHelper.findField(SPacketMultiBlockChange.class, "field_148925_b").set(n, ReflectionHelper.findField(SPacketMultiBlockChange.class, "field_148925_b").get(pk));
                SPacketMultiBlockChange.BlockUpdateData[] copy = new SPacketMultiBlockChange.BlockUpdateData[all.length];
                for (int i = 0; i < all.length; i++) {
                    IBlockState s = all[i].getBlockState();
                    copy[i] = n.new BlockUpdateData(all[i].getOffset(), v.missing(s) ? lookalike(s) : s);
                }
                MB_LIST.set(n, copy);
                return n;
            }
            if (pk instanceof SPacketUpdateTileEntity) {
                BlockPos pos = (BlockPos) TE_POS.get(pk);
                return v.missing(p.world.getBlockState(pos)) ? null : pk;
            }
            if (pk instanceof SPacketBlockAction) return v.missing(((Block) BA_BLOCK.get(pk)).getRegistryName()) ? null : pk;
            if (pk instanceof SPacketSoundEffect) return v.missing(((SoundEvent) SE_SOUND.get(pk)).getRegistryName()) ? null : pk;
            if (pk instanceof SPacketEntityEffect) {
                Potion pot = Potion.getPotionById(((Byte) EE_ID.get(pk)) & 255);
                return pot == null || v.missing(pot.getRegistryName()) ? null : pk;
            }
            if (pk instanceof SPacketEffect && (int) EF_TYPE.get(pk) == 2001) {         // block-break effect carries a block state id
                IBlockState s = Block.getStateById((int) EF_DATA.get(pk));
                if (!v.missing(s)) return pk;
                SPacketEffect n = new SPacketEffect();
                for (Field fl : new Field[]{EF_TYPE, ReflectionHelper.findField(SPacketEffect.class, "field_179747_b"), ReflectionHelper.findField(SPacketEffect.class, "field_149246_f")})
                    fl.set(n, fl.get(pk));
                EF_DATA.set(n, Block.getStateId(lookalike(s)));
                return n;
            }
            if (pk instanceof SPacketParticles) {
                EnumParticleTypes t = (EnumParticleTypes) PA_TYPE.get(pk);
                int[] a = (int[]) PA_ARGS.get(pk);
                if (a == null || a.length == 0) return pk;
                if (t == EnumParticleTypes.ITEM_CRACK) { Item it = Item.getItemById(a[0]); return it == null || v.missing(it.getRegistryName()) ? null : pk; }
                if (t == EnumParticleTypes.BLOCK_CRACK || t == EnumParticleTypes.BLOCK_DUST || t == EnumParticleTypes.FALLING_DUST) {
                    IBlockState s = Block.getStateById(a[0]);
                    return v.missing(s) ? null : pk;                                     // ponytail: dropped, not recoloured
                }
                return pk;
            }
            if (pk instanceof SPacketSpawnObject && (int) SO_TYPE.get(pk) == 70) {      // falling block: data = block state id
                IBlockState s = Block.getStateById((int) SO_DATA.get(pk));
                if (!v.missing(s)) return pk;
                SPacketSpawnObject n = copy(pk, new SPacketSpawnObject());
                SO_DATA.set(n, Block.getStateId(lookalike(s)));
                return n;
            }
            if (pk instanceof SPacketEntityMetadata) {
                List<EntityDataManager.DataEntry<?>> list = (List<EntityDataManager.DataEntry<?>>) EM_LIST.get(pk);
                if (list == null) return pk;
                boolean any = false;
                for (EntityDataManager.DataEntry<?> d : list)
                    if (d.getKey().getSerializer() == DataSerializers.OPTIONAL_BLOCK_STATE && ((Optional<IBlockState>) d.getValue()).isPresent()
                            && v.missing(((Optional<IBlockState>) d.getValue()).get())) { any = true; break; }
                if (!any) return pk;
                SPacketEntityMetadata n = copy(pk, new SPacketEntityMetadata());
                List<EntityDataManager.DataEntry<?>> out = new ArrayList<>();
                for (EntityDataManager.DataEntry<?> d : list) {
                    if (d.getKey().getSerializer() == DataSerializers.OPTIONAL_BLOCK_STATE && ((Optional<IBlockState>) d.getValue()).isPresent()
                            && v.missing(((Optional<IBlockState>) d.getValue()).get()))
                        out.add(new EntityDataManager.DataEntry<>((net.minecraft.network.datasync.DataParameter<Optional<IBlockState>>) d.getKey(),
                                Optional.of(lookalike(((Optional<IBlockState>) d.getValue()).get()))));
                    else out.add(d);
                }
                EM_LIST.set(n, out);
                return n;
            }
        } catch (Throwable t) {
            System.out.println("[PridePerms] Pride Bridge: couldn't translate " + pk.getClass().getSimpleName() + " for " + p.getName() + ": " + t);
        }
        return pk;
    }

    private static SPacketChunkData chunk(EntityPlayerMP p, View v, SPacketChunkData pk) throws Exception {
        int x = (int) CH_X.get(pk), z = (int) CH_Z.get(pk);
        boolean full = (boolean) CH_FULL.get(pk);
        Chunk c = p.getServerWorld().getChunkFromChunkCoords(x, z);
        SPacketChunkData n;
        CHUNK.set(v);
        try { n = new SPacketChunkData(c, full ? 65535 : (int) CH_AVAIL.get(pk)); }
        finally { CHUNK.remove(); OURS.clear(); }
        if (full) {                                                               // biomes: the last 256 bytes
            byte[] buf = (byte[]) CH_BUF.get(n);
            for (int i = buf.length - 256; i < buf.length; i++) {
                Biome b = Biome.getBiome(buf[i] & 255);
                if (b != null && v.missing(b.getRegistryName())) buf[i] = (byte) Biome.getIdForBiome(lookalike(b));
            }
        }
        List<NBTTagCompound> tags = n.getTileEntityTags();
        tags.removeIf(t -> v.missing(new ResourceLocation(t.getString("id"))));
        return n;
    }

    /** copy every field of a packet into a fresh one of the same class */
    private static <T> T copy(Object from, T to) throws IllegalAccessException {
        for (Field fl : from.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(fl.getModifiers())) continue;
            fl.setAccessible(true);
            fl.set(to, fl.get(from));
        }
        return to;
    }

    /** mobs from mods the player's client lacks stay invisible to them (look-alike stand-ins come later) */
    public static boolean hides(EntityPlayerMP p, Entity e) {
        View v = view(p);
        if (v == null || e == null) return false;
        EntityEntry en = EntityRegistry.getEntry(e.getClass());
        return en != null && v.missing(en.getRegistryName());
    }

    private Translate() {}
}
