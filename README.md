# Village Railways

![A large station at night](art/banner.jpg)

**Server-side. Vanilla clients welcome.**

Villages in Minecraft sit hundreds of blocks apart with nothing between them. This mod lays minecart railways from one village to the next while the world generates: stations outside the villages, open cuts through hills, tunnels through mountains, bridges over rivers and ravines, and if you want them, glass tubes under the sea. Everything is built from vanilla blocks, so the lines stay when the mod is gone.

The whole mod runs on the server. Players join with a normal vanilla client and install nothing.

Not released yet. The code here is for Minecraft 26.3; builds for 26.2, 26.1 and 1.21.11 live on their own branches.

## Screenshots

<p align="center"><img src="art/screenshots/station-platform.jpg" alt="Station platform" width="100%"><br><em>The platform of a large station.</em></p>

<table>
<tr>
<td width="50%"><img src="art/screenshots/station-lake.jpg" alt="A station by a lake"><br><em>A station by the lake, just outside the village.</em></td>
<td width="50%"><img src="art/screenshots/bridge-lake.jpg" alt="A bridge over a lake"><br><em>A double track bridge at night.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/tunnel.jpg" alt="A tunnel"><br><em>Inside a long tunnel.</em></td>
<td><img src="art/screenshots/double-tunnel.jpg" alt="A double track tunnel"><br><em>A double line has two tracks all the way, tunnels included.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/cave-bridge-deck.jpg" alt="A bridge through a cave"><br><em>Where a tunnel breaks into a cave, the line crosses it on a bridge.</em></td>
<td><img src="art/screenshots/cave-bridge.jpg" alt="A cave bridge from below"><br><em>The same kind of bridge from the cave floor.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/halt-tunnel.jpg" alt="A halt by a tunnel"><br><em>A halt at the mouth of a long tunnel.</em></td>
<td><img src="art/screenshots/halt.jpg" alt="A halt at sunset"><br><em>A halt on open track.</em></td>
</tr>
<tr>
<td><img src="art/screenshots/sea-tube.jpg" alt="A glass tube under the sea"><br><em>A glass tube on the sea bed.</em></td>
<td><img src="art/screenshots/sea-tube-entrance.jpg" alt="Where a tube leaves the shore"><br><em>Where the tube leaves the shore.</em></td>
</tr>
</table>

## How the lines are planned

When a world starts, the mod works out where villages will generate, from the seed alone, before any of that land exists. It then picks which villages to join: each village gets up to three lines to its nearest neighbours, and groups of villages that would end up on their own are joined to the rest by longer double lines. Lines run in long straights, and a line at a slant goes as a staircase of straights and side steps.

The planning reads only the world generator, never the chunks, so it runs ahead of the players. By the time a chunk generates, the lines through it are known, and the chunk comes out with its piece of railway already in it. Nothing gets built later over land you have already seen.

Lines keep off the buildings of the villages they serve, away from other villages, and clear of other structures on the surface.

## What gets built

The track stays level. Shallow hills get an open cut, deeper ones a tunnel lined with cobblestone and oak frames and lit with copper lanterns. Rivers, ravines and drops are crossed on bridges with pillars and railings, and where a tunnel breaks into a cave, the line crosses the cave on a bridge of its own. Powered rails keep the cart at speed, run by redstone torches hidden under the track.

Each village gets one station, a straight track just outside it where its lines meet. Where a double line comes in, the station can be a large station: a big timber house beside two tracks, with seats, two minecart dispensers and a map of the network on the wall. Double lines have two tracks all the way, bridges on any line do too, and every switch has a lever.

Long stretches get halts, a small brick hut on a platform with a minecart dispenser. There is about one every 800 blocks, and one at each end of a long tunnel.

Sea tubes are off by default. With them on, a line that would cross open water runs along the sea bed in a glass tube wide enough for two tracks, with prismarine ribs every 5 blocks on columns down to the bottom. Without them, lines keep off the ocean and bridge only narrow straits.

