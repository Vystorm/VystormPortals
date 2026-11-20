# VystormPortals

Private portal previews, linked travel and saved multi-stop trips for Bukkit servers. Manual portals can be public, private, trusted-player-only or paused for maintenance. Existing Nether/End portal previews, entity transfer, menus and access checks remain available.

## Home and warp gates (1.21.0)

`/home [NAME]` and `/warp NAME` open a door-sized (1×2) portal in the wall in front of you, or free standing when there is no wall, that shows the destination. Walk into it within 10 seconds to travel; then the gate breaks down. Home gates are only for you, warp gates take friends along. `/sethome`, `/delhome`, `/homes`, `/setwarp`, `/delwarp` (admins) and `/warps` manage the places; every command also works as `/vportal home` and so on. Optional portal energy (`travel-gates.energy.enabled`) recharges over time, and longer trips or trips into another world cost more. Settings are under `travel-gates`. Permissions are `vystormportals.home`, `vystormportals.warp`, `vystormportals.warp.manage`, `vystormportals.homes.N` and `vystormportals.energy.bypass`.

## Portal designer (1.18.0)

In the portal menu → Style, owners pick frame particles, an ambient sound and an arrival effect for each portal, with a private preview. Styles live in `styles.yml`.

## Multi-stop trips (1.15.0)

```text
/vportal trip save sightseeing town museum harbor
/vportal trip list
/vportal trip show sightseeing
/vportal trip start sightseeing
/vportal trip confirm
/vportal trip cancel
/vportal trip delete sightseeing
```

Trips are private to their creator and contain 2–8 distinct existing manual portals. `show` and `start` display the entire route, each destination's world/coordinates and its warmup. Confirm within 30 seconds while standing within four blocks of the first portal. Remain still between stops: after the first warmup, travel proceeds in order with a three-second viewing pause plus each portal's warmup. `/vportal trip cancel`, movement, disconnect, death or an external teleport ends the session.

These are explicit temporary direct legs between accessible portals; they do not rewrite permanent portal links. Every stop must remain present, open and accessible. Changing any saved portal definition invalidates a pending/active route. Vehicles and passengers cannot enter a trip. Each arrival requires a loaded destination chunk and safe standing room. Cancelled teleports stop immediately; missing chunks time out after 15 seconds beyond the leg's departure deadline, and the entire trip is bounded to three minutes. A failed trip leaves the player at the last reached location, without retrying cancelled travel.

`vystormportals.use` grants trip commands and existing portal access rules still apply. Plans persist atomically in `trips.yml` (16 per player, 256 total); runtime travel/confirmation never resumes automatically after a restart. At most 16 trips run simultaneously. Chunk requests use the existing bounded destination ticket service; routes never generate terrain as a side effect.

## Installation and existing features

Install Vystorm Core **0.66.0+**, then `VystormPortals-<version>.jar` (for this release `VystormPortals-1.25.0.jar`) in `plugins/` and restart. Java 21 bytecode is used; run the Java version required by your Minecraft server. Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2. Folia is unsupported.

## Feature switches (1.24.0)

Every feature has its own on/off switch in `config.yml`, listed with a one-line description in all seven languages in `/vadmin features`. A change applies at once: the plugin now has a reload hook, and the portal views and gates read their switches every tick and close when one is off. Only `travel-gates.short-commands` and `tutorial.enabled` need a restart. Core adds new switches to an existing `config.yml` with their comments; a missing switch counts as on.

- New in 1.24.0: `manual-portals.enabled` (master switch for your own portals: views, travel, entity travel, styles, the `/vportal` portal commands and the portal menu), `trips.enabled`, `designer.enabled` (particles, ambient sound, arrival effect and the Style page), `maintenance.enabled` (`/vportal pause` and `resume`; off ignores the pause flags), `travel-gates.homes.enabled`, `travel-gates.warps.enabled`, `import.enabled` (HuskHomes), `use-world-links`, `tutorial.enabled`.
- Already there: `end-portals.enabled`, `nether-portals.enabled`, `entity-travel.enabled`, `menu.enabled`, `trust.enabled`, `opening-hours.enabled`, `travel-gates.enabled`, `travel-gates.energy.enabled`, and the declared `instant-travel`, `end-portals.gateways`, `end-portals.return-view`, `entity-travel.objects|projectiles|mobs|vehicles`, `trust.player-menu`, `travel-gates.short-commands`, `travel-gates.warps.take-others-along`.
- Older keys that now also show in `/vadmin features`: `nether-portals.prepare-counterpart`, `end-portals.prepare-arrival-platform`, `end-portals.generate-destination-chunks`, `travel-gates.warps.permission-per-warp`.

A command of a switched-off feature answers "This feature is switched off on this server."; it disappears from tab completion and help at once (also `/home` and `/warp` with their switch) and comes back when the switch is on again. Saved portals, trips, styles, hours, homes, warps and energy stay.

## Placeholders (1.24.0)

With Vystorm Core 0.65.0 the plugin registers the namespace `portals`: `energy`, `energy_max`, `energy_percent`, `energy_full_in`, `energy_enabled`, `homes_count`, `homes_max`, `warps_count`, `portals_owned`, `portals_total`, `portal_open` (with a portal name as argument). Each works as `%vystorm_portals_<key>%` (also through PlaceholderAPI) and as `{portals:<key>}` in Vystorm texts; `/vadmin placeholders portals` lists them. Examples: `%vystorm_portals_energy%` and `%vystorm_portals_portal_open_market%`. A value of a switched-off feature is empty. `portals_total` also answers off the server thread; the others answer on the server thread.

Use `/vportal help` for portal creation, linking, trust, preview, menu and maintenance commands. Settings live in `config.yml`; portal definitions in `portals.yml`. Localized text is supplied in English, German, French, Italian, Brazilian Portuguese, Russian and Simplified Chinese. Core provides language selection, command aliases and safe fallback dialogs.

## Build

Use JDK 25 and Gradle 9.4.1: `./gradlew -PcoreJar=/path/to/Core.jar build`. The build includes source compilation against Paper 1.20.6, current tests and the older-API runtime test task. Native preview adapters are isolated and fail locally on unknown signatures; vanilla travel remains available.

License and attribution files in this repository remain applicable. This is a Java server plugin; portal previews are private visual projections backed by Core.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.22.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.22.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.22.0.txt](LICENSE-MIT-until-1.22.0.txt)) and stay available under those terms.
