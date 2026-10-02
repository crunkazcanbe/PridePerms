package com.dogpound.prideperms;

import net.minecraftforge.common.config.Config;

/** config/prideperms.cfg */
@Config(modid = PridePerms.MODID, name = "prideperms")
public final class PermsConfig {
    @Config.Comment("Server operators count as members of the \"admin\" group (admin has every permission unless a rule says otherwise).")
    public static boolean opsAreAdmin = true;

    @Config.Comment("Answer Forge's permission system, so mods that already ask Forge (Ender IO, Vampirism, Custom NPCs, FVTM, "
            + "MineColonies, Techguns...) use these ranks. Restart to change. If another mod already does this, the first one wins.")
    public static boolean forgeHandler = true;

    @Config.Comment("Also check machines that act like a player (\"fake players\": quarries, deployers, turtles...). "
            + "Off = machines are always allowed, which is what most packs want.")
    public static boolean checkFakePlayers = false;

    @Config.Comment("Let a group or player be GIVEN a command (command.<name> = true) even without being op. "
            + "Denying commands always works; this is about granting.")
    public static boolean grantCommands = true;

    @Config.Comment("Show each player's top group prefix (e.g. &d[Admin]) before their name in chat.")
    public static boolean namePrefixes = true;

    // ------------------------------------------------------------------ the join gate (Gate.java)
    @Config.Comment("JOIN GATE: new players are locked until they make an account on the Pride website and type the code it "
            + "gives them (/verify <code>). Leave OFF until the website hands out codes, or everyone gets locked. /verify on|off")
    public static boolean gateEnabled = false;

    @Config.Comment("The website page where players get their code (shown on the lock screen and as a link in chat).")
    public static String gateSiteUrl = "https://pride.example.com";

    @Config.Comment("How far a locked player may walk from the gate spot to look around (blocks). 0 = frozen in place.")
    public static double gateGuestRadius = 8;

    @Config.Comment("How many minutes a code works after the website makes it.")
    public static int gateCodeMinutes = 10;

    @Config.Comment("Rules quiz after the code, one line each: question | answer (several right answers: yes/yeah). Empty = no quiz.")
    public static String[] gateQuiz = {
            "Is griefing other people's builds allowed? (yes / no) | no",
            "Are hacked clients allowed? (yes / no) | no",
            "Do we treat everyone with respect, whoever they are? (yes / no) | yes"};

    @Config.Comment("Given once, the first time a player verifies: modid:item[:meta] count")
    public static String[] gateStarterKit = {"minecraft:bread 16", "minecraft:torch 32"};

    @Config.Comment("In-game Pride Realms coins given once when a player first verifies (paid from the server treasury). 0 = none.")
    public static double gateWelcomeCoins = 100;

    @Config.Comment("Rank a player gets when they verify (only if that group exists). Empty = none.")
    public static String gateVerifiedGroup = "member";

    @Config.Comment("Staff 2-step: an op joining from a new internet address must verify again.")
    public static boolean gateStaffIpCheck = true;

    @Config.Comment("How many Minecraft accounts one website account may link (stops alts). 0 = no limit.")
    public static int gateMaxPerSiteAccount = 1;

    @Config.Comment("The owner of a single-player / LAN world is never locked. Turn off only to try the gate on yourself.")
    public static boolean gateExemptOwner = true;

    @Config.Comment("Commands a locked player may still use.")
    public static String[] gateAllowedCommands = {"verify"};

    @Config.Comment("Server OWNER accounts (Minecraft UUIDs): top 'owner' group with every permission, op level 4 on a server, never held by the join gate.")
    public static String[] ownerUuids = {};   // set yours in config/prideperms.cfg (the Pride server keeps its owners there)
    private PermsConfig() {}
}
