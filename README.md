# PridePerms

Ranks, permissions and commands for every mod in a Minecraft 1.12.2 pack, with an in-game Permissions Studio, a website join gate, and Pride Bridge, which lets Pride Lite players join a full Pride server.

## About

PridePerms gives every block, item, mob, menu, dimension and command a permission node, without the mod that owns it having to know PridePerms exists. Nothing is blocked until you write a rule, so normal play keeps working. You deny or allow what you want, per group or per player, for one thing or a whole mod at once (`mekanism.*`).

It also answers Forge's own permission system, so mods that already ask Forge for permissions (Ender IO, Vampirism, Custom NPCs, FVTM, MineColonies, Techguns and others) use the same ranks. All checks run on the server, so a modified client cannot skip them.

Everything can be done with `/perms`, or from the Permissions Studio, a menu that sends the same `/perms` words you could type. The mod also includes a command button menu, rank prefixes and name colours in chat, playtime rank-ups, a rank shop that uses in-game coins, a join gate tied to a website account, and imports from LuckPerms and PermissionsEx.

PridePerms was built for the Pride modpack, a 1.12.2 pack of about 750 mods, and works in other packs. Pride Realms (the Pride money and land mod) is optional; without it the rank shop, welcome coins and land rules are simply off.

## Features

### Permission nodes for every mod
Each player action Forge reports becomes a node named after the mod that owns the thing. Registry names are lower-cased and dots and spaces become `_`.

| Action | Node | Example |
|---|---|---|
| Break a block | `<mod>.break.<block>` | `mekanism.break.digital_miner` |
| Place a block | `<mod>.place.<block>` | `minecraft.place.tnt` |
| Right-click a block | `<mod>.use.<block>` | `ic2.use.te` |
| Right-click with an item (in the air or on a block) | `<mod>.item.<item>` | `techguns.item.ak47` |
| Pick up an item | `<mod>.pickup.<item>` | `minecraft.pickup.diamond` |
| Drop an item | `<mod>.drop.<item>` | denied drops are put back in the inventory, never deleted |
| Attack a mob | `<mod>.attack.<entity>` | `minecraft.attack.villager`; players are `minecraft.attack.player` |
| Interact with a mob | `<mod>.interact.<entity>` | |
| Ride / mount | `<mod>.ride.<entity>` | |
| Open a menu | `<mod>.open.<menu>` | `ic2.open.macerator` (the mod is found from the jar the menu class came from; `Container` is trimmed from the class name) |
| Enter a dimension | `dimension.<id>.enter` | `dimension.-1.enter` |
| Run a command | `command.<name>` | `command.tp` |
| Trample farmland | `minecraft.trample.farmland` | |
| Locks mod short names | `locks.pick`, `locks.masterkey`, `locks.key`, `locks.lock` | checked in addition to the per-item node |

- A denied action shows `✖ Not allowed (<node>)` above the hotbar (at most every 1.5 seconds).
- Machines that act as players (fake players: quarries, deployers, turtles) are always allowed unless `checkFakePlayers` is on.
- The console and command blocks follow vanilla rules.
- Every node anyone has asked about is remembered for `/perms nodes`, tab completion and the Studio's node list.

### Chat and name nodes
| Node | Default when no rule is set |
|---|---|
| `prideperms.chat.color` (`&0`-`&f`, `&r` in chat) | ops only |
| `prideperms.chat.format` (`&k`-`&o` in chat) | ops only |
| `prideperms.chat.emoji` (`:heart:` → ❤) | everyone |
| `prideperms.namecolor.<colour>` (for `/namecolor`) | everyone |
| `prideperms.namecolor.bold` / `.italic` / `.underline` | everyone |

There are 48 emoji shortcodes, for example `:heart:`, `:star:`, `:check:`, `:skull:`, `:trans:`, `:crown:`, `:sword:`, `:shrug:`, `:tableflip:`, `:lenny:`.

### How a rule is decided
1. The player's own rules, most specific match first.
2. Otherwise each of the player's groups by priority (highest first); each group's own rules, then its parents' rules.
3. Otherwise "not set", and the caller's default applies (allowed for actions, the mod's own default for Forge nodes, vanilla op levels for commands).

