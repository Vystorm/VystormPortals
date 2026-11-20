## 1.25.1 — 2026-11-20

- With travel gate energy on (`travel-gates.energy.enabled`), `energy.yml` now keeps only players whose energy bar is not full. A player whose bar has filled up again is left out at the next save and starts full, as before, so nobody sees a different value.
- The file no longer grows with every player who ever opened a gate, so each `/home` or `/warp` gate writes a small file even on servers with thousands of past players.
- The file format stays the same: 1.25.0 reads the new file, and a player missing from it starts full there too.

## 1.25.0 — 2026-11-20

- Fixed: a `config.yml` with a YAML mistake no longer resets the settings when it is reloaded (for example from `/vadmin config`). The file is left as it is, the console names the line and column of the mistake, and the last working settings stay active (at start: the default settings).
- Fixed: `styles.yml`, `hours.yml`, `travel.yml` and `energy.yml` with a YAML mistake are copied to `<name>.broken-<date-time>` before the plugin writes the file again, so portal styles, opening hours, homes, warps and energy are never lost to a typo; the console names the line and column.
- `/vadmin features` names every switch of the plugin in seven languages ("Portal energy", "End gateway views") instead of the key path ("Travel gates › Energy").
- A command whose own switch is on but which needs another switch now names that switch: `/vportal menu`, `/vportal trip` and the Style page answer "This needs “Own portals” (manual-portals.enabled), which is switched off on this server.", and `/home`, `/warp`, `/homes`, `/warps` and `/vportal energy` name `travel-gates.enabled`.
- The portal menu shows a portal's settings as a list on Vystorm Screens: public access, pause, warmup, the three style choices and the opening hours each sit in one row with the full name on the left, the value on the right and a one-line description below. The trust page of the player menu does the same. Players with the client mod or the web page see the menu as before.
- README: the supported servers are written as tested: Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.
- Requires Vystorm Core 0.66.0 or newer. Update Core before you update this plugin.

## 1.24.0 — 2026-11-18

- Requires Vystorm Core 0.65.0. Update Core before you update this plugin.
- Switches now apply at once: a change in `/vadmin features` or `/vadmin config` is applied without a restart. Open portal views close by themselves when their switch goes off. Only `travel-gates.short-commands` and `tutorial.enabled` still need a restart.
- Every feature can now be switched on and off, with a one-line description in all seven languages in `/vadmin features`. Core adds the new switches to your existing `config.yml` with their comments; a missing switch counts as on, so nothing changes until you switch something off.
- New master switch `manual-portals.enabled` for your own portals: views, travel, entity travel, styles, the `/vportal` portal commands and the portal menu. Saved portals stay when it is off.
- New switches: `trips.enabled`, `designer.enabled` (particles, ambient sound, arrival effect and the Style page), `maintenance.enabled` (off ignores the pause flags), `travel-gates.homes.enabled` and `travel-gates.warps.enabled` (homes and warps can now be switched apart), `import.enabled` (HuskHomes), `use-world-links`, `tutorial.enabled`.
- Older keys that now also show in `/vadmin features`: `nether-portals.prepare-counterpart`, `end-portals.prepare-arrival-platform`, `end-portals.generate-destination-chunks`, `travel-gates.warps.permission-per-warp`.
- A command of a switched-off feature answers "This feature is switched off on this server." It is also gone from tab completion and help while the switch is off, and so are the short commands `/home`, `/warp` and the others while homes or warps are off. The portal menu entry, the Style page and the portal access entry in the player menu disappear while their switch is off.
- Placeholders: the plugin registers the namespace `portals` with 11 values (portal energy, homes, warps, own portals, whether a portal is open). Examples: `%vystorm_portals_energy%` and `%vystorm_portals_portal_open_market%`. They work in Vystorm texts and, through PlaceholderAPI, in other plugins. A value of a switched-off feature is empty.
- Tab completion and help follow a switch at once, without registering the command again.
- README: the install line names the right jar, and the compatibility line says what the build and the recorded Spigot check prove.

## 1.23.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The guard that stops players from breaking or building into the blocks of a portal view now denies before other plugins act.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.22.0 — 2026-11-15

