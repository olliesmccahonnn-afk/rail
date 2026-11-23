# Sniper Rod (Railgun) – Fabric, Minecraft 1.21.11

OP-only railgun fishing rod.

## Get the jar
Push to GitHub. The **Actions** tab builds it automatically; download
`sniperrod-jar` from the finished run and drop the jar (not `-sources`) into `mods/`
together with Fabric API.

Local build: install Gradle 9.2.1 + JDK 21, then run `gradle build`
(jar ends up in `build/libs/`).

## Use
- `/railgun give [arrow|wither|pearl|mob|tnt|onetnt] [uses]`
- Sneak + right-click the rod (or `/railgun gui`) opens the settings menu.
- `onetnt` = one primed TNT where you aim (fuse via the TNT fuse setting; works with sky mode).
