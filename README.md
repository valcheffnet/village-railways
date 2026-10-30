# Village Railways

![A large station at night](art/banner.jpg)

**Server-side. Vanilla clients welcome.**

Villages in Minecraft sit hundreds or sometimes thousands of blocks apart with nothing but wilderness between them. Village Railways joins them with minecart railways. Each village gets lines to up to three of its nearest neighbours. Everything is vanilla blocks, so the railways stay in the world even if you remove the mod later.

It runs on the server. Players join with a normal vanilla client and don't need to install anything.

This branch is Minecraft 26.3; the 26.2, 26.1 and 1.21.11 versions are on their own branches.

## Screenshots

<p align="center"><img src="art/screenshots/station-platform.jpg" alt="Station platform" width="100%"><br><em>The platform of a large station.</em></p>

<table>
<tr>
<td width="50%"><img src="art/screenshots/station-lake.jpg" alt="A station by a lake"><br><em>A station by the lake, at the edge of the village.</em></td>
<td width="50%"><img src="art/screenshots/bridge-lake.jpg" alt="A bridge over a lake"><br><em>A double track bridge at night.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/tunnel.jpg" alt="A tunnel"><br><em>Inside a long tunnel.</em></td>
<td><img src="art/screenshots/double-tunnel.jpg" alt="A double track tunnel"><br><em>A double line in a tunnel.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/cave-bridge-deck.jpg" alt="A bridge through a cave"><br><em>Where a tunnel breaks into a cave, the line crosses it on a bridge.</em></td>
<td><img src="art/screenshots/cave-bridge.jpg" alt="A cave bridge from below"><br><em>A cave bridge seen from the floor.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/halt-tunnel.jpg" alt="A halt by a tunnel"><br><em>A halt at the mouth of a long tunnel.</em></td>
<td><img src="art/screenshots/halt.jpg" alt="A halt at sunset"><br><em>A halt in the evening.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/sea-tube.jpg" alt="A glass tube under the sea"><br><em>A glass tube on the sea bed.</em></td>
<td><img src="art/screenshots/sea-tube-entrance.jpg" alt="Where a tube leaves the shore"><br><em>Where the tube leaves the shore.</em></td>
</tr>
</table>

## What gets built

The track stays level wherever it can. A low hill gets an open cut, a mountain gets a beautiful tunnel, and rivers and ravines get bridges with pillars and railings. If a tunnel breaks into a cave on the way, the line crosses the cave on a bridge. The powered rails run off redstone torches hidden under the track, so you won't see torches every few blocks.

Every village has one station just outside it, where its lines meet. Groups of villages that would otherwise be cut off from the rest are joined by a longer double line, with two tracks all the way, and where one of those comes in, the station may be a large one with a map of the network on the wall. Bridges are double track on every line, and every switch comes with a lever.

On long stretches there are halts, small brick huts by the track with a minecart dispenser, roughly one every 800 blocks. Long tunnels get one at each end.

**Sea tubes are off by default.** Turn them on and a line that meets open water carries on along the sea bed in a glass tube, wide enough for two tracks, with prismarine ribs every 5 blocks standing on columns. With them off, lines stay away from the ocean and only bridge narrow straits.

<p align="center"><img src="art/screenshots/sea-tube-inside.jpg" alt="Inside a sea tube" width="100%"><br><em>Inside a sea tube.</em></p>

## Old worlds

Out of the box, lines only go through land that hasn't been generated yet, so nothing anyone has built gets touched. Add the mod to a world you've played for months and the railways show up in the land you explore from then on.

If you want lines through the old land too, an operator can ask for it in the settings screen ("Open the existing land to lines...", then "Backup and Build") or with `/villagerailways openland`. On the next server start the world is backed up into the `backups` folder first, then the lines are planned through the old land and built as it loads. Be warned that they will cut through whatever is in the way, houses and farms and redstone included, and only the backup can undo that.

## Maps

`/villagerailways mapitem` gives you a vanilla map of the network that reaches much farther than a normal map would. `/villagerailways mapwall` gives you a set of them to hang as a wall in item frames, the same kind the large stations have.

<table>
<tr>
<td width="50%"><img src="art/screenshots/station-map.jpg" alt="Map of the network in a station"><br><em>A map of the network on the wall of a large station.</em></td>
<td width="50%"><img src="art/screenshots/network-map.png" alt="A map of the network"><br><em>A network map from <code>/villagerailways mapitem</code>.</em></td>
</tr>
</table>

## Install

There is a jar for Fabric, one for NeoForge and a plugin for Paper, all for Minecraft 26.3:

- Fabric needs Fabric Loader 0.19 or newer and Fabric API. Mod Menu is optional and only gives you the settings screen.
- NeoForge needs NeoForge 26.3 and nothing else.
- Paper needs Paper 26.3.

Put the jar in the server's `mods` folder, or `plugins` on Paper. For singleplayer, install it on the client like any other mod.

The 26.2, 26.1 and 1.21.11 versions are on the `mc-26.2`, `mc-26.1` and `mc-1.21.11` branches. The first start of a world takes a bit longer on those, around 40 seconds.

## Settings

With the mod on your client you get a settings screen, through Mod Menu on Fabric or the Config button in the NeoForge mod list. On a server that runs the mod, admins (permission level 3) can change the server's settings from there, and in singleplayer the world's owner always can. The sea tubes also have their own switch on the Create World screen.

Everything is in `config/village_railways.properties` (`plugins/VillageRailways` on Paper), with a comment on each line. Changes apply on the next server start.

| Setting | Default | |
|---|---|---|
| `maxLinks` | `3` | Most lines one village may have, 1 to 3. |
| `maxLength` | `1200` | Longest line between two villages, in blocks. Double lines may be longer. |
| `minRun`, `maxRun` | `32`, `64` | The straight and the side step of a line at a slant. |
| `cutDepth` | `5` | Where the ground is higher than this over the rail, the line goes into a tunnel. |
| `pillarSpacing` | `9` | Blocks between the pillars of a bridge in the open. |
| `seaTubes` | `0` | `1` crosses seas in glass tubes. |

Lines that are already planned keep their route. A world also keeps the `cutDepth`, `pillarSpacing` and `seaTubes` it was first started with, so changing those only matters for new worlds.

## Commands

All commands need operator permission (level 2).

| Command | |
|---|---|
| `/villagerailways mapitem [radius]` | A map with the network on it. |
| `/villagerailways mapwall [maps] [radius]` | Maps for a wall of item frames, up to 5 by 5. |
| `/villagerailways lines [radius]` | The planned lines nearby, nearest first, with how far each one is built. |
| `/villagerailways villages [radius]` | The villages the mod expects around you. |
| `/villagerailways openland` | Opens the existing land to lines at the next start; `openland cancel` calls it off. |
| `/villagerailways speed` | Shows your minecart's speed above the hotbar. |
| `/villagerailways network`, `map`, `profile`, `build`, `station`, `verify` | Testing tools that plan, draw or build lines on demand. `build` and `verify` generate chunks, so use them only in test worlds. |

## Building from source

```
./gradlew build
./gradlew -p neoforge build
./gradlew -p paper build
```

All three come from the same sources. The jars end up in `build/libs`, `neoforge/build/libs` and `paper/build/libs`.

## Feedback

Found a bug or have an idea? Come to [Discord](https://discord.com/invite/X6yxJpk9Fz) or open an [issue](https://github.com/valcheffnet/village-railways/issues). If it's a bug, the seed and the coordinates help a lot, and screenshots of your lines are always welcome.

## License

All rights reserved, with permission to use the mod on any server and in modpacks. See [LICENSE.md](LICENSE.md).