- **Feature switches:** with Core 0.60.0 every Portals feature is in `/vadmin features` and can be switched off on its own: End and Nether portal views, End gateways, entity travel (objects, projectiles, mobs, vehicles), the menu, trusted players, opening hours, travel gates, their short commands and portal energy. Changes apply after a restart.
- **Import from HuskHomes:** `/vportal import huskhomes` (admins or console) copies all HuskHomes homes and warps into the travel gates. It reads the database named in HuskHomes' config (SQLite, MySQL or MariaDB) and changes nothing there. Homes and warps that already exist keep their place; names are lowercased. Afterwards you can turn off HuskHomes' /home and /warp.

## 1.21.0 — 2026-11-15

- **Home and warp gates:** `/home` and `/warp NAME` open a door-sized portal (1 wide, 2 high) that shows the destination. It forms in the wall in front of you, up to 3 blocks away (the wall only looks open to you, nothing is broken). Without a wall it stands free in front of you, one block higher on a step. Walk in within 10 seconds to travel, and then the gate breaks down. Home gates are only for their opener; warp gates take along anybody who may use the warp. New commands: `/sethome`, `/delhome`, `/homes`, `/setwarp`, `/delwarp` and `/warps`, also as `/vportal home` and so on. Turn off `travel-gates.short-commands` if another plugin owns /home. Homes per player are set by `travel-gates.homes.max` or the permission `vystormportals.homes.N`.
- **Portal energy (optional):** with `travel-gates.energy.enabled` every gate costs energy, more for longer trips (1 per 100 blocks), 25 extra into another world. Energy recharges over time, also offline. The cost is shown in the HUD corner window with Core 0.59, or in chat. An unused gate gives its energy back. Check yours with `/vportal energy`.
- Tutorial page "Home and warp gates" in all languages.

## 1.20.0 — 2026-11-15 (released with 1.21.0)

- Rebuild the portal overview, details, link picker, style, opening-hours and player trust menus as Core Panels, with paged selection and confirmed destructive actions.
- Keep all original chest menus as fallbacks for unavailable panels or older Core; no item-transfer menus exist in this plugin.

## 1.19.1 — 2026-11-14

- Performance: the portal check that runs on every player movement now skips portals in other worlds and far away before doing the crossing math.

## 1.19.0 — 2026-11-12

- Timed portals: portal menu → Opening hours. Pick the days and a daily time window (also past midnight). Outside its hours a portal refuses travel and tells the next opening time; players near a timed portal see a countdown in the action bar. Trips and entity travel respect the hours too. Settings under `opening-hours` (with an optional time zone), also in the first-run setup.

## 1.18.0 — 2026-11-11

- Portal designer: owners give each portal its own look in the portal menu → Style. Choose frame particles (sparks, portal mist, runes, soul flames, cherry petals, snowflakes and more), an ambient sound and an arrival effect (flash, swirl, sparkle). Preview shows it to you only. Particles appear when a player is within 32 blocks; styles are kept in `styles.yml`.

## 1.17.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- /portal and /portals work as short names for /vportal.
- Unknown portal or player messages now say what to do.

## 1.16.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.15.1 — 2026-11-08

- Reject malformed saved itineraries without silently discarding stops, and enforce saved plan limits on load.
- Reject extra trip command arguments; stop trips reliably after movement, disconnects, cancellation or teleport errors.
- Release destination tickets when worlds unload and retry failed chunk requests after a short delay.
- Restore trip tab completion and respect editing permissions in portal suggestions.
- Translate portal validation errors and access information in all seven languages.
- Allow even-width portals beside blocks outside their actual opening.

## 1.15.0 — 2026-11-07

- Add private saved multi-stop trips with full route preview, confirmation, ordered travel and bounded cancellation/failure handling.
- Include all seven bundled languages and targeted persistence, ownership and cancellation tests.
- Preserve Bukkit/Spigot fallback paths and existing Paper behavior.

## 1.14.0 (2026-11-07)

- Use shared Core compatibility adapters for Bukkit/Spigot rich text, menus and the server operations used by this plugin.
- Requires Vystorm Core 0.51.0 for Core integration. Server acceptance is recorded separately; a successful build alone does not establish runtime compatibility.

