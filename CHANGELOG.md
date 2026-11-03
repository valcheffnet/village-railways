# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-11-04

### Added

- A small station with two tracks, with a small house, a platform on each side and a minecart dispenser for each track. It has a crossover at each end, so a cart that comes in on either track goes on to the far end, and the levers by the switches send it onto the other track.

### Changed

- Every village now gets a station with two tracks: the large one, or the small one where the large one doesn't fit. Where both fit, the village picks one, and the same seed always picks the same.
- Lines take one end of a station first and only then the other, so a double line that comes later still finds room. A track end with no line gets a hay bale.
- Villages close together share one station instead of each getting its own, and stations are at least 200 blocks apart.
- Where the ground drops away under the track, over a ravine for example, the line crosses on a bridge instead of a solid wall.
- On 1.20.1 only, the first start of a new world is a lot faster. The mod plans the railway network before the land generates, and on 1.20.1 that held the server up much longer than on the newer versions. On a Forge server the first start of a test world went from about 105 seconds to about 30.
- In a world from an earlier version, the stations and lines that are already planned stay as they are. The new rules apply to what gets planned after the update.

### Fixed

- A double line could turn into a single track just before a station. It now runs in with both rails, one into each track.
- Lines no longer zigzag with very short straight bits between the turns.
- Grass blocks next to the rails at the end of a large station.

## [1.0.2] - 2026-10-31

### Added

- Minecraft 1.21.1, for Fabric, NeoForge and Paper. That version has no copper lanterns or copper chains, so lines and stations use plain lanterns and chains there, and the benches in the large station have no cushions.
- Minecraft 1.20.1, for Fabric, Forge and Paper (2026-11-01). 1.20.1 also has no copper trapdoors, so the large station gets iron ones there.
- The note admins get when they join says where to bring questions and bug reports.

### Changed

- On 1.20.1 the settings screen scrolls when the settings don't fit on the screen.
- The Paper plugin no longer adds a data pack of its own. The house of the large station is read straight from the jar.

### Fixed

- On 26.2, 26.1 and 1.21.11 the large stations were built without their house. The house is saved in the 26.3 format, which those versions read as empty space. Large stations that generate after the update get the house, and ones that are already built stay as they are. Cushions only exist in 26.3, so the benches on those versions have none.

## [1.0.1] - 2026-10-31

### Added

- Two lines that leave a station side by side share one double tunnel. Where one of them turns off, there's a junction with the switches and levers in place.
- A single line turning inside a tunnel gets a real corner instead of bare rock.
- Short bits of tunnel where a full section doesn't fit, and the corners of double lines, are lined with stone bricks, some cracked and some mossy.
- Admins see a short note when they join if a newer version is out, and a link to the Discord. Both can be switched off in the settings (`updateCheck`, `supportMessage`).
- The mod has an icon in Mod Menu and the NeoForge mod list.

### Changed

- The map for new players is off by default. Turn it on with `starterMap=1`. Servers that already have a config file keep whatever it says.

### Fixed

- Two lines leaving a station side by side each built their own tunnel, and the frames ended up across the other line's track.
- Bridges left a strip of earth hanging over the track where the bank turned out higher than planned.
- Posts and lamps that lost their ground to a cut floated in the air. They now come down whole, and dirt or rock that a later chunk spills onto a finished line gets cleared.

## [1.0.0] - 2026-10-30

### Added

- The first release, for Minecraft 26.3, 26.2, 26.1 and 1.21.11 on Fabric, NeoForge and Paper. Village Railways links villages with minecart railways built from vanilla blocks while the world generates: stations, open cuts, tunnels, bridges, halts along the way and, if you turn them on, glass tubes under the sea.
- Every player joining a world for the first time gets one map of the railway network. You can turn that off in the settings.
- In a world you've already played, lines only show up in land you haven't explored yet. The README explains how to get them through the old land too; the world gets backed up first.

[Unreleased]: https://github.com/valcheffnet/village-railways/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/valcheffnet/village-railways/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/valcheffnet/village-railways/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/valcheffnet/village-railways/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/valcheffnet/village-railways/releases/tag/v1.0.0
