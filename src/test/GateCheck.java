package com.dogpound.prideperms;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** run: java -cp ... com.dogpound.prideperms.GateCheck — the join gate's rules (codes, expiry, alts, quiz) */
public class GateCheck {
    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": wanted " + want + " got " + got);
        System.out.println("ok  " + what + " = " + got);
    }

    public static void main(String[] a) {
        long now = 1_000_000;
        UUID bob = UUID.randomUUID(), amy = UUID.randomUUID();
        eq("AB3DQ7", Gate.normalize(" ab3-dq7 "), "codes ignore case, spaces, dashes");
        Gate.Pending p = new Gate.Pending();
        p.player = "bob"; p.account = "site:1"; p.expires = now + 60_000;
        eq(true, Gate.codeFits(p, "Bob", bob, now), "code fits its player (any case)");
        eq(false, Gate.codeFits(p, "Amy", amy, now), "someone else's code doesn't fit");
        eq(false, Gate.codeFits(p, "Bob", bob, now + 60_000), "an expired code doesn't fit");
        p.player = bob.toString();
        eq(true, Gate.codeFits(p, "NewName", bob, now), "a code made for the uuid survives a name change");
        eq(false, Gate.codeFits(null, "Bob", bob, now), "no code at all");

        Map<String, Gate.Linked> v = new LinkedHashMap<>();
        Gate.Linked l = new Gate.Linked();
        l.name = "Bob"; l.account = "site:1";
        v.put(bob.toString(), l);
        eq(1, Gate.linkedCount(v, "SITE:1", amy), "site account already has one Minecraft account (alt blocked at max 1)");
        eq(0, Gate.linkedCount(v, "site:1", bob), "re-verifying yourself doesn't count against you");
        eq(0, Gate.linkedCount(v, "site:2", amy), "a different site account is free");

        String q = "Is griefing other people's builds allowed? (yes / no) | no";
        eq("Is griefing other people's builds allowed? (yes / no)", Gate.question(q), "question text");
        eq(true, Gate.answerFits(q, " No. "), "answer ignores case, spaces, a period");
        eq(false, Gate.answerFits(q, "yes"), "wrong answer");
        eq(true, Gate.answerFits("Say hi | hi/hello", "hello"), "several right answers");

        String c = Gate.newCode();
        eq(6, c.length(), "new codes are 6 long");
        eq(true, c.matches("[A-HJ-NP-Z2-9]{6}"), "no 0/O/1/I in codes");
        System.out.println("GateCheck: all passed");
    }
}
