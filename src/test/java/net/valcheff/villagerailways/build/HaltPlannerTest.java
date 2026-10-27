package net.valcheff.villagerailways.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.profile.HeightProfile;
import net.valcheff.villagerailways.profile.Section;
import net.valcheff.villagerailways.profile.TerrainSampler;
import net.valcheff.villagerailways.profile.TrackPlan;

class HaltPlannerTest {
	private static final int TRACK = 70;
	private static final HaltPlanner.Terrain FLAT = (x, z) -> new TerrainSampler.Column(TRACK, HeightProfile.DRY);
	private static final HaltPlanner.NearLine NO_LINES = (x, z) -> false;

	@Test
	void longOpenLineGetsRareHaltsAwayFromStations() {
		TrackPlan plan = straight(3000, Section.FLAT);
		int[] halts = HaltPlanner.halts(plan, FLAT, NO_LINES);
		assertTrue(halts.length >= 2 && halts.length <= 3, "halts at " + Arrays.toString(halts));
		for (int k = 0; k < halts.length; k++) {
			assertTrue(halts[k] >= HaltPlanner.STATION_DISTANCE && halts[k] < plan.length() - HaltPlanner.STATION_DISTANCE);
			if (k > 0) {
				assertTrue(halts[k] - halts[k - 1] >= HaltPlanner.STATION_DISTANCE, "halts at " + Arrays.toString(halts));
			}
		}
	}

	@Test
	void shortLineGetsNone() {
		assertEquals(0, HaltPlanner.halts(straight(700, Section.FLAT), FLAT, NO_LINES).length);
	}

	@Test
	void longTunnelGetsAHaltOutsideEachEnd() {
		TrackPlan plan = straight(1500, Section.FLAT);
		Arrays.fill(plan.sections(), 600, 900, Section.TUNNEL);
		int[] halts = HaltPlanner.halts(plan, FLAT, NO_LINES);
		assertTrue(Arrays.stream(halts).anyMatch(i -> i >= 540 && i <= 590), "halts at " + Arrays.toString(halts));
		assertTrue(Arrays.stream(halts).anyMatch(i -> i >= 909 && i <= 959), "halts at " + Arrays.toString(halts));
	}

	@Test
	void tunnelHaltStandsInTheCutBeforeThePortal() {
		// The land rises towards the tunnel: a cut for the last hundred blocks before it.
		TrackPlan plan = straight(1500, Section.FLAT);
		Arrays.fill(plan.sections(), 500, 600, Section.TRENCH);
		Arrays.fill(plan.sections(), 600, 900, Section.TUNNEL);
		HaltPlanner.Terrain slope = (x, z) -> new TerrainSampler.Column(x >= 500 && x < 600 ? TRACK + 4 : TRACK,
				HeightProfile.DRY);
		int[] halts = HaltPlanner.halts(plan, slope, NO_LINES);
		assertTrue(Arrays.stream(halts).anyMatch(i -> i >= 500 && i <= 590), "halts at " + Arrays.toString(halts));
	}

	@Test
	void shortTunnelGetsNone() {
		TrackPlan plan = straight(700, Section.FLAT);
		Arrays.fill(plan.sections(), 300, 400, Section.TUNNEL);
		assertEquals(0, HaltPlanner.halts(plan, FLAT, NO_LINES).length);
	}

	@Test
	void noHaltOverWaterOrSlopesOrNearOtherLines() {
		TrackPlan plan = straight(3000, Section.FLAT);
		HaltPlanner.Terrain wet = (x, z) -> new TerrainSampler.Column(TRACK - 3, TRACK - 1);
		HaltPlanner.Terrain steep = (x, z) -> new TerrainSampler.Column(TRACK + Math.abs(z) * 2, HeightProfile.DRY);
		assertEquals(0, HaltPlanner.halts(plan, wet, NO_LINES).length);
		assertEquals(0, HaltPlanner.halts(plan, steep, NO_LINES).length);
		assertEquals(0, HaltPlanner.halts(plan, FLAT, (x, z) -> true).length);
	}

	@Test
	void passesNearMeasuresToTheSegments() {
		List<Point> path = List.of(new Point(0, 0), new Point(100, 0), new Point(100, 100));
		assertTrue(HaltPlanner.passesNear(path, 50, 10, 16));
		assertTrue(HaltPlanner.passesNear(path, 110, 50, 16));
		assertTrue(!HaltPlanner.passesNear(path, 50, 30, 16));
	}

	/** A level line running east along z = 0. */
	private static TrackPlan straight(int length, Section section) {
		int[] x = new int[length];
		int[] z = new int[length];
		int[] track = new int[length];
		int[] ground = new int[length];
		int[] water = new int[length];
		Section[] sections = new Section[length];
		for (int i = 0; i < length; i++) {
			x[i] = i;
			track[i] = TRACK;
			ground[i] = TRACK;
			water[i] = HeightProfile.DRY;
			sections[i] = section;
		}
		return new TrackPlan(x, z, track, ground, water, new boolean[length], sections, new int[0], new int[0], List.of(),
				new int[0]);
	}
}