Within one set of rules the most specific wins: `mekanism.break.digital_miner` beats `mekanism.break.*`, which beats `mekanism.*`, which beats `*`. Only a trailing `.*` (or a bare `*`) is a wildcard. Everyone is in `default`. Answers are cached and the cache is cleared on every change.

### Groups, players and contexts
- First run creates `default` (priority 0) < `member` (10) < `moderator` (50) < `admin` (100, has `*`). `/perms preset` adds owner, admin, moderator, helper, vip and member with prefixes and a few starter nodes, leaving existing groups alone.
- An `owner` group (priority 1000, `*`) always exists. Accounts listed in `ownerUuids` are put in it on login, get op level 4 on dedicated servers, and are never held by the join gate. UUIDs are used so a renamed account cannot claim it.
- Groups have priority, parents (inheritance), prefix, suffix and meta values. Groups can be cloned and renamed (renaming updates members, temporary members, children and tracks).
- Players have their own rules, groups, temporary groups (`addtemp`), prefix, suffix and meta.
- A rule can be limited by any mix of:
  - **time**: `30m`, `2h`, `7d`, `1w`, `1d12h` (the rule disappears when it runs out),
  - **dimension**: `dim=<id>` or `in <id>`,
  - **Pride Realms land**: `land=own|trusted|claimed|others|wild`. `trusted` covers own land too, and `claimed` is anything except wild. Land is read from Pride Realms by reflection.
- Ops count as members of `admin` for normal checks when `opsAreAdmin` is on. For commands they do not, so a level-1 op does not get `/stop` from admin's `*`.

### Commands as permissions
A mixin on `EntityPlayerMP.canUseCommand` asks PridePerms first. `command.<name> = false` takes a command away, even from ops. `command.<name> = true` gives it without op (when `grantCommands` is on). With no rule, vanilla's op check is unchanged. A `CommandEvent` check also blocks denied commands.

### Tracks and playtime rank-ups
- A track is a ladder of groups, lowest first (`/perms track staff set member moderator admin`). `/perms promote|demote <player> <track>` moves along it.
- Auto-rank: give a group on a track the meta `hours` (for example `/perms group regular meta set hours 5`). Once a minute every online player is moved up the track as far as their play time allows. A step without `hours` is manual only and stops the climb. Players are never moved down.

### Rank shop
Groups with the meta `price` are for sale with `/rank buy <group>`, paid in Pride Realms coins from the player's account to the server treasury (in-game money only).
- `duration` meta (for example `30d`) makes it a rental through a temporary group.
- `requires` meta means the player must already be in that group.
Without Pride Realms nothing is for sale.

### Chat look
- Each player's top group prefix and suffix are shown around their name in chat (`namePrefixes`). A prefix that is only colour codes colours the name; a prefix like `[VIP]` is shown in front.
- `/namecolor <colour> [bold] [italic] [underline]` sets a player's own name colour, per permission.

### Join gate
Off by default. When `gateEnabled` is on, a new player is locked until they verify:
1. On joining they are held at the gate spot (or may walk within `gateGuestRadius` blocks), cannot break, place, use, hit, pick up, drop, open menus, ride, change dimension, chat, or run commands other than those in `gateAllowedCommands`, and cannot be hurt or hurt others. A lock screen explains the steps and a reminder with a clickable link appears in chat every 30 seconds.
2. The player makes an account on the server's website and types their Minecraft name there. The site sends a one-time code to the game through the signed web inbox (see "Website bridge").
3. The player types `/verify <code>` (or uses the box on the lock screen). Codes ignore case, spaces and dashes, and last `gateCodeMinutes`.
4. If `gateQuiz` has questions, they answer them one by one.
5. They are unlocked: a title, sound and rainbow fireworks. On the first verification they also get the `gateVerifiedGroup` rank, the `gateStarterKit` items and `gateWelcomeCoins` (paid from the Pride Realms treasury).

Other rules:
- Eight wrong tries within 10 minutes means waiting before trying again.
- One website account can link at most `gateMaxPerSiteAccount` Minecraft accounts.
- Staff check: an op who joins from a new address is locked until they verify again (`gateStaffIpCheck`).
- The owner of a single-player or LAN world is never locked (`gateExemptOwner`), and neither are `ownerUuids`.
- Admins can verify players by hand, make codes without a website, revoke verification, and set the waiting spot.

