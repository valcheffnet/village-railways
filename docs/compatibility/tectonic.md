# Tectonic

**Compatible.** [Back to the list](../../COMPATIBILITY.md)

Tectonic changes the shape of the overworld, with bigger continents, higher mountains and more ocean. Village Railways plans its lines from the terrain the world generator is going to make, so with Tectonic installed the lines follow Tectonic's land. There is nothing to set up in either mod.

## Tested with

- Village Railways 1.0.1 for Minecraft 26.3, Fabric.
- [Tectonic](https://modrinth.com/mod/tectonic) 3.0.32 for Fabric 26.3, with its required library [Lithostitched](https://modrinth.com/mod/lithostitched) 2.0.6.
- A Fabric 26.3 dedicated server, default settings in both mods.

## What changes in the world

Tectonic has much more ocean and its villages are further apart, so fewer of them are close enough to link and the network is smaller. On the test seed the first plan had 29 villages and 26 links with Tectonic, and 60 villages and 55 links without it. There were no villages near spawn with Tectonic, so the nearest lines were a long way out.

Where the route goes over Tectonic's hills, the line climbs with them. One line on the test world goes from sea level up to y 119. The world check found nothing wrong on the lines: all powered rails had power, all switches had levers and all station dispensers had their minecarts.

The first start of a world was quicker with Tectonic, since there were fewer villages to plan:

| | First start | Network ready |
|---|---|---|
| Village Railways alone | 60 s | 13 s |
| With Tectonic | 41 s | 3 s |

## Not tested yet

- Sea tubes on.
- Tectonic's taller world options (build height above 320, or a floor below -64).
- Tectonic 3.1 beta, the datapack version, NeoForge and other Minecraft versions.
- Tectonic and RoadWeaver together with Village Railways.
- Single player.
