package net.valcheff.villagerailways.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RouteStepperTest {
	private static final PlannerSettings SETTINGS = PlannerSettings.DEFAULT;

	@Test
	void alignedPointsGiveOneStraight() {
		List<Point> route = RouteStepper.route(new Point(0, 40), new Point(700, 40), SETTINGS);
		assertEquals(List.of(new Point(0, 40), new Point(700, 40)), route);
	}

	@Test
	void diagonalBecomesStaircaseOfSimilarStraights() {
		List<Point> route = RouteStepper.route(new Point(0, 0), new Point(1000, 1000), SETTINGS);
		assertAxisAligned(route, new Point(0, 0), new Point(1000, 1000));
		for (int i = 1; i < route.size(); i++) {
			int length = length(route.get(i - 1), route.get(i));
			assertTrue(length >= SETTINGS.minRun() && length <= SETTINGS.maxRun(), "straight of " + length);
		}
	}

	@Test
	void mostlyStraightLineGetsFewLongStraights() {
		List<Point> route = RouteStepper.route(new Point(0, 0), new Point(1000, 100), SETTINGS);
		assertAxisAligned(route, new Point(0, 0), new Point(1000, 100));
		// Three long straights east with two side steps of 50 between them.
		assertEquals(6, route.size());
		assertEquals(new Point(333, 0), route.get(1));
		assertEquals(new Point(333, 50), route.get(2));
	}

	@Test
	void shortSideDistanceIsOneStep() {
		List<Point> route = RouteStepper.route(new Point(0, 0), new Point(-500, 20), SETTINGS);
		assertAxisAligned(route, new Point(0, 0), new Point(-500, 20));
		assertEquals(4, route.size());
	}

	@Test
	void forcedMainAxisLeavesAlongIt() {
		List<Point> route = RouteStepper.route(new Point(0, 0), new Point(100, 300), true, SETTINGS);
		assertAxisAligned(route, new Point(0, 0), new Point(100, 300));
		assertEquals(0, route.get(1).z(), "first straight runs along x");
		assertEquals(300, route.getLast().z());
		assertEquals(route.get(route.size() - 2).z(), 300, "last straight runs along x");
	}

	static void assertAxisAligned(List<Point> route, Point from, Point to) {
		assertEquals(from, route.getFirst());
		assertEquals(to, route.getLast());
		for (int i = 1; i < route.size(); i++) {
			Point a = route.get(i - 1);
			Point b = route.get(i);
			assertTrue(a.x() == b.x() ^ a.z() == b.z(), "segment " + a + " to " + b + " is not along one axis");
		}
	}

	private static int length(Point a, Point b) {
		return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
	}
}
