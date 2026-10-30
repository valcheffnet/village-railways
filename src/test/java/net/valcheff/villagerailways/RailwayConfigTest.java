package net.valcheff.villagerailways;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.valcheff.villagerailways.network.PlannerSettings;
import net.valcheff.villagerailways.profile.TrackSettings;

class RailwayConfigTest {
	@TempDir
	Path dir;

	@Test
	void missingFileGivesDefaultsAndIsWritten() {
		RailwayConfig config = RailwayConfig.load(dir);
		assertEquals(PlannerSettings.DEFAULT, config.planner());
		assertTrue(Files.exists(dir.resolve("village_railways.properties")));
	}

	@Test
	void valuesAreReadAndBadOnesFallBack() throws IOException {
		Files.writeString(dir.resolve("village_railways.properties"),
				"maxLinks=2\nmaxLength=800\nminRun=40\nmaxRun=abc\ncutDepth=9\npillarSpacing=6\n");
		RailwayConfig config = RailwayConfig.load(dir);
		assertEquals(2, config.planner().maxLinks());
		assertEquals(800, config.planner().maxLength());
		assertEquals(40, config.planner().minRun());
		assertEquals(PlannerSettings.DEFAULT.maxRun(), config.planner().maxRun());
		assertEquals(new TrackSettings(TrackSettings.DEFAULT.cutDepth(), 6, false), config.trackFor(dir.resolve("new")));
	}

	@Test
	void starterMapIsOffUnlessTurnedOn() throws IOException {
		assertFalse(RailwayConfig.load(dir).starterMap());
		Files.writeString(dir.resolve("village_railways.properties"), "starterMap=1\n");
		assertTrue(RailwayConfig.load(dir).starterMap());
		assertTrue(Files.readString(dir.resolve("village_railways.properties")).contains("starterMap=1"));
	}

	@Test
	void sideStepShorterThanStraightGivesDefaults() throws IOException {
		Files.writeString(dir.resolve("village_railways.properties"), "minRun=60\nmaxRun=40\n");
		PlannerSettings planner = RailwayConfig.load(dir).planner();
		assertEquals(PlannerSettings.DEFAULT.minRun(), planner.minRun());
		assertEquals(PlannerSettings.DEFAULT.maxRun(), planner.maxRun());
	}

	@Test
	void worldKeepsTheValuesOfItsFirstStart() throws IOException {
		Path world = dir.resolve("world");
		Files.writeString(dir.resolve("village_railways.properties"), "cutDepth=3\npillarSpacing=5\nseaTubes=0\n");
		assertEquals(new TrackSettings(3, 5, false), RailwayConfig.load(dir).trackFor(world));
		Files.writeString(dir.resolve("village_railways.properties"), "cutDepth=7\npillarSpacing=12\nseaTubes=1\n");
		assertEquals(new TrackSettings(3, 5, false), RailwayConfig.load(dir).trackFor(world));
	}

	@Test
	void newWorldKeepsTheSeaTubesChosenForIt() throws IOException {
		RailwayConfig config = RailwayConfig.load(dir);
		RailwayConfig.chooseNewWorldSeaTubes(true);
		assertTrue(config.trackFor(dir.resolve("chosen")).seaTubes());
		assertTrue(config.trackFor(dir.resolve("chosen")).seaTubes());
		assertEquals(TrackSettings.DEFAULT.seaTubes(), config.trackFor(dir.resolve("next")).seaTubes());
	}

	@Test
	void worldThatKeptItsValuesBeforeSeaTubesHasNone() throws IOException {
		Path world = dir.resolve("world");
		Files.createDirectories(world);
		Files.writeString(world.resolve("track.properties"), "cutDepth=4\npillarSpacing=9\n");
		assertEquals(new TrackSettings(4, 9, false), RailwayConfig.load(dir).trackFor(world));
	}

	@Test
	void worldWithLinesFromBeforeKeepsTheDefaults() throws IOException {
		Path world = dir.resolve("world");
		Files.createDirectories(world);
		Files.writeString(world.resolve("network.json"), "{}");
		Files.writeString(dir.resolve("village_railways.properties"), "cutDepth=3\npillarSpacing=5\n");
		assertEquals(TrackSettings.DEFAULT, RailwayConfig.load(dir).trackFor(world));
	}
}