## Old worlds

By default lines go only through land that does not exist yet, so nothing a player built is ever touched. In a world that has been played for a while, lines appear in the land you explore from now on.

To lay lines through the land that already exists as well, an operator can open it: from the settings screen ("Open the existing land to lines...", then "Backup and Build") or with `/villagerailways openland`. At the next start of the server the world is backed up into the `backups` folder, the lines are planned through the old land, and they are built there as it loads. They can cut through houses, farms and redstone. Only the backup can undo it.

## Maps

`/villagerailways mapitem` gives you a vanilla map with the whole network on it, reaching much farther than a normal map. `/villagerailways mapwall` gives a set of maps for a wall of item frames. The large stations hang such maps on their walls.

<table>
<tr>
<td width="50%"><img src="art/screenshots/station-map.jpg" alt="Map of the network in a station"><br><em>A map of the network on the wall of a large station.</em></td>
<td width="50%"><img src="art/screenshots/network-map.png" alt="A map of the network"><br><em>A network map from <code>/villagerailways mapitem</code>.</em></td>
</tr>
</table>

## Install

Minecraft 26.3, on Fabric, NeoForge or Paper:

- Fabric needs Fabric Loader 0.19 or newer and Fabric API. Mod Menu is optional, for the settings screen.
- NeoForge needs NeoForge 26.3 and nothing else.
- Paper takes the plugin jar, for Paper 26.3.

Drop the jar into the server's `mods` folder (`plugins` on Paper). Players join with a vanilla client either way. In singleplayer it works the same when installed on the client. Java 25 is needed, as for Minecraft 26.3 itself.

The same mod for 26.2, 26.1 and 1.21.11 is on the `mc-26.2`, `mc-26.1` and `mc-1.21.11` branches. On those versions a first start takes longer, because the older world generator is slower to read; expect about 40 seconds before the server is up.

## Settings

Players with the mod on their client get a settings screen: from Mod Menu on Fabric, or the Config button in the mod list on NeoForge. On a server that runs the mod, admins (permission level 3) see and change the server's settings there; in singleplayer the world's owner always can. When you create a singleplayer world, the Create World screen has a switch for the sea tubes.

The settings are in `config/village_railways.properties` (`plugins/VillageRailways` on Paper), with a comment for each one. They apply from the next start of the server.

| Setting | Default | |
|---|---|---|
| `maxLinks` | `3` | Most lines one village may have, 1 to 3. |
| `maxLength` | `1200` | Longest line between two villages, in blocks. Double lines may be longer. |
| `minRun`, `maxRun` | `32`, `64` | The straight and the side step of a line at a slant. |
| `cutDepth` | `5` | Where the ground is higher than this over the rail, the line goes into a tunnel. |
| `pillarSpacing` | `9` | Blocks between the pillars of a bridge in the open. |
| `seaTubes` | `0` | `1` crosses seas in glass tubes. |

Lines already planned keep their path. `cutDepth`, `pillarSpacing` and `seaTubes` are kept by each world from its first start, because a half built line has to come out the same at every start; changing them affects new worlds only.

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
| `/villagerailways network`, `map`, `profile`, `build`, `station`, `verify` | Tools for testing: they plan, draw or build lines on demand. `build` and `verify` generate chunks, so keep them to test worlds. |

## Building from source

```
./gradlew build
./gradlew -p neoforge build
./gradlew -p paper build
```

The Fabric jar ends up in `build/libs`, the NeoForge jar in `neoforge/build/libs` and the Paper jar in `paper/build/libs`. All three are built from the same sources.

## Feedback

Bugs, ideas and screenshots of your lines go to [Discord](https://discord.com/invite/X6yxJpk9Fz) or the [issues](https://github.com/valcheffnet/village-railways/issues) here. For a bug, the seed and the coordinates help a lot.

## License

All rights reserved, with permission to use the mod on any server and in modpacks. See [LICENSE.md](LICENSE.md).