Gate data is saved in `<world>/prideperms-gate.json`.

### Permissions Studio (`/perms menu`)
A centred Pride-style panel for ops and admins with these pages: Dashboard, Groups, Players, Rule editor, Tracks, Rank shop, Join gate, Checker, Change log, Backups, Settings.
- Every button sends the same `/perms` (or `/verify`) words an admin could type, so the server checks it the same way. The server's answer is shown in a bar at the bottom of the menu.
- **Rule editor**: pick a group or player on the left, a mod in the middle (with search, plus Settings, Commands and Tracks), then the right side lists whole-mod and per-action switches and every block, item and mob of that mod with its actions. Each switch cycles not set → allow → deny → not set. A faint mark on "not set" shows the inherited answer. Rules limited by land, dimension or time are listed and can be added there.
- **Groups / Players**: prefix and suffix editor (colour swatches, bold/italic/underline/strike, symbols, bracket presets, a one-click rainbow, live chat preview), priority, parents, members, temporary ranks, promote/demote, name colour, verification status, meta values, clone, rename, delete (type the name to confirm).
- **Rank shop**: put groups on sale, set price, duration and required rank.
- **Join gate**: on/off, website page, waiting spot, quiz, starter kit, welcome coins, verified rank, verify or revoke players, make codes.
- **Checker**: test any player and node, and turn on live verbose output for everyone or one player.
- **Change log**, **Backups** (backup now, restore, reload from file, import from LuckPerms or PermissionsEx) and **Settings** (every config value, editable live).
- `/perms menu groups vip` or `/perms menu players Steve` opens straight on one group or player.

### Command button menu (`/cmds`, key J)
Every command the player is allowed to use, grouped by the mod it came from, as buttons. Commands without arguments run on one click. Commands with arguments open a form built from the usage text: `<needed>` and `[optional]` become text boxes, `<a|b|c>` becomes a button that cycles the choices, player arguments get a button that cycles online players, and usages with several forms (`x | y | z`) offer each form. Right-click toggles a favourite. Favourites and recent commands are saved in `config/prideperms-cmdmenu.txt` on the client.

### Verbose and change log
- `/perms verbose on [filter]` shows every permission check live in your chat (at most 20 lines a second), optionally filtered by node or player name.
- Every change made with `/perms`, the Studio, the website or auto-rank is written to `<world>/prideperms-log.txt`. `/perms log [n]` shows the latest lines.

### Backups and imports
- `/perms backup [note]` copies `prideperms.json` to `<world>/prideperms-backups/`; the newest 40 are kept. `/perms restore <name>` saves a safety backup first.
- If `prideperms.json` cannot be read, it is kept as `prideperms.json.broken` and a fresh file is started.
- `/perms import luckperms <file>` reads a LuckPerms `/lp export` file (`.json` or `.json.gz`): groups, inheritance, weights, prefixes, suffixes, meta, permissions with expiry, and players' groups and permissions. Contexts other than `world` are skipped (`server` and `gamemode` contexts are dropped).
- `/perms import pex <file>` reads a PermissionsEx `permissions.yml`: groups, inheritance, permissions (a leading `-` means deny), prefix, suffix, rank (as priority) and other options as meta, and users' groups and permissions.
- Imports merge into groups that already exist; nothing is wiped. The file is looked for in the world folder, then `config/`, then the server folder.

### Website bridge
PridePerms exchanges JSON files with a website or phone app through the world folder. It does not open any network connection itself.
- **Out** (every 5 minutes and within a second of a change): `<world>/pride-web/prideperms/ranks.json` (groups, prefixes, parents, tracks, ranks for sale), `players.json` (groups and temporary groups) and `verified.json` (gate status and linked accounts, no IP addresses).
- **In**: `<world>/pride-web/inbox/prideperms/*.json`, each `{"body": "<json text>", "signed": "<hex HMAC-SHA256 of body>"}`. The secret is in `config/pride-web.cfg` and is created on first use. Requests older than 10 minutes or already handled are refused. Answers go to `<world>/pride-web/outbox/prideperms/<id>.json`, and handled requests move to `inbox/prideperms/done/`.
- Actions: `user.group.add {group, duration?}`, `user.group.remove {group}`, `user.node.set {node, value: true|false|unset}`, `group.node.set {group, node, value}`, `group.meta.set {group, key, value}` (these need `by` to be someone allowed to use `/perms`), `rank.buy {group}` (the player must be online), `verify.code {name|uuid, code, account, minutes?}` and `verify.revoke {name|uuid}`.

