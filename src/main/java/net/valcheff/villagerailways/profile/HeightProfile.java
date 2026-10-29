package net.valcheff.villagerailways.profile;

import java.util.Arrays;

/**
 * Chooses the track height along a line.
 *
 * <p>The track follows the terrain the way a railway would: it runs level where it can, cuts through small rises in
 * an open trench, and climbs or drops on 1:1 ramps of at most {@link #MAX_RAMP} blocks with flat landings of at least
 * {@link #LANDING} blocks between them. Every block of climb or drop costs, so the track does not bob over bumps,
 * climb a hill to come down again where a tunnel would do, or dive into a deep valley a bridge could span. A fill or
 * a bridge over low dry land costs more than going down to the ground, so it never builds a wall to stay level.
 * Ramps stay off corners (a curved rail cannot slope) and off the flat stretch at each station. Water is always
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
	/** Cost of every block of climb or drop: against the tunnel and bridge costs it decides over or through. */
	private static final int RAMP_BLOCK_COST = 800;
	/** Extra cost of starting a ramp, so a few longer ramps win over many short ones. */
	private static final int RAMP_START_COST = 600;
	private static final long UNREACHABLE = Long.MAX_VALUE / 4;

	// Moves, the last step taken: 0..LANDING are flat steps counted up to LANDING, then ramps up of length 1..MAX_RAMP,
	// then ramps down of length 1..MAX_RAMP.
	private static final int FIRST_UP = LANDING + 1;
	private static final int FIRST_DOWN = FIRST_UP + MAX_RAMP;
	private static final int MOVES = FIRST_DOWN + MAX_RAMP;

	private HeightProfile() {
	}

	/**
	 * The track heights for a line, or null when no track keeps above every stretch of water.
	 *
	 * @param ground  first free y above solid ground, per block along the line
	 * @param water   first free y above water, or {@link #DRY}
	 * @param corners whether the line turns at each block
	 * @param start   track height at the first station
	 * @param end     track height at the second station
	 */
	public static int[] plan(int[] ground, int[] water, boolean[] corners, int start, int end) {
		return plan(ground, water, corners, start, end, 1, Section.MAX_TRENCH);
	}

	/**
	 * The track heights for a line whose ramps keep {@code cornerClearance} blocks from every corner and whose open
	 * cuts are at most {@code maxTrench} deep, or null when no track keeps above every stretch of water.
	 */
	public static int[] plan(int[] ground, int[] water, boolean[] corners, int start, int end, int cornerClearance,
			int maxTrench) {
		int length = ground.length;
		boolean[] mayRamp = rampAllowed(corners, length, cornerClearance);
		int low = Math.min(start, end);
		int high = Math.max(start, end);
		for (int i = 0; i < length; i++) {
			low = Math.min(low, ground[i] - maxTrench - 1);
			high = Math.max(high, Math.max(ground[i], water[i] == DRY ? ground[i] : water[i] + 1));
		}
		int levels = high - low + 1;
		int states = levels * MOVES;
		long[] cost = new long[states];
		long[] next = new long[states];
		// For every block and state, the move the step before ended with; the level follows from the move.
		byte[][] from = new byte[length][];
		Arrays.fill(cost, UNREACHABLE);
		cost[(start - low) * MOVES + LANDING] = blockCost(start, ground[0], water[0], maxTrench);
		for (int i = 0; i + 1 < length; i++) {
			Arrays.fill(next, UNREACHABLE);
			byte[] previous = new byte[states];
			boolean slopeHere = mayRamp[i] && mayRamp[i + 1];
			for (int state = 0; state < states; state++) {
				if (cost[state] >= UNREACHABLE) {
					continue;
				}
				int level = state / MOVES;
				int move = state % MOVES;
				int track = low + level;
				boolean up = move >= FIRST_UP && move < FIRST_DOWN;
				boolean down = move >= FIRST_DOWN;
				int flatMove = up || down ? 1 : Math.min(move + 1, LANDING);
				relax(next, previous, level * MOVES + flatMove, move,
						cost[state] + blockCost(track, ground[i + 1], water[i + 1], maxTrench));
				if (!slopeHere) {
					continue;
				}
				boolean rested = !up && !down && move >= LANDING;
				if (level + 1 < levels && (rested || (up && move - FIRST_UP + 1 < MAX_RAMP))) {
					int upMove = up ? move + 1 : FIRST_UP;
					relax(next, previous, (level + 1) * MOVES + upMove, move, cost[state] + RAMP_BLOCK_COST
							+ (up ? 0 : RAMP_START_COST) + blockCost(track + 1, ground[i + 1], water[i + 1], maxTrench));
				}
				if (level > 0 && (rested || (down && move - FIRST_DOWN + 1 < MAX_RAMP))) {
					int downMove = down ? move + 1 : FIRST_DOWN;
					relax(next, previous, (level - 1) * MOVES + downMove, move, cost[state] + RAMP_BLOCK_COST
							+ (down ? 0 : RAMP_START_COST) + blockCost(track - 1, ground[i + 1], water[i + 1], maxTrench));
				}
			}
			from[i + 1] = previous;
			long[] swap = cost;
			cost = next;
			next = swap;
		}

		int bestState = -1;
		for (int move = 0; move < MOVES; move++) {
			int state = (end - low) * MOVES + move;
			if (cost[state] < UNREACHABLE && (bestState < 0 || cost[state] < cost[bestState])) {
				bestState = state;
			}
		}
		if (bestState < 0) {
			return null;
		}
		int[] track = new int[length];
		int state = bestState;
		for (int i = length - 1; i >= 0; i--) {
			int level = state / MOVES;
			int move = state % MOVES;
			track[i] = low + level;
			if (i > 0) {
				int previousLevel = move >= FIRST_DOWN ? level + 1 : move >= FIRST_UP ? level - 1 : level;
				state = previousLevel * MOVES + from[i][state];
			}
		}
		return track;
	}

	private static void relax(long[] next, byte[] previous, int state, int fromMove, long value) {
		if (value < next[state]) {
			next[state] = value;
			previous[state] = (byte) fromMove;
		}
	}

	/**
	 * Where the rail may change height: not within {@code cornerClearance} blocks of a corner, and not on a station's
	 * flat stretch.
	 */
	static boolean[] rampAllowed(boolean[] corners, int length, int cornerClearance) {
		boolean[] allowed = new boolean[length];
		for (int i = 0; i < length; i++) {
			allowed[i] = i >= STATION_FLAT && i < length - STATION_FLAT;
		}
		for (int i = 0; i < length; i++) {
			if (corners[i]) {
				for (int j = Math.max(0, i - cornerClearance); j <= Math.min(length - 1, i + cornerClearance); j++) {
					allowed[j] = false;
				}
			}
		}
		return allowed;
	}

	/**
	 * Cost of one block of line, roughly in tenths of a block of work. An open trench is cheap and widens with its
	 * depth; a tunnel costs the same at any depth, a little more than the deepest trench. A fill costs far more than a
	 * trench of the same height and grows fast up to the cost of a bridge; a bridge over dry land costs the same at any
	 * height, so it loses to going down on low ground and wins over deep, short valleys. Over water a bridge is the
	 * only way and costs less.
	 */
	static long blockCost(int track, int ground, int water, int maxTrench) {
		if (water != DRY) {
			return track > water ? WATER_BRIDGE_COST + 5L * (track - ground) : UNREACHABLE;
		}
		Section section = Section.of(track, ground, water, maxTrench);
		int difference = Math.abs(track - ground);
		return switch (section) {
			case FLAT -> 0;
			case FILL -> Math.min(60L * difference * difference, DRY_BRIDGE_COST);
			case BRIDGE -> DRY_BRIDGE_COST + 2L * difference;
			case TRENCH -> difference * (5L + difference);
			case TUNNEL -> TUNNEL_COST;
		};
	}

	private static final int WATER_BRIDGE_COST = 120;
	private static final int DRY_BRIDGE_COST = 250;
	private static final int TUNNEL_COST = 100;
}
