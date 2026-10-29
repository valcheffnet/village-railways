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
 * Ramps stay off corners (a curved rail cannot slope) and off the flat stretch at each station. Water is crossed
 * above its surface, but a sea: a stretch of water longer than {@link #MAX_BRIDGE} blocks and deep enough somewhere
 * for a tube is crossed under water, level at the height {@link #tubeLevels} gives it. It goes down to that height on
 * the shore, or in the water where a station stands too close to it.
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
	/** Longest stretch of water a line bridges. */
	public static final int MAX_BRIDGE = 64;
	/** Widest island in a sea that the tube runs on through rather than coming up onto it. */
	public static final int MAX_ISLAND = 16;
	/**
	 * Least depth of a rail in a tube below the water's first free y: the tube's roof, four blocks over the rail, keeps
	 * two blocks of water over it.
	 */
	public static final int TUBE_DEPTH = 7;
	/** Water left under the floor of a tube where the sea bed is highest. */
	private static final int TUBE_CLEARANCE = 2;
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
		int[] tube = new int[ground.length];
		Arrays.fill(tube, DRY);
		return plan(ground, water, tube, corners, start, end, cornerClearance, maxTrench);
	}

	/**
	 * The track heights for a line that crosses seas in tubes: where {@code tube} gives a rail height, from
	 * {@link #tubeLevels}, the rail runs at that height.
	 */
	public static int[] plan(int[] ground, int[] water, int[] tube, boolean[] corners, int start, int end,
			int cornerClearance, int maxTrench) {
		int length = ground.length;
		boolean[] mayRamp = rampAllowed(corners, length, cornerClearance);
		int low = Math.min(start, end);
		int high = Math.max(start, end);
		for (int i = 0; i < length; i++) {
			low = Math.min(low, Math.min(ground[i] - maxTrench - 1, tube[i] == DRY ? ground[i] : tube[i]));
			high = Math.max(high, Math.max(ground[i], water[i] == DRY ? ground[i] : water[i] + 1));
		}
		int levels = high - low + 1;
		int states = levels * MOVES;
		long[] cost = new long[states];
		long[] next = new long[states];
		// For every block and state, the move the step before ended with; the level follows from the move.
		byte[][] from = new byte[length][];
		Arrays.fill(cost, UNREACHABLE);
		cost[(start - low) * MOVES + LANDING] = blockCost(start, ground[0], water[0], tube[0], maxTrench);
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
						cost[state] + blockCost(track, ground[i + 1], water[i + 1], tube[i + 1], maxTrench));
				if (!slopeHere) {
					continue;
				}
				boolean rested = !up && !down && move >= LANDING;
				if (level + 1 < levels && (rested || (up && move - FIRST_UP + 1 < MAX_RAMP))) {
					int upMove = up ? move + 1 : FIRST_UP;
					relax(next, previous, (level + 1) * MOVES + upMove, move, cost[state] + RAMP_BLOCK_COST
							+ (up ? 0 : RAMP_START_COST)
							+ blockCost(track + 1, ground[i + 1], water[i + 1], tube[i + 1], maxTrench));
				}
				if (level > 0 && (rested || (down && move - FIRST_DOWN + 1 < MAX_RAMP))) {
					int downMove = down ? move + 1 : FIRST_DOWN;
					relax(next, previous, (level - 1) * MOVES + downMove, move, cost[state] + RAMP_BLOCK_COST
							+ (down ? 0 : RAMP_START_COST)
							+ blockCost(track - 1, ground[i + 1], water[i + 1], tube[i + 1], maxTrench));
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
	 * The rail height of a tube at each block of a line, or {@link #DRY} where there is none. A tube crosses every
	 * stretch of water longer than {@value #MAX_BRIDGE} blocks, islands of up to {@value #MAX_ISLAND} blocks included,
	 * that is somewhere deep enough for it with {@value #TUBE_CLEARANCE} blocks of water under its floor. It runs level
	 * at the height that leaves that much water under it where the deep sea bed is highest, so it stands on columns
	 * over the rest and cuts into the shallows near the shores.
	 */
	public static int[] tubeLevels(int[] ground, int[] water) {
		int length = ground.length;
		int[] levels = new int[length];
		Arrays.fill(levels, DRY);
		int i = 0;
		while (i < length) {
			if (water[i] == DRY) {
				i++;
				continue;
			}
			int first = i;
			int last = i;
			for (int j = i; j < length; ) {
				if (water[j] != DRY) {
					last = j++;
					continue;
				}
				int land = j;
				while (land < length && water[land] == DRY) {
					land++;
				}
				if (land == length || land - j > MAX_ISLAND) {
					break;
				}
				j = land;
			}
			i = last + 1;
			if (last - first + 1 <= MAX_BRIDGE) {
				continue;
			}
			int surface = Integer.MAX_VALUE;
			int level = DRY;
			for (int j = first; j <= last; j++) {
				if (water[j] != DRY) {
					surface = Math.min(surface, water[j]);
					int onBed = ground[j] + 1 + TUBE_CLEARANCE;
					if (onBed <= water[j] - TUBE_DEPTH) {
						level = Math.max(level, onBed);
					}
				}
			}
			if (level != DRY) {
				Arrays.fill(levels, first, last + 1, Math.min(level, surface - TUBE_DEPTH));
			}
		}
		return levels;
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
	 * only way and costs less; in a tube the rail lies at the tube's height, and above it only at a cost.
	 */
	static long blockCost(int track, int ground, int water, int tube, int maxTrench) {
		if (tube != DRY) {
			if (track <= tube) {
				return track == tube ? TUBE_COST : UNREACHABLE;
			}
			// Over the tube's level only where the line has to: going down to it from a station by the water.
			long lifted = TUBE_COST + TUBE_LIFT_COST * (track - tube);
			return water != DRY && track > water ? lifted + WATER_BRIDGE_COST : lifted;
		}
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
			case TUBE -> UNREACHABLE;
		};
	}

	private static final int WATER_BRIDGE_COST = 120;
	private static final int DRY_BRIDGE_COST = 250;
	private static final int TUNNEL_COST = 100;
	private static final int TUBE_COST = 150;
	private static final int TUBE_LIFT_COST = 100;
}
