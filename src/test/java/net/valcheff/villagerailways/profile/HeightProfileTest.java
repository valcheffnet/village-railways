package net.valcheff.villagerailways.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
		Arrays.fill(ground, 150, 220, 90);
		int[] track = HeightProfile.plan(ground, dry(300), new boolean[300], 70, 70);
		assertTrue(Arrays.stream(track).allMatch(y -> y == 70), "the line stays level");
		assertEquals(Section.TRENCH, Section.of(track[70], ground[70], HeightProfile.DRY));
		assertEquals(Section.TUNNEL, Section.of(track[180], ground[180], HeightProfile.DRY));
	}

	@Test
	void waterIsBridgedAboveItsSurface() {
		int[] ground = filled(200, 70);
		int[] water = dry(200);
		Arrays.fill(ground, 80, 120, 55);
		Arrays.fill(water, 80, 120, 64);
		int[] track = HeightProfile.plan(ground, water, new boolean[200], 70, 70);
		assertEquals(70, track[100]);
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
			assertTrue(delta >= 0 && delta <= 1, "only climbs, one block at a time");
			if (delta == 1) {
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
		assertEquals(20, changes);
		assertTrue(firstChange > 150 && lastChange < 280, "ramps at " + firstChange + " to " + lastChange);
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
	void lineBelowTheWaterHasNoProfile() {
		int[] ground = filled(100, 62);
		int[] water = dry(100);
		Arrays.fill(ground, 40, 60, 50);
		Arrays.fill(water, 40, 60, 63);
		assertNull(HeightProfile.plan(ground, water, new boolean[100], 62, 62));
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
