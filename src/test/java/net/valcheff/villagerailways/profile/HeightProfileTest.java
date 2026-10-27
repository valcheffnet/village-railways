package net.valcheff.villagerailways.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class HeightProfileTest {
	@Test
	void flatGroundStaysOnTheGround() {
		int[] ground = filled(200, 70);
		int[] track = HeightProfile.plan(ground, dry(200), new boolean[200], 70, 70);
		assertTrue(Arrays.stream(track).allMatch(y -> y == 70));
	}

	@Test
	void lowHillIsCutThroughAndHighHillTunnelled() {
		int[] ground = filled(300, 70);
		Arrays.fill(ground, 60, 90, 74);
		Arrays.fill(ground, 160, 185, 90);
		int[] track = HeightProfile.plan(ground, dry(300), new boolean[300], 70, 70);
		assertTrue(Arrays.stream(track).allMatch(y -> y == 70), "the line stays level");
		assertEquals(Section.TRENCH, Section.of(track[70], ground[70], HeightProfile.DRY));
		assertEquals(Section.TUNNEL, Section.of(track[170], ground[170], HeightProfile.DRY));
	}

	@Test
	void waterIsBridgedAboveItsSurface() {
		int[] ground = filled(200, 70);
		int[] water = dry(200);
		Arrays.fill(ground, 80, 120, 55);
		Arrays.fill(water, 80, 120, 64);
		int[] track = HeightProfile.plan(ground, water, new boolean[200], 70, 70);
		assertTrue(track[100] > water[100], "rail at " + track[100] + " over water up to " + water[100]);
		assertEquals(Section.BRIDGE, Section.of(track[100], ground[100], water[100]));
	}

	@Test
	void heightDifferenceIsTakenOnceWhereTheGroundRises() {
		int length = 400;
		int[] ground = new int[length];
		for (int i = 0; i < length; i++) {
			ground[i] = i < 200 ? 70 : i < 230 ? 70 + (i - 200) * 20 / 30 : 90;
		}
		boolean[] corners = new boolean[length];
		corners[205] = true;
		int[] track = HeightProfile.plan(ground, dry(length), corners, 70, 90);
		assertEquals(70, track[0]);
		assertEquals(90, track[length - 1]);
		int changes = 0;
		int run = 0;
		int flat = HeightProfile.LANDING;
		int firstChange = -1;
		int lastChange = -1;
		for (int i = 1; i < length; i++) {
			int delta = track[i] - track[i - 1];
			assertTrue(Math.abs(delta) <= 1, "one block at a time");
			if (delta != 0) {
				changes++;
				firstChange = firstChange < 0 ? i : firstChange;
				lastChange = i;
				assertTrue(run > 0 || flat >= HeightProfile.LANDING, "landing before ramp at " + i);
				run++;
				flat = 0;
				assertTrue(run <= HeightProfile.MAX_RAMP, "ramp too long at " + i);
				// The sloped rail is at i - 1 and rises to i; neither may sit on the corner or next to it.
				assertTrue(Math.abs(i - 205) > 1 && Math.abs(i - 1 - 205) > 1, "ramp touches the corner at " + i);
			} else {
				run = 0;
				flat++;
			}
		}
		assertEquals(20, changes, "climbs the rise and nothing else");
		assertTrue(firstChange > 150 && lastChange < 280, "ramps at " + firstChange + " to " + lastChange);
	}

	@Test
	void doubleTrackRampsKeepFurtherFromCorners() {
		int length = 300;
		int[] ground = new int[length];
		for (int i = 0; i < length; i++) {
			ground[i] = i < 140 ? 70 : 80;
		}
		boolean[] corners = new boolean[length];
		corners[140] = true;
		int[] track = HeightProfile.plan(ground, dry(length), corners, 70, 80, 3);
		for (int i = 1; i < length; i++) {
			if (track[i] != track[i - 1]) {
				assertTrue(Math.abs(i - 140) > 3 && Math.abs(i - 1 - 140) > 3, "ramp near the corner at " + i);
			}
		}
		assertEquals(80, track[length - 1]);
	}

	@Test
	void dipIsFollowedDownInsteadOfWalledOver() {
		int[] ground = filled(400, 70);
		Arrays.fill(ground, 100, 300, 64);
		int[] track = HeightProfile.plan(ground, dry(400), new boolean[400], 70, 70);
		assertTrue(track[200] <= 66, "track at " + track[200] + " over ground at 64");
		for (int i = 0; i < 400; i++) {
			assertTrue(track[i] - ground[i] <= 2, "wall of " + (track[i] - ground[i]) + " at " + i);
		}
	}

	@Test
	void deepValleyIsBridgedRatherThanDescended() {
		int[] ground = filled(300, 70);
		Arrays.fill(ground, 130, 170, 50);
		int[] track = HeightProfile.plan(ground, dry(300), new boolean[300], 70, 70);
		assertTrue(Arrays.stream(track).allMatch(y -> y == 70), "the line stays level over the valley");
		assertEquals(Section.BRIDGE, Section.of(track[150], ground[150], HeightProfile.DRY));
	}

	@Test
	void eightBlockDipIsBridgedRatherThanDescended() {
		int[] ground = filled(300, 72);
		Arrays.fill(ground, 130, 170, 64);
		int[] track = HeightProfile.plan(ground, dry(300), new boolean[300], 72, 72);
		assertTrue(Arrays.stream(track).allMatch(y -> y == 72), "the line stays level over the dip");
	}

	@Test
	void shallowDipIsStillFollowedDown() {
		int[] ground = filled(300, 72);
		Arrays.fill(ground, 120, 180, 68);
		int[] track = HeightProfile.plan(ground, dry(300), new boolean[300], 72, 72);
		assertTrue(track[150] <= 70, "track at " + track[150] + " over a 4-block dip");
	}

	@Test
	void longHighGroundIsTunnelledRatherThanClimbed() {
		// Fifty blocks higher for four hundred: climbing up and down again costs more than staying low in a tunnel.
		int[] ground = filled(600, 70);
		Arrays.fill(ground, 100, 500, 120);
		int[] track = HeightProfile.plan(ground, dry(600), new boolean[600], 70, 70);
		assertTrue(Arrays.stream(track).allMatch(y -> y == 70), "the line stays low");
		assertEquals(Section.TUNNEL, Section.of(track[300], ground[300], HeightProfile.DRY));
	}

	@Test
	void shortDeepGapIsBridged() {
		int[] ground = filled(200, 70);
		Arrays.fill(ground, 95, 105, 40);
		int[] track = HeightProfile.plan(ground, dry(200), new boolean[200], 70, 70);
		assertEquals(70, track[100]);
		assertEquals(Section.BRIDGE, Section.of(track[100], ground[100], HeightProfile.DRY));
	}

	@Test
	void stationsStayFlat() {
		int length = 100;
		int[] ground = filled(length, 70);
		int[] track = HeightProfile.plan(ground, dry(length), new boolean[length], 70, 76);
		for (int i = 1; i <= HeightProfile.STATION_FLAT; i++) {
			assertEquals(track[0], track[i]);
			assertEquals(track[length - 1], track[length - 1 - i]);
		}
	}

	@Test
	void lineClimbsOverWaterHigherThanItsStations() {
		int[] ground = filled(200, 62);
		int[] water = dry(200);
		Arrays.fill(ground, 80, 120, 50);
		Arrays.fill(water, 80, 120, 63);
		int[] track = HeightProfile.plan(ground, water, new boolean[200], 62, 62);
		for (int i = 80; i < 120; i++) {
			assertTrue(track[i] > water[i], "rail at " + track[i] + " in water up to " + water[i]);
		}
	}

	private static int[] filled(int length, int value) {
		int[] array = new int[length];
		Arrays.fill(array, value);
		return array;
	}

	private static int[] dry(int length) {
		return filled(length, HeightProfile.DRY);
	}
}