## Pride Bridge

Pride Bridge lets a player using **Pride Lite** (the smaller version of the pack) join a server running the full **Pride** pack. Whatever comes from mods the Lite pack does not have is shown to that player as a vanilla look-alike.

### How a Lite client joins
1. **Marker.** A client counts as bridge-capable when it has PridePerms installed. PridePerms must be in both packs.
2. **Mod list check (server).** Forge normally rejects a client that lacks any of the server's mods. A mixin on `FMLNetworkHandler.checkModList` fills in every server mod the client is missing, at the server's own version, before the check runs. A mod the client *does* have at a different version is still rejected; only missing mods are allowed.
3. **Registry lists (client).** The server sends its full block, item and other registry lists, and a client missing entries would normally disconnect with "Fatally missing registry entries". A mixin on `GameData.injectSnapshot` drops the entries the client does not have before the lists are applied. This only happens for lists from a remote server, never when loading a single-player world.
4. **Translation (server).** For each player, the server works out once which mods their client lacks (from the mod list the client reported at login). A player whose client has every server mod is not translated at all. For a Lite player, every packet sent to them passes through the translator below.

"Missing" is decided by registry namespace: anything whose namespace is not `minecraft` and not one of the client's mod ids is missing for that player.

### What is translated
| Server sends | Lite player sees |
|---|---|
| Chunks | Each chunk section is rewritten with look-alikes in place of missing blocks; missing biomes become the nearest vanilla biome; tile-entity data for missing blocks is left out. |
| Single and multi block changes | The look-alike block. |
| Block-break effect (event 2001) | The look-alike block's break effect. |
| Falling blocks | The look-alike block. |
| Entity metadata holding a block state (for example an enderman's carried block) | The look-alike block. |
| Tile-entity updates for missing blocks | Left out. |
| Block events (pistons, chests, note blocks…) of missing blocks | Left out. |
| Sounds from missing mods | Left out. |
| Potion effects from missing mods | Left out. |
| Item or block particles from missing mods | Left out (not recoloured). |
| Mobs and other entities from missing mods | Not sent; they are invisible to that player. |

How a look-alike block is picked:
- air stays air, lava becomes lava, any other liquid becomes water;
- blocks you can walk through (plants, wires) become air;
- non-full blocks, glass and ice become glass or the stained glass closest in map colour;
- full solid blocks become the plain vanilla full block closest in map colour, preferring the same material. Blocks with tile entities, falling blocks, TNT, bedrock, barriers, command and structure blocks, spawners, slime, magma, redstone blocks, grass, mycelium and farmland are never used as look-alikes.

How a look-alike biome is picked: the vanilla biome closest in temperature and rainfall, keeping oceans as oceans and snowy as snowy.

### Current limits
- **Items are not translated yet (planned).** Inventory, slot and dropped-item packets that carry items from missing mods are sent unchanged.
- **Mob stand-ins are planned.** Mobs from missing mods are hidden for now instead of being shown as a vanilla look-alike.
- Custom network packets that mods send on their own channels are not filtered.
- Particles from missing mods are dropped rather than recoloured.
- A mod that registers things under a namespace that is not its mod id is treated as missing even for clients that have it, so those players also see look-alikes for it.
- Both packs need the same PridePerms. Mods present in both packs must have the same version.

## Commands

`/perms` (alias `/pp`) needs op level 3, or a rule `command.perms = true`.

| Command | What it does |
|---|---|
| `/perms menu [page] [name]` (or `studio`) | Open the Permissions Studio, optionally on a page (`gate`, `groups vip`, `players Steve`…) |
| `/perms groups` | List groups with priority, parents and rule count |
| `/perms group <g> create [priority]` | Create a group (default priority 5) |
| `/perms group <g> delete` | Delete a group (not `default`); removes it from players and parents |
| `/perms group <g> info` | Show prefix, suffix, priority, parents, rules, timed rules and meta |
| `/perms group <g> set <node> <true\|false> [time] [dim=<id>\|in <id>] [land=<kind>]` | Set a rule, optionally timed, per dimension or per land kind |
| `/perms group <g> unset <node>` | Remove a rule |
| `/perms group <g> parent add\|remove <group>` | Change inheritance |
| `/perms group <g> prefix [text]` / `suffix [text]` | Set the chat prefix or suffix (`&` codes) |
| `/perms group <g> priority <n>` | Set priority |
| `/perms group <g> meta set <key> <value>` / `meta unset <key>` | Meta values (`hours`, `price`, `duration`, `requires`…) |
| `/perms group <g> clone <new>` / `rename <new>` | Copy or rename a group |
| `/perms user <player> info` | Groups, temporary groups, rules and meta |
| `/perms user <player> add\|remove <group>` | Add or remove a group |
| `/perms user <player> addtemp <group> <time>` | Add a group for a time (`7d`, `2h30m`) |
| `/perms user <player> set <node> <true\|false> [time] [dim=<id>] [land=<kind>]` | Set a player rule |
| `/perms user <player> unset <node>` | Remove a player rule |
| `/perms user <player> prefix\|suffix [text]` | Personal prefix or suffix (empty = from their group) |
| `/perms user <player> meta set <key> <value>` / `meta unset <key>` | Player meta |
| `/perms tracks` | List tracks |
| `/perms track <name> set <g1> <g2> …` | Create or replace a track (at least two groups, lowest first) |
| `/perms track <name> add\|remove\|up\|down <group>` | Edit a track |
| `/perms track <name> delete` | Delete a track |
| `/perms promote\|demote <player> <track>` | Move a player one step |
| `/perms check <player> <node>` | Show the answer and which rule or group decided it |
| `/perms nodes [search]` | List nodes seen so far (first 40) |
| `/perms verbose on [filter]` / `verbose off` | Live permission checks in your chat |
| `/perms log [n]` | Last n changes (default 15, max 200) |
| `/perms preset` | Add the ready-made ranks |
| `/perms config [setting] [value]` | List or change config values live (list values are separated by ` ;; `) |
| `/perms backup [note]` / `backups` / `restore <name>` | Backups of `prideperms.json` |
| `/perms import luckperms\|pex <file>` | Import from LuckPerms or PermissionsEx |
| `/perms reload` | Reload `prideperms.json` from disk |

Player names accept selectors such as `@p`.

| Other command | Who | What it does |
|---|---|---|
| `/cmds` (alias `/cmdmenu`) | everyone | Open the command button menu |
| `/rank list` | everyone | Ranks for sale |
| `/rank buy <rank>` | everyone | Buy a rank with Pride Realms coins |
| `/namecolor <colour> [bold] [italic] [underline]` (alias `/nc`) | everyone, per permission | Set your name colour |
| `/namecolor list` / `reset` | everyone | Colours you may use / back to your rank's colour |
| `/verify [code or answer]` | everyone | Verify, answer the quiz, or show the lock screen |
| `/verify on\|off` | admins | Turn the join gate on or off (on locks unverified players already online) |
| `/verify give <player>` | admins | Verify an online player |
| `/verify giveall` | admins | Verify everyone online |
| `/verify revoke <player>` | admins | Unlink; they must verify again |
| `/verify code <player> [account]` | admins | Make a one-time 6-character code without a website |
| `/verify list` | admins | Verified players and their linked accounts |
| `/verify status <player>` | admins | One player's verification |
| `/verify setspawn` | admins | Locked players wait where you stand |

`/namecolor`, `/rank`, `/cmds` and `/verify` are only registered if no other mod already has a command with that name. "Admins" means anyone allowed to use `/perms`.

### Keybinds
| Key | Default | Action |
|---|---|---|
| Command menu | J | Opens the command button menu (category "PridePerms") |

## Config

`config/prideperms.cfg`. Every value can also be changed in game with `/perms config` or the Studio's Settings page.

| Key | Default | Effect |
|---|---|---|
| `opsAreAdmin` | `true` | Ops count as members of `admin` for normal checks (not for commands) |
| `forgeHandler` | `true` | Answer Forge's permission system. Restart to change. If another mod already does this, it is left alone |
| `checkFakePlayers` | `false` | Also check machines that act as players |
| `grantCommands` | `true` | `command.<name> = true` gives a command without op. Denying always works |
| `namePrefixes` | `true` | Show rank prefix and suffix around names in chat |
| `gateEnabled` | `false` | Turn on the join gate. Leave off until your website hands out codes |
| `gateSiteUrl` | the Pride website | Page where players get their code; shown on the lock screen and as a chat link. Set it to your own site |
| `gateGuestRadius` | `8` | How far a locked player may walk from the gate spot (0 = frozen) |
| `gateCodeMinutes` | `10` | How long a code works |
| `gateQuiz` | three yes/no rules questions | `question \| answer` per line; several right answers with `/` (`yes/yeah`). Empty = no quiz |
| `gateStarterKit` | `minecraft:bread 16`, `minecraft:torch 32` | Given on first verification: `modid:item[:meta] count` |
| `gateWelcomeCoins` | `100` | Pride Realms coins on first verification (0 = none) |
| `gateVerifiedGroup` | `member` | Rank given on verification, if that group exists |
| `gateStaffIpCheck` | `true` | An op joining from a new address must verify again |
| `gateMaxPerSiteAccount` | `1` | Minecraft accounts per website account (0 = no limit) |
| `gateExemptOwner` | `true` | The single-player / LAN world owner is never locked |
| `gateAllowedCommands` | `verify` | Commands a locked player may still use |
| `ownerUuids` | the Pride server owner's accounts | UUIDs that get the `owner` group, op level 4 and skip the gate. **Replace these with your own before running a server** |

### Files
| File | Contents |
|---|---|
| `<world>/prideperms.json` | Groups, players, tracks |
| `<world>/prideperms-gate.json` | Verified players, waiting codes, gate spot |
| `<world>/prideperms-log.txt` | Change log |
| `<world>/prideperms-backups/` | Backups (newest 40) |
| `<world>/pride-web/…` | Website bridge files |
| `config/pride-web.cfg` | Shared secret for the website bridge. Keep it private |
| `config/prideperms-cmdmenu.txt` (client) | Command menu favourites and recent commands |

## API for other mods
- `PridePerms.decide(UUID, node)` → `TRUE`, `FALSE` or `null` (not set).
- `PridePerms.allowed(UUID, node, fallback)` → the answer, or `fallback` when no rule is set.
- `PridePerms.decideCommand(UUID, node)` → like `decide`, without counting ops as admin.
- `PridePerms.inGroup(UUID, group)` → whether the player is in that group or a higher group that inherits it (used by PrideQuests rank tasks).
- `Owner.isOwner(UUID)`.
- Forge's `PermissionAPI` works as usual and is answered by PridePerms.

## Requirements
- Minecraft 1.12.2 with Forge 14.23.5.2860 or Cleanroom.
- MixinBooter (built into Cleanroom). PridePerms loads its mixins as a coremod (`IEarlyMixinLoader`).
- Java 8 or newer.
- Optional: Pride Realms, for the rank shop, welcome coins and land rules.
- Server side does all the checking and accepts clients without PridePerms. The client copy adds the Studio, the command menu, the lock screen, and is required for a Lite client to join through Pride Bridge.

## Install
1. Download `PridePerms-1.12.2-<version>.jar`.
2. Put it in the `mods` folder on the server and on clients.
3. Start the server once, then set `ownerUuids` (and `gateSiteUrl` if you will use the join gate) in `config/prideperms.cfg`.
4. In game, run `/perms menu` or `/perms preset` to get started.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The project targets Java 8 (ForgeGradle 3, MCP snapshot `20171003-1.12`). It compiles against `libs/mixinbooter-api.jar` and `libs/sponge-mixin.jar`, which you need to place in `libs/` before building. Self-check programs for the rule store, gate rules, chat styling and importers are in `src/test/`.

## License

MIT License. © 2026 crunkazcanbe.

## Credits

Made by crunkazcanbe, with Claude.


## Compile-only jars

The build compiles against these jars in `libs/` (other authors' mods / APIs). They are not included in this repo — get them from their official pages and drop them in `libs/` before building:

- `mixinbooter-api.jar`
- `sponge-mixin.jar`
