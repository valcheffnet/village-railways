package net.valcheff.villagerailways.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class NetworkPlannerTest {
	private static final PlannerSettings SETTINGS = PlannerSettings.DEFAULT;
	private static final NetworkPlanner.PathFilter ACCEPT_ALL = path -> true;

	@Test
	void randomWorldGivesPlanarNetworkWithinLimits() {
		List<Village> villages = scatter(new Random(7), 80, 8000);
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		// Explore the world in four squares, as players would.
		for (int square = 0; square < 4; square++) {
			int minX = (square % 2) * 4000;
			int minZ = (square / 2) * 4000;
			explore(planner, villages, minX, minZ, minX + 3999, minZ + 3999, ACCEPT_ALL);
		}
		List<Link> links = planner.links();
		assertTrue(links.size() > 40, "only " + links.size() + " lines");
		assertNetworkRules(planner);
	}

	@Test
	void everyVillageHasOneStationJustOutsideIt() {
		List<Village> villages = scatter(new Random(7), 80, 8000);
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, villages, ACCEPT_ALL);
		assertTrue(planner.stations().size() > 20, "only " + planner.stations().size() + " stations");
		Map<Long, Integer> perVillage = new HashMap<>();
		for (Link station : planner.stations()) {
			perVillage.merge(station.from(), 1, Integer::sum);
			assertTrue(planner.links().stream().anyMatch(link -> link.connects(station.from())),
					"station without a line: " + station.path());
			Village village = villages.stream().filter(candidate -> candidate.id() == station.from()).findFirst().orElseThrow();
			List<Point> track = station.path();
			assertEquals(3, track.size());
			assertEquals(StationLayout.LENGTH - 1, Math.abs(track.get(2).x() - track.get(0).x())
					+ Math.abs(track.get(2).z() - track.get(0).z()));
			for (int i = 1; i < track.size(); i++) {
				assertFalse(village.touches(track.get(i - 1), track.get(i), SETTINGS.stationMargin() - 1),
						"station inside its village: " + track);
				assertTrue(village.touches(track.get(i - 1), track.get(i), SETTINGS.stationMargin()),
						"station away from its village: " + track);
			}
		}
		perVillage.forEach((village, count) -> assertEquals(1, count, "stations of village " + village));
		assertNetworkRules(planner);
	}

	@Test
	void stationFacesTheNeighbours() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, List.of(village(1, 0, 0), village(2, 800, 0)), ACCEPT_ALL);
		Point west = planner.stationOf(1).path().get(1);
		Point east = planner.stationOf(2).path().get(1);
		assertEquals(60 + SETTINGS.stationMargin(), west.x(), "station of the west village at " + west);
		assertEquals(800 - 60 - SETTINGS.stationMargin(), east.x(), "station of the east village at " + east);
		// Facing each other, the line runs from middle to middle in one straight.
		assertEquals(List.of(west, east), planner.links().getFirst().path());
	}

	@Test
	void lineFromBehindTheStationGoesRoundTheVillage() {
		// Village 1's nearest neighbours lie east, so its station faces east; village 4 lies west of it.
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, List.of(village(1, 0, 0), village(2, 700, 0), village(3, 600, 400), village(4, -800, 0)),
				ACCEPT_ALL);
		assertTrue(planner.stationOf(1).path().get(1).x() > 60, "station of village 1 at " + planner.stationOf(1).path());
		assertTrue(planner.links().stream().anyMatch(link -> link.connects(1) && link.connects(4)),
				"no line to the village behind: " + planner.links());
		assertNetworkRules(planner);
	}

	@Test
	void largeWorldPlansQuickly() {
		// About 5000 villages over 40 000 x 40 000 blocks, explored in 100 squares of 4000 blocks.
		List<Village> villages = scatter(new Random(5), 5000, 40000);
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		long slowestBatch = 0;
		long started = System.nanoTime();
		for (int square = 0; square < 100; square++) {
			long batchStart = System.nanoTime();
			int minX = (square % 10) * 4000;
			int minZ = (square / 10) * 4000;
			explore(planner, villages, minX, minZ, minX + 3999, minZ + 3999, ACCEPT_ALL);
			slowestBatch = Math.max(slowestBatch, System.nanoTime() - batchStart);
		}
		long totalMillis = (System.nanoTime() - started) / 1_000_000;
		System.out.println("5000 villages, " + planner.links().size() + " lines (" + planner.links().stream().filter(Link::trunk)
				.count() + " trunk) in " + totalMillis + " ms, slowest square " + slowestBatch / 1_000_000 + " ms");
		assertTrue(totalMillis < 20_000, "planning took " + totalMillis + " ms");
	}

	@Test
	void sameBatchInAnyOrderGivesSameNetwork() {
		List<Village> villages = scatter(new Random(11), 40, 5000);
		NetworkPlanner first = new NetworkPlanner(SETTINGS);
		exploreAll(first, villages, ACCEPT_ALL);
		List<Village> shuffled = new ArrayList<>(villages);
		Collections.shuffle(shuffled, new Random(3));
		NetworkPlanner second = new NetworkPlanner(SETTINGS);
		exploreAll(second, shuffled, ACCEPT_ALL);
		assertEquals(new HashSet<>(first.links()), new HashSet<>(second.links()));
	}

	@Test
	void villageKeepsAtMostThreeLines() {
		List<Village> villages = new ArrayList<>();
		villages.add(village(1, 0, 0));
		for (int i = 0; i < 8; i++) {
			double angle = Math.PI * 2 * i / 8;
			villages.add(village(10 + i, (int) (Math.cos(angle) * 600), (int) (Math.sin(angle) * 600)));
		}
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, villages, ACCEPT_ALL);
		assertTrue(planner.links().stream().filter(link -> link.connects(1)).count() <= 3);
		assertNetworkRules(planner);
	}

	@Test
	void distantVillageWaitsForANeighbour() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		// Squares on chunk borders, as the game explores them.
		List<Village> villages = List.of(village(1, 0, 0), village(2, 2000, 0), village(3, 1000, 30));
		explore(planner, villages, -608, -608, 607, 607, ACCEPT_ALL);
		explore(planner, villages, 1408, -608, 2607, 607, ACCEPT_ALL);
		assertTrue(planner.links().isEmpty(), "the land between is not explored yet");
		List<Link> added = explore(planner, villages, 608, -608, 1407, 607, ACCEPT_ALL);
		List<Link> lines = added.stream().filter(link -> !planner.isStation(link.key())).toList();
		assertEquals(2, lines.size());
		assertTrue(lines.stream().noneMatch(Link::trunk));
		assertEquals(3, planner.stations().size(), "a station for each village");
		assertNetworkRules(planner);
	}

	@Test
	void separateGroupsAreJoinedByOneTrunkLine() {
		// Two triangles of villages 1800 blocks apart: too far for ordinary lines, close enough for a trunk line.
		List<Village> villages = List.of(village(1, 0, 0), village(2, 600, 0), village(3, 300, 500),
				village(4, 2400, 0), village(5, 3000, 0), village(6, 2700, 500));
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, villages, ACCEPT_ALL);
		List<Link> trunks = planner.links().stream().filter(Link::trunk).toList();
		assertEquals(1, trunks.size());
		assertTrue(trunks.getFirst().connects(2) && trunks.getFirst().connects(4), "nearest villages of the two groups");
		assertNetworkRules(planner);
	}

	@Test
	void groupGetsAtMostThreeTrunkLines() {
		// A small group in the middle with five far groups around it.
		List<Village> villages = new ArrayList<>();
		villages.add(village(1, 0, 0));
		villages.add(village(2, 500, 0));
		for (int i = 0; i < 5; i++) {
			double angle = Math.PI * 2 * i / 5;
			villages.add(village(10 + i, 250 + (int) (Math.cos(angle) * 1900), (int) (Math.sin(angle) * 1900)));
		}
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, villages, ACCEPT_ALL);
		long middleTrunks = planner.links().stream().filter(link -> link.trunk() && (link.connects(1) || link.connects(2))).count();
		assertTrue(middleTrunks <= 3 && middleTrunks > 0, middleTrunks + " trunk lines from the middle group: " + planner.links());
		assertNetworkRules(planner);
	}

	@Test
	void rejectedPathIsNotBuilt() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, List.of(village(1, 0, 0), village(2, 700, 0)), path -> false);
		assertTrue(planner.links().isEmpty());
	}

	@Test
	void lineStepsAroundAnObstacle() {
		// A structure sits where the plain staircase would put a corner; the filter stands in for the world check.
		NetworkPlanner.PathFilter obstacle = path -> {
			for (int i = 1; i < path.size(); i++) {
				Point a = path.get(i - 1);
				Point b = path.get(i);
				if (Math.max(a.x(), b.x()) >= 380 && Math.min(a.x(), b.x()) <= 420
						&& Math.max(a.z(), b.z()) >= 180 && Math.min(a.z(), b.z()) <= 220) {
					return false;
				}
			}
			return true;
		};
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, List.of(village(1, 0, 0), village(2, 800, 400)), obstacle);
		assertEquals(1, planner.links().size());
		assertTrue(obstacle.accepts(planner.links().getFirst().path()));
	}

	@Test
	void linesOfAVillageMeetItsStationAtDifferentSlots() {
		List<Village> villages = new ArrayList<>();
		villages.add(village(1, 0, 0));
		for (int i = 0; i < 6; i++) {
			double angle = Math.PI * 2 * i / 6;
			villages.add(village(10 + i, (int) (Math.cos(angle) * 650), (int) (Math.sin(angle) * 650)));
		}
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, villages, ACCEPT_ALL);
		assertEquals(3, planner.links().stream().filter(link -> link.connects(1)).count(), "lines " + planner.links());
		assertNetworkRules(planner);
	}

	@Test
	void lineDoesNotCutThroughAThirdVillage() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		exploreAll(planner, List.of(village(1, 0, 0), village(2, 1000, 0), village(3, 500, 0)), ACCEPT_ALL);
		assertFalse(planner.links().stream().anyMatch(link -> link.connects(1) && link.other(1) == 2));
		assertNetworkRules(planner);
	}

	/** Explores a square: marks it and adds the villages whose center lies in it. */
	private static List<Link> explore(NetworkPlanner planner, List<Village> villages, int minX, int minZ, int maxX, int maxZ,
			NetworkPlanner.PathFilter filter) {
		planner.markExplored(minX, minZ, maxX, maxZ);
		List<Village> inside = villages.stream()
				.filter(village -> village.center().x() >= minX && village.center().x() <= maxX
						&& village.center().z() >= minZ && village.center().z() <= maxZ)
				.toList();
		return planner.addVillages(inside, filter);
	}

	private static List<Link> exploreAll(NetworkPlanner planner, List<Village> villages, NetworkPlanner.PathFilter filter) {
		return explore(planner, villages, -6000, -6000, 10000, 10000, filter);
	}

	private static void assertNetworkRules(NetworkPlanner planner) {
		List<Link> links = planner.links();
		Map<Long, Village> byId = new HashMap<>();
		planner.villages().forEach(village -> byId.put(village.id(), village));
		Map<Long, Integer> degree = new HashMap<>();
		for (Link link : links) {
			degree.merge(link.from(), 1, Integer::sum);
			degree.merge(link.to(), 1, Integer::sum);
			Village a = byId.get(link.from());
			Village b = byId.get(link.to());
			long maxLength = link.trunk() ? SETTINGS.maxGroupLength() : SETTINGS.maxLength();
			assertTrue(a.center().distanceSq(b.center()) <= maxLength * maxLength);
			RouteStepperTest.assertAxisAligned(link.path(), link.path().getFirst(), link.path().getLast());
			for (Village other : byId.values()) {
				for (int i = 1; i < link.path().size(); i++) {
					assertFalse(other.touches(link.path().get(i - 1), link.path().get(i), 0),
							"line " + link.from() + "-" + link.to() + " passes through village " + other.id());
				}
			}
			assertTrue(atSlot(planner.stationOf(link.from()), link.path().getFirst(), byId.get(link.from())),
					"line " + link.path() + " does not start at a slot of " + planner.stationOf(link.from()));
			assertTrue(atSlot(planner.stationOf(link.to()), link.path().getLast(), byId.get(link.to())),
					"line " + link.path() + " does not end at a slot of " + planner.stationOf(link.to()));
			for (Link station : planner.stations()) {
				for (int i = 2; i < link.path().size() - 1; i++) {
					for (int j = 1; j < station.path().size(); j++) {
						assertTrue(NetworkPlanner.gapSq(link.path().get(i - 1), link.path().get(i), station.path().get(j - 1),
								station.path().get(j)) > 0, "line " + link.path() + " crosses station " + station.path());
					}
				}
			}
		}
		degree.forEach((village, count) -> assertTrue(count <= SETTINGS.maxLinks(), village + " has " + count));
		Map<Point, Integer> slotUse = new HashMap<>();
		for (Link link : links) {
			slotUse.merge(link.path().getFirst(), 1, Integer::sum);
			slotUse.merge(link.path().getLast(), 1, Integer::sum);
		}
		slotUse.forEach((slot, count) -> assertEquals(1, count, "lines meeting at " + slot));
		for (int i = 0; i < links.size(); i++) {
			for (int j = i + 1; j < links.size(); j++) {
				assertFalse(touch(links.get(i), links.get(j)), "lines " + links.get(i) + " and " + links.get(j) + " meet");
			}
		}
	}

	private static boolean atSlot(Link station, Point end, Village village) {
		StationLayout.Station layout = StationLayout.of(station.path(), village);
		for (StationLayout.Slot slot : StationLayout.Slot.values()) {
			if (layout.end(slot).equals(end)) {
				return true;
			}
		}
		return false;
	}

	private static boolean touch(Link first, Link second) {
		for (int i = 1; i < first.path().size(); i++) {
			for (int j = 1; j < second.path().size(); j++) {
				if (NetworkPlanner.gapSq(first.path().get(i - 1), first.path().get(i), second.path().get(j - 1),
						second.path().get(j)) == 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** Villages at least 350 blocks apart, like vanilla's placement grid, with footprints of 80 to 160 blocks. */
	private static List<Village> scatter(Random random, int count, int size) {
		List<Village> villages = new ArrayList<>();
		while (villages.size() < count) {
			int x = random.nextInt(size);
			int z = random.nextInt(size);
			Point center = new Point(x, z);
			if (villages.stream().anyMatch(other -> other.center().distanceSq(center) < 350L * 350L)) {
				continue;
			}
			int halfX = 40 + random.nextInt(40);
			int halfZ = 40 + random.nextInt(40);
			villages.add(new Village(villages.size() + 1, "test", center, x - halfX, z - halfZ, x + halfX, z + halfZ,
					List.of(new Box(x - halfX + 20, z - halfZ + 20, x + halfX - 20, z + halfZ - 20))));
		}
		return villages;
	}

	/** A village with a footprint of 120 blocks and its buildings in the middle 80. */
	private static Village village(long id, int x, int z) {
		return new Village(id, "test", new Point(x, z), x - 60, z - 60, x + 60, z + 60,
				List.of(new Box(x - 40, z - 40, x + 40, z + 40)));
	}
}
