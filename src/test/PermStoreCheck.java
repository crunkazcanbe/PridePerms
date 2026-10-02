package com.dogpound.prideperms;

import java.io.File;
import java.util.UUID;

/** run: java -cp ... com.dogpound.prideperms.PermStoreCheck — fails loudly if the rules engine is wrong */
public class PermStoreCheck {
    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": wanted " + want + " got " + got);
        System.out.println("ok  " + what + " = " + got);
    }

    public static void main(String[] a) throws Exception {
        PermStore s = PermStore.fresh();
        UUID bob = UUID.randomUUID(), amy = UUID.randomUUID();
        eq(null, s.decide(bob, "mekanism.break.digital_miner", false), "nobody said anything");
        eq(true, s.decide(bob, "anything.at.all", true), "op = admin = *");
        s.groups.get("default").nodes.put("mekanism.*", false);
        s.changed();
        eq(false, s.decide(bob, "mekanism.break.digital_miner", false), "default denies all of mekanism");
        eq(true, s.decide(bob, "mekanism.break.digital_miner", true), "admin * beats default (higher priority)");
        s.groups.get("member").nodes.put("mekanism.break.digital_miner", true);
        s.player(bob, "bob").groups.add("member");
        s.changed();
        eq(true, s.decide(bob, "mekanism.break.digital_miner", false), "member's exact rule beats default's wildcard");
        eq(false, s.decide(bob, "mekanism.use.machine", false), "member inherits default's mekanism.* deny");
        s.player(bob, "bob").nodes.put("mekanism.use.*", true);
        s.changed();
        eq(true, s.decide(bob, "mekanism.use.machine", false), "player's own rule beats groups");
        s.player(amy, "amy").nodes.put("mekanism.use.machine", false);
        s.changed();
        eq(false, s.decide(amy, "mekanism.use.machine", true), "player's own deny beats being op/admin");
        s.groups.get("member").parents.add("member");                 // a loop someone typed by mistake
        s.groups.get("default").parents.add("member");
        s.changed();
        eq(true, s.decide(bob, "mekanism.break.digital_miner", false), "parent loops don't hang");
        eq(null, s.decide(bob, "ic2.use.te", false), "unrelated mod still unset");
        File f = File.createTempFile("prideperms", ".json");
        f.delete();
        PermStore.load(f);                                            // fresh file written
        s = PermStore.load(f);
        eq(true, s.groups.containsKey("admin"), "save + load round trip keeps groups");
        eq(true, PermStore.match(java.util.Collections.singletonMap("*", true), "x.y.z"), "lone * matches");
        // --- v0.2: timed, per-dimension, temporary groups
        PermStore t = PermStore.fresh();
        UUID cat = UUID.randomUUID();
        PermStore.Rule r = new PermStore.Rule();
        r.node = "ic2.use.te"; r.value = false; r.until = System.currentTimeMillis() + 300;
        t.player(cat, "cat").timed.add(r);
        t.changed();
        eq(false, t.decide(cat, "ic2.use.te", false, 0), "timed deny works now");
        Thread.sleep(400);
        eq(null, t.decide(cat, "ic2.use.te", false, 0), "timed deny gone after it runs out");
        PermStore.Rule nether = new PermStore.Rule();
        nether.node = "minecraft.place.*"; nether.value = false; nether.dim = -1;
        t.groups.get("default").timed.add(nether);
        t.changed();
        eq(false, t.decide(cat, "minecraft.place.tnt", false, -1), "nether-only rule in the nether");
        eq(null, t.decide(cat, "minecraft.place.tnt", false, 0), "nether-only rule not in the overworld");
        t.groups.get("moderator").nodes.put("mod.power", true);
        t.player(cat, "cat").tempGroups.put("moderator", System.currentTimeMillis() + 300);
        t.changed();
        eq(true, t.decide(cat, "mod.power", false, 0), "temporary rank gives its rules");
        Thread.sleep(400);
        eq(null, t.decide(cat, "mod.power", false, 0), "temporary rank ends by itself");
        eq(86_400_000L + 3_600_000L * 2 + 60_000L * 30, PermsCommand.duration("1d2h30m"), "durations parse");
        eq(0L, PermsCommand.duration("5x"), "bad duration rejected");
        // --- land context (Pride Realms): only on your own land / only in the wild
        PermStore L = PermStore.fresh();
        UUID dog = UUID.randomUUID();
        PermStore.Rule tnt = new PermStore.Rule();
        tnt.node = "minecraft.place.tnt"; tnt.value = true; tnt.land = "own";
        L.groups.get("default").timed.add(tnt);
        PermStore.Rule wild = new PermStore.Rule();
        wild.node = "minecraft.break.*"; wild.value = false; wild.land = "claimed";
        L.groups.get("default").timed.add(wild);
        L.changed();
        eq(true, L.decide(dog, "minecraft.place.tnt", false, 0, "own"), "own-land rule on own land");
        eq(null, L.decide(dog, "minecraft.place.tnt", false, 0, "others"), "own-land rule not on someone else's land");
        eq(null, L.decide(dog, "minecraft.place.tnt", false, 0, null), "land rules never count when the land is unknown");
        eq(false, L.decide(dog, "minecraft.break.stone", false, 0, "trusted"), "claimed = any parcel");
        eq(null, L.decide(dog, "minecraft.break.stone", false, 0, "wild"), "claimed rule not in the wild");
        eq(true, PermStore.landMatches("trusted", "own"), "owners count as trusted");
        System.out.println("ALL PASS");
    }
}