## 1.13.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.11.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode, minimum API 1.20.6, Vystorm Core 0.48.0 required for portable dialogs and feature detection.
- The bundled modern adapter preserves current respawn lookup and portal-delay rules. Older Paper uses its available gamerules and respawn lookup; the latter can synchronously load the respawn chunk during End return previews.
- Portal creation prompts fall back to Core chat input when native dialogs are unavailable. Nether portal search and End arrival platform preparation also support the older Paper internal signatures; no portal feature is deliberately disabled on 1.20.6. Unknown internal signatures disable only the affected preview preparation with one console warning; vanilla travel remains available.
- Offline `compat1206` compilation is part of `check`; adapter selection and an isolated 1.20.6 API runtime check cover the fallbacks.

## 1.10.0 (2026-11-02)

- New: maintenance mode for manual portals. Owners and admins can `/vportal pause NAME` and `/vportal resume NAME`, or use the switch in portal details. Pausing either endpoint stops both directions of player/entity travel and previews, including for admins, and cancels pending warmups. Links, access and trust stay intact. The optional `paused` state is saved atomically in schema 1 and defaults to false for older files. Info and menu cards show the state; paused portals remain manageable. English, German and Simplified Chinese commands and messages included. Uses existing editing permissions; native portals are unaffected.

## 1.9.0 (unreleased)

- New: End gateways get a view onto their exit, like the big End portals: standing up to 4 blocks from a gateway, the gateway block becomes a one-block window that shows the exit area in your looking direction (the gateway keeps your yaw too). Minecraft fixes a gateway's exit on its first use, so an unused gateway shows nothing. Same permissions as the End portal view (`vystormportals.end-view`); `end-portals.gateways: false` turns it off.

## 1.8.0 (unreleased)

- New: `/vportal rename <name> <new-name>` (German `umbenennen`, Chinese `重命名`) gives a portal a new name without rebuilding it. Position, size, access, warmup, owner and trusted players stay, and the linked partner keeps its link to the renamed portal. Owner or admin, same name rules as `create`; taken names are refused. Tab completion offers your editable portals.

## 1.7.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- `/vportal` now runs through Vystorm Core's command system: subcommands, keywords (`public`/`private`), help, tab completion and error messages in the player's language (`/vportal verbinden a b`, `/vportal 连接 a b` work next to the English names for everyone), a clickable `/vportal help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/portale` (German) and `/传送门` (Chinese) from the language files (`commands.vportal.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/vportal` and every English subcommand stay; width, height and warmup are checked as whole numbers before anything changes. The command is no longer declared in plugin.yml.

## 1.6.0 (2026-10-30)

- New: **portal menu**. `/vportal menu` or Core `/vmenu` → World → Portals lists every portal you may use (your own first, 45 per page) with world, position, size, access, warmup, target and owner. Owners and admins switch access, link through a picker, unlink, change the warmup by clicking (left +10, right -10, shift ±50 ticks), delete (shift-click) and create a 3x3 portal where they stand by typing a name. "Show me where" prints position and distance and outlines the portal. Every button runs the matching `/vportal` action with the same permissions and checks. New permission `vystormportals.menu` (default: everyone), config `menu.enabled: true`.
- New: **trusted players**. `/vportal trust <name> <player>` lets a single player use a private portal (travel and preview), `/vportal untrust <name> <player>` takes it back, `/vportal trust <name>` shows the list; the player is told when online. With Core 0.43.0 owners can also sneak + right-click a player → "Portal access" and toggle each of their portals. Items and mobs still only travel through public portals. Config `trust.enabled`, `trust.max-per-portal` (16, max 64), `trust.player-menu`. Stored as an optional `trusted` list in `portals.yml`; older files load unchanged.
- Tab completion for `menu`, `trust` and `untrust` (online players / trusted names); player-name completion now ignores letter case.

## 1.5.0 (2026-10-29)

- New: `/vportal info <name>` shows world, block position, size, access, warmup, linked target and owner of a portal you may use. Needs only `vystormportals.use`; tab completion offers every portal you can access.
- New: `/vportal unlink <name>` disconnects a portal from its partner without deleting either one (owner or admin). Fix: `/vportal link` now also clears the old links of both portals, so re-linking A from B to C no longer leaves B pointing one-way at A.

