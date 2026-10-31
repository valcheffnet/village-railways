# RoadWeaver

**Compatible.** [Back to the list](../../COMPATIBILITY.md)

RoadWeaver builds roads between villages and other structures. Village Railways builds railway lines between the same villages, so the two cross each other a lot.

## Tested with

- Village Railways 1.0.1 for Minecraft 26.3, Fabric.
- RoadWeaver [2.3.2-server.1+26.3](https://github.com/valcheffnet/RoadWeaver/releases/tag/2.3.2-server.1%2B26.3), an unofficial server-side build of RoadWeaver 2.3.2 for Minecraft 26.3. The original RoadWeaver by shiroha233 has no 26.3 version yet.
- A Fabric 26.3 dedicated server with both jars in `mods`.

## Where a line meets a road

There are no level crossings. A railway line cuts through a road the same way it cuts through any other ground, and the road ends at the edge of the cut or at the tunnel mouth.

RoadWeaver puts lamp posts and sign posts along its roads. Village Railways does not count them as ground when it decides between a cut and a tunnel, so a lamp post standing on a hill does not make the line dig a tunnel under it. When a cut takes away the ground under a post, the whole post comes down with it, so nothing is left hanging over the track.

## First start of a world

Both mods plan their network when a new world starts for the first time, before the land around spawn is generated, so that first start takes longer with the two of them. On one test world (1536 by 1536 blocks, sea tubes off) it took:

| | First start | Later starts |
|---|---|---|
| Village Railways alone | 37 s | 1 s |
| RoadWeaver alone | 62 s | 11.5 s |
| Both | 87 to 147 s | 8 s |

The first start with both varied a lot between runs. Generating the land afterwards was not slower with both mods than with either one alone.

## Not tested yet

- Other Minecraft versions, and NeoForge.
- The fork's RoadWeaver 2.3.2 builds for Minecraft 26.2, and the original RoadWeaver on 1.21.x.
- Single player.

RoadWeaver has no Paper plugin, so there is nothing to test on Paper.
