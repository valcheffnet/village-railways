package net.valcheff.villagerailways.profile;

import java.util.Arrays;

/**
 * Chooses the track height along a line.
 *
 * <p>The track only ever climbs (or only ever drops) from the first station's height to the second station's: it never
 * goes up a hill to come down again, so hills are cut through and dips filled or bridged. The climb is made of 1:1
 * ramps of at most {@link #MAX_RAMP} blocks with flat landings of at least {@link #LANDING} blocks between them, spread
 * along the line where they save the most earthwork, and each new ramp costs extra so there are as few as the terrain
 * allows. Ramps stay off corners (a curved rail cannot slope) and off the flat stretch at each station. Water is always
 * crossed above its surface.
 *
 * <p>Heights are rail heights: the y of the rail block. {@code ground[i]} is the first free y above solid ground and
 * {@code water[i]} the first free y above water, or {@link #DRY} where there is none, so a rail resting on the
 * ground has {@code track == ground}.
 */
public final class HeightProfile {
	public static final int DRY = Integer.MIN_VALUE;
	/** Longest ramp without a landing. */
	public static final int MAX_RAMP = 8;
	/** Flat blocks between two ramps. */
	public static final int LANDING = 3;
	/** Flat blocks at each end of a line, for the station. */
	public static final int STATION_FLAT = 12;
	/** Extra cost of starting a ramp, so a few long ramps win over many short ones. */
	private static final int RAMP_COST = 400;
	private static final long UNREACHABLE = Long.MAX_VALUE / 4;

	private HeightProfile() {
	}

	/**
	 * The track heights for a line, or null when no climb keeps the track above every stretch of water.
	 *
	 * @param ground  first free y above solid ground, per block along the line
	 * @param water   first free y above water, or {@link #DRY}
	 * @param corners whether the line turns at each block
	 * @param start   track height at the first station
	 * @param end     track height at the second station
	 */
	public static int[] plan(int[] ground, int[] water, boolean[] corners, int start, int end) {
		int length = ground.length;
		boolean[] mayRamp = rampAllowed(corners, length);
		int climb = Math.abs(end - start);
		int step = end >= start ? 1 : -1;
		// State per block: how far the track has climbed (0..climb) and what the last move was. Moves 0..LANDING are
		// flat ones counted up to LANDING; moves LANDING+1..LANDING+MAX_RAMP are ramps of length 1..MAX_RAMP.
		int moves = LANDING + 1 + MAX_RAMP;
		int states = (climb + 1) * moves;
		long[] cost = new long[states];
		long[] next = new long[states];
		int[][] from = new int[length][];
		Arrays.fill(cost, UNREACHABLE);
		cost[LANDING] = blockCost(start, ground[0], water[0]);
		for (int i = 0; i + 1 < length; i++) {
			Arrays.fill(next, UNREACHABLE);
			int[] previous = new int[states];
			boolean slopeHere = mayRamp[i] && mayRamp[i + 1];
			for (int state = 0; state < states; state++) {
				if (cost[state] >= UNREACHABLE) {
					continue;
				}
				int climbed = state / moves;
				int move = state % moves;
				boolean onRamp = move > LANDING;
				int flatMove = onRamp ? 1 : Math.min(move + 1, LANDING);
				relax(next, previous, climbed * moves + flatMove, state,
						cost[state] + blockCost(start + step * climbed, ground[i + 1], water[i + 1]));
				boolean canStartRamp = !onRamp && move >= LANDING;
				boolean canContinueRamp = onRamp && move - LANDING < MAX_RAMP;
				if (slopeHere && climbed < climb && (canStartRamp || canContinueRamp)) {
					int rampMove = onRamp ? move + 1 : LANDING + 1;
					long extra = onRamp ? 0 : RAMP_COST;
					relax(next, previous, (climbed + 1) * moves + rampMove, state,
							cost[state] + extra + blockCost(start + step * (climbed + 1), ground[i + 1], water[i + 1]));
				}
			}
			from[i + 1] = previous;
			long[] swap = cost;
			cost = next;
			next = swap;
		}

		int bestState = -1;
		for (int move = 0; move < moves; move++) {
			int state = climb * moves + move;
			if (cost[state] < UNREACHABLE && (bestState < 0 || cost[state] < cost[bestState])) {
				bestState = state;
			}
		}
		if (bestState < 0) {
			return null;
		}
		int[] level = new int[length];
		int state = bestState;
		for (int i = length - 1; i >= 0; i--) {
			level[i] = start + step * (state / moves);
			if (i > 0) {
				state = from[i][state];
			}
		}
		return level;
	}

	private static void relax(long[] next, int[] previous, int state, int fromState, long value) {
		if (value < next[state]) {
			next[state] = value;
			previous[state] = fromState;
		}
	}

	/** Where the rail may change height: not at a corner or next to one, and not on a station's flat stretch. */
	static boolean[] rampAllowed(boolean[] corners, int length) {
		boolean[] allowed = new boolean[length];
		for (int i = 0; i < length; i++) {
			allowed[i] = i >= STATION_FLAT && i < length - STATION_FLAT;
		}
		for (int i = 0; i < length; i++) {
			if (corners[i]) {
				for (int j = Math.max(0, i - 1); j <= Math.min(length - 1, i + 1); j++) {
					allowed[j] = false;
				}
			}
		}
		return allowed;
	}

	/**
	 * Cost of one block of line, roughly in tenths of a block of work. A trench widens with its depth (sloped sides);
	 * a tunnel costs the same at any depth and a little more than the deepest trench, so cuts grow into tunnels only
	 * where they must. A bridge costs more than a low embankment and grows with its pillars.
	 */
	static long blockCost(int track, int ground, int water) {
		if (water != DRY) {
			return track > water ? BRIDGE_COST + 10 * (track - ground) : UNREACHABLE;
		}
		Section section = Section.of(track, ground, water);
		int difference = Math.abs(track - ground);
		return switch (section) {
			case FLAT -> 0;
			case FILL -> 30 * difference;
			case BRIDGE -> BRIDGE_COST + 10 * difference;
			case TRENCH -> difference * (15 + 4 * difference);
			case TUNNEL -> TUNNEL_COST;
		};
	}

	private static final int BRIDGE_COST = 100;
	private static final int TUNNEL_COST = 200;
}