## 1.4.1 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 1.4.0 (2026-10-28)

- Texts are now English and German through the Vystorm Core language system (`lang/en.yml`, `lang/de.yml`, per player language) instead of German-only texts in `config.yml`. Texts you changed under `messages:` keep working and win.
- New: entities travel through manual portals. Dropped items, XP orbs, primed TNT, falling blocks, arrows, snowballs, tridents, ender pearls, mobs and empty minecarts/boats that fly or walk through a public portal (both ends public) arrive at the linked portal and keep their momentum, rotated like a player's. Throw loot through, shoot arrows through, let mobs follow you. Players keep their own travel rules. Fishing hooks, the ender dragon and the wither never travel; entities with riders stay put. The destination must be loaded (it is while someone looks through the portal); otherwise the entity simply passes the frame. Each group can be switched off under `entity-travel`, and at most 16 entities travel per tick (`max-per-tick`).
- `/vportal create` and `/vportal warmup` now say "width must be a whole number" instead of showing a raw Java parser message for non-numeric sizes or ticks.

## 1.3.2 (2026-10-26)

- Enable instant travel by default: manual crossings teleport in the move event and retain rotated momentum. Temporarily set native Nether dwell-time gamerules to zero, retaining vanilla portal events and destination handling. Set `instant-travel: false` to retain manual warmups and vanilla delays.
- Increase upright and End portal projection depth from 12 to 16 blocks.
- Show a one-block black concrete rim around portal views, including corners. The rim is client-only and restored with the view; native portal blocks remain unchanged.
- Reserve rim capacity within the 4096-block view limit and retain the 512-change per-tick budget.
- Still requires Core 0.36.0 or newer; built and tested against Core 0.38.0.

## 1.3.1 (2026-10-26)

- Refresh every tick with a 512-change/viewer budget; prioritize the aperture and newly visible nearby sides while retaining cleanup progress. Cache stationary sampling and increase upright depth/coverage.
- Transfer destination biome colors using Core 0.36.0 spatial biome overlays, including composition/restoration with WinterIsComing.
- Recheck nearby Nether portals on block movement and prepare already-loaded counterpart chunks without async throttling.

## 1.3.0 (2026-10-26)

- Automatically recognize lit native Nether portals, resolve/create their counterpart through Paper and project the target through the real portal surface. Manual create/link commands are no longer required for native portals.
- Bound asynchronous preparation, share chunk-ticket leases and honor cancellable native creation events. Preserve vanilla travel and restore views on player/plugin lifecycle changes.
- Resolve conventional and canonical dimension families, with explicit multiworld preview mappings.
- Prepare an empty first End arrival platform through Paper without clearing existing blocks; retain native return travel and credits.
- Add unit coverage and a disposable Purpur integration harness for real lookup, creation, cancellation and reuse.

## 1.2.2 (2026-10-25)

- Accept PUBLIC/PRIVATE in any letter case for access commands.
- Reject surplus create arguments before creating a portal, so command typos cannot silently change portal data.

# 1.2.1 (2026-10-24)

Complete portal commands with arguments appropriate to each action: access modes, editable portal names, and no portal names in numeric fields. Match command prefixes and existing portal names case-insensitively. Exclude the source portal from link target suggestions.

# 1.2.0 (2026-10-23)

Add automatic visual support for native End entry and return portals, using Core 0.30.0 horizontal aperture masks. Preserve vanilla dimension transitions, credits and respawn decisions. Keep centre pillars and frame-layer blocks intact. Bound asynchronous destination generation and release tickets with plugin/player lifecycle. Keep the native surface visible if no destination terrain is available.

# 1.0.1 (2026-10-23)

Preserve pending portal traversal through Paper's synchronous movement correction teleport. Other teleports still cancel pending traversal; correction matching expires after one event/tick. Found in real-client acceptance testing.

# 1.0.0 (2026-10-23)

Initial independently implemented plugin with a new configuration/data schema. See README.md for supported behavior and validation limits.
