package com.dogpound.prideperms;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** standalone check: LuckPerms export + PEX permissions.yml import into PermStore */
public class ImporterCheck {
    static int fails;
    static void eq(Object want, Object got, String what) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok  " : "FAIL ") + what + " = " + got);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("ppimp").toFile();
        String uid = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b";
        String lp = "{\"metadata\":{},\"groups\":{"
                + "\"vip\":{\"nodes\":[{\"type\":\"inheritance\",\"key\":\"group.default\",\"value\":true},{\"type\":\"prefix\",\"key\":\"prefix.50.&6[VIP] \",\"value\":true},"
                + "{\"type\":\"weight\",\"key\":\"weight.30\",\"value\":true},{\"type\":\"permission\",\"key\":\"essentials.fly\",\"value\":true},"
                + "{\"type\":\"permission\",\"key\":\"essentials.nick\",\"value\":false},{\"type\":\"meta\",\"key\":\"meta.homes.5\",\"value\":true},"
                + "{\"type\":\"permission\",\"key\":\"bungee.only\",\"value\":true,\"context\":{\"server\":\"lobby\"}}]}},"
                + "\"users\":{\"" + uid + "\":{\"username\":\"Kitty\",\"primaryGroup\":\"vip\",\"nodes\":[{\"type\":\"permission\",\"key\":\"worldedit.*\",\"value\":true,\"expiry\":" + (System.currentTimeMillis() / 1000 + 3600) + "}]}}}";
        File lpf = new File(dir, "export.json");
        Files.write(lpf.toPath(), lp.getBytes(StandardCharsets.UTF_8));
        PermStore s = PermStore.fresh();
        s.setFile(new File(dir, "prideperms.json"));
        Importer.run(s, "luckperms", lpf);
        PermStore.Group vip = s.groups.get("vip");
        eq(true, vip.parents.contains("default"), "LP inheritance");
        eq("§6[VIP] ", vip.prefix, "LP prefix with & colours");
        eq(30, vip.priority, "LP weight");
        eq(true, vip.nodes.get("essentials.fly"), "LP allow");
        eq(false, vip.nodes.get("essentials.nick"), "LP deny");
        eq("5", vip.meta.get("homes"), "LP meta");
        eq(null, vip.nodes.get("bungee.only"), "LP server context skipped");
        PermStore.Player kitty = s.players.get(uid);
        eq(true, kitty.groups.contains("vip"), "LP primary group");
        eq(1, kitty.timed.size(), "LP expiring permission becomes a timed rule");
        eq(true, s.decide(UUID.fromString(uid), "essentials.fly", false), "LP player inherits vip");

        String yml = "groups:\n"
                + "  default:\n    default: true\n    permissions:\n    - modifyworld.*\n    - -modifyworld.tnt\n    options:\n      prefix: '&7[Guest] '\n"
                + "  builder:\n    inheritance:\n    - default\n    permissions: [worldedit.wand, 'bob.*']   # flow list\n    options:\n      rank: '500'\n      suffix: \"&a!\"\n      homes: 3\n"
                + "users:\n  " + uid + ":\n    group:\n    - builder\n    permissions:\n    - -essentials.fly\n    options:\n      name: Kitty\n";
        File pf = new File(dir, "permissions.yml");
        Files.write(pf.toPath(), yml.getBytes(StandardCharsets.UTF_8));
        PermStore p = PermStore.fresh();
        p.setFile(new File(dir, "p2.json"));
        Importer.run(p, "pex", pf);
        PermStore.Group b = p.groups.get("builder");
        eq(true, b.parents.contains("default"), "PEX inheritance");
        eq(true, b.nodes.get("worldedit.wand"), "PEX flow list");
        eq(true, b.nodes.get("bob.*"), "PEX quoted item");
        eq(500, b.priority, "PEX rank → priority (1000 - rank)");
        eq("§a!", b.suffix, "PEX suffix");
        eq("3", b.meta.get("homes"), "PEX other options → meta");
        eq(false, p.groups.get("default").nodes.get("modifyworld.tnt"), "PEX -node = deny");
        eq("§7[Guest] ", p.groups.get("default").prefix, "PEX prefix");
        eq(false, p.decide(UUID.fromString(uid), "essentials.fly", false), "PEX user deny");
        eq(true, p.decide(UUID.fromString(uid), "modifyworld.dig", false), "PEX user gets default's wildcard via builder");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
