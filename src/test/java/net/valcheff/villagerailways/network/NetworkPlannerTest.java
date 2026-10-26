package net.valcheff.villagerailways.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
		// Explore the world in four batches, as players would.
		for (int batch = 0; batch < 4; batch++) {
			planner.addVillages(villages.subList(batch * 20, batch * 20 + 20), ACCEPT_ALL);
		}
		List<Link> links = planner.links();
		assertTrue(links.size() > 40, "only " + links.size() + " lines");
		assertNetworkRules(planner);
	}

	@Test
	void largeWorldPlansQuickly() {
		// About 5000 villages over 40 000 x 40 000 blocks, found in 100 batches as players spread out.
		List<Village> villages = scatter(new Random(5), 5000, 40000);
		villages.sort(Comparator.comparingLong(village -> village.center().distanceSq(new Point(20000, 20000))));
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		long slowestBatch = 0;
		long started = System.nanoTime();
		for (int batch = 0; batch < 100; batch++) {
			long batchStart = System.nanoTime();
			planner.addVillages(villages.subList(batch * 50, batch * 50 + 50), ACCEPT_ALL);
			slowestBatch = Math.max(slowestBatch, System.nanoTime() - batchStart);
		}
		long totalMillis = (System.nanoTime() - started) / 1_000_000;
		System.out.println("5000 villages, " + planner.links().size() + " lines in " + totalMillis + " ms, slowest batch of 50 "
				+ slowestBatch / 1_000_000 + " ms");
		assertTrue(totalMillis < 20_000, "planning took " + totalMillis + " ms");
	}

	@Test
	void sameBatchInAnyOrderGivesSameNetwork() {
		List<Village> villages = scatter(new Random(11), 40, 5000);
		NetworkPlanner first = new NetworkPlanner(SETTINGS);
		first.addVillages(villages, ACCEPT_ALL);
		List<Village> shuffled = new ArrayList<>(villages);
		Collections.shuffle(shuffled, new Random(3));
		NetworkPlanner second = new NetworkPlanner(SETTINGS);
		second.addVillages(shuffled, ACCEPT_ALL);
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
		planner.addVillages(villages, ACCEPT_ALL);
		assertTrue(planner.links().stream().filter(link -> link.connects(1)).count() <= 3);
		assertNetworkRules(planner);
	}

	@Test
	void distantVillageWaitsForANeighbour() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		planner.addVillages(List.of(village(1, 0, 0), village(2, 2000, 0)), ACCEPT_ALL);
		assertTrue(planner.links().isEmpty());
		List<Link> added = planner.addVillages(List.of(village(3, 1000, 30)), ACCEPT_ALL);
		assertEquals(2, added.size());
		assertNetworkRules(planner);
	}

	@Test
	void rejectedPathIsNotBuilt() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		planner.addVillages(List.of(village(1, 0, 0), village(2, 700, 0)), path -> false);
		assertTrue(planner.links().isEmpty());
	}

	@Test
	void lineDoesNotCutThroughAThirdVillage() {
		NetworkPlanner planner = new NetworkPlanner(SETTINGS);
		planner.addVillages(List.of(village(1, 0, 0), village(2, 1000, 0), village(3, 500, 0)), ACCEPT_ALL);
		assertFalse(planner.links().stream().anyMatch(link -> link.connects(1) && link.other(1) == 2));
		assertNetworkRules(planner);
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
			assertTrue(a.center().distanceSq(b.center()) <= (long) SETTINGS.maxLength() * SETTINGS.maxLength());
			RouteStepperTest.assertAxisAligned(link.path(), link.path().getFirst(), link.path().getLast());
			for (Village other : byId.values()) {
				if (other.id() == a.id() || other.id() == b.id()) {
					continue;
				}
				for (int i = 1; i < link.path().size(); i++) {
					assertFalse(other.touches(link.path().get(i - 1), link.path().get(i), 0),
							"line " + link.from() + "-" + link.to() + " passes through village " + other.id());
				}
			}
		}
		degree.forEach((village, count) -> assertTrue(count <= SETTINGS.maxLinks(), village + " has " + count));
		for (int i = 0; i < links.size(); i++) {
			for (int j = i + 1; j < links.size(); j++) {
				assertFalse(touch(links.get(i), links.get(j)), "lines " + links.get(i) + " and " + links.get(j) + " meet");
			}
		}
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
			villages.add(new Village(villages.size() + 1, "test", center, x - halfX, z - halfZ, x + halfX, z + halfZ));
		}
		return villages;
	}

	private static Village village(long id, int x, int z) {
		return new Village(id, "test", new Point(x, z), x - 60, z - 60, x + 60, z + 60);
	}
}
