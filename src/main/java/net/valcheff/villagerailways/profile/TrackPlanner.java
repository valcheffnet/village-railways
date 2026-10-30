package net.valcheff.villagerailways.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.valcheff.villagerailways.network.Point;

/**
 * Lays a planned line out block by block and gives it a height profile, reading the terrain from the generator's
 * noise so the chunks need not exist. The noise is the terrain before caves, ravines and surface features are carved
 * in; those are handled when the line is built.
 */
public class TrackPlanner {
	/** Distance between the rails of a double track. */
	public static final int DOUBLE_TRACK = 2;
	/**
	 * The ramps of a double track keep this far from corners, so the corners of the second rail, up to two blocks
	 * from the line's, are level too.
	 */
	private static final int DOUBLE_CORNER_CLEARANCE = 3;
	/** Shortest stretch of second rail worth laying along a trunk line. */
	private static final int MIN_SECOND_RAIL = 40;
	/** Shortest second rail over a bridge. */
	private static final int MIN_BRIDGE_SECOND_RAIL = 16;
	/** Shortest bridge a line runs over as a double track. */
	private static final int MIN_DOUBLE_BRIDGE = 5;
	/** Widest stretch of ground between two bridges that one double track runs on across. */
	public static final int MAX_BRIDGE_GAP = 16;
	/** How far before and after a double bridge its second rail may branch off and join, on level ground. */
	private static final int BRIDGE_APPROACH = 8;
	/** The same for a tube, whose shores go down to it on ramps. */
	private static final int TUBE_APPROACH = 32;
	/** Longest stretch of a shore a tube runs on through while the rail is below the water's surface. */
	private static final int MAX_SHORE_TUBE = 64;
	/**
	 * Most ground between a double bridge and a station with a second track for it: the second rail runs on into the
	 * station instead of joining the line for those few blocks.
	 */
	private static final int STATION_REACH = 40;
	/** Closest to a line's ends a second rail branches off or joins. */
	private static final int SPLIT_FROM_END = 8;
	/** Level, straight blocks either side of the block where a second rail branches off or joins. */
	private static final int SPLIT_REACH = 3;
	/** Shortest straight of a second rail between two of its corners. */
	private static final int MIN_SECOND_RUN = 3;
	/** Shortest tunnel worth boring; a shorter one is cut open. */
	private static final int MIN_TUNNEL = 8;
	/**
	 * How far either side of the line a tunnel needs ground over it: the width of the double tunnel, whose second rail
	 * may run on either side.
	 */
	private static final int TUNNEL_REACH = DOUBLE_TRACK + 2;
	/** Deepest open cut a tunnel too short or with too little ground over its side becomes. */
	private static final int MAX_OPEN_CUT = 12;

	/** Where a second rail may run. */
	@FunctionalInterface
	public interface Area {
		boolean contains(int x, int z);
	}

	private final TerrainSampler terrain;
	private final int cutDepth;
	private final boolean seaTubes;

	public TrackPlanner(ServerLevel level, TrackSettings settings) {
		this.terrain = new TerrainSampler(level);
		this.cutDepth = settings.cutDepth();
		this.seaTubes = settings.seaTubes();
	}

	/**
	 * The block-by-block plan of a line through the given corners, from a station at {@code startHeight} to one at
	 * {@code endHeight}, or null when no height profile fits it.
	 */
	public TrackPlan plan(List<Point> path, int startHeight, int endHeight) {
		return plan(path, startHeight, endHeight, null, false, false, (x, z) -> true);
	}

	/**
	 * The plan of a line, as a double track wherever a second rail fits: on the line's left, {@value #DOUBLE_TRACK}
	 * blocks over, at the line's heights. A trunk line runs double inside {@code trunk} (null for any other line), and
	 * every line over its bridges in the open; never where {@code clear} refuses, beside another track. A second rail
	 * branches off the line and joins it again on level, straight ground. At an open end, a station with a second
	 * track for it, the second rail runs on to the line's end beside it instead, into that track.
	 */
	public TrackPlan plan(List<Point> path, int startHeight, int endHeight, Area trunk, boolean openStart,
			boolean openEnd, Area clear) {
		Layout layout = layout(path);
		int cornerClearance = trunk == null ? 1 : DOUBLE_CORNER_CLEARANCE;
		int[] tubes = HeightProfile.tubeLevels(layout.ground(), layout.water());
		if (!seaTubes) {
			Arrays.fill(tubes, HeightProfile.DRY);
		}
		int[] track = HeightProfile.plan(layout.ground(), layout.water(), tubes, layout.corner(), startHeight,
				endHeight, cornerClearance, cutDepth);
		if (track == null) {
			// No room to go down to a tube between a station and the shore: the water is bridged.
			Arrays.fill(tubes, HeightProfile.DRY);
			track = HeightProfile.plan(layout.ground(), layout.water(), layout.corner(), startHeight, endHeight,
					cornerClearance, cutDepth);
		}
		if (track == null) {
			return null;
		}
		TrackPlan line = openTunnels(tubeShores(plan(layout, track), tubes));
		boolean[] bridged = bridgeDoubles(line, clear, openStart, openEnd);
		boolean[] doubled = bridged.clone();
		for (int i = 0; trunk != null && i < line.length(); i++) {
			doubled[i] |= trunk.contains(line.x()[i], line.z()[i]);
		}
		return line.withSeconds(secondRails(line, doubled, bridged, openStart, openEnd));
	}

	/**
	 * Marks the land a line's tubes run through: the islands in a sea while the rail is below its surface, and the
	 * shores on from a tube the same way, up to {@value #MAX_SHORE_TUBE} blocks. A cut or a short tunnel there would let
	 * the sea in.
	 */
	private static TrackPlan tubeShores(TrackPlan line, int[] tubes) {
		for (int i = 0; i < line.length(); i++) {
			if (tubes[i] == HeightProfile.DRY) {
				continue;
			}
			int last = i;
			int surface = line.water()[i];
			while (last + 1 < line.length() && tubes[last + 1] != HeightProfile.DRY) {
				last++;
				surface = Math.max(surface, line.water()[last]);
			}
			for (int j = i; j <= last; j++) {
				if (line.water()[j] == HeightProfile.DRY && line.track()[j] < surface) {
					line.sections()[j] = Section.TUBE;
				}
			}
			if (line.sections()[i] == Section.TUBE) {
				tubeShore(line, i, -1, surface);
			}
			if (line.sections()[last] == Section.TUBE) {
				tubeShore(line, last, 1, surface);
			}
			i = last;
		}
		return line;
	}

	private static void tubeShore(TrackPlan line, int end, int step, int surface) {
		for (int n = 1; n <= MAX_SHORE_TUBE; n++) {
			int i = end + step * n;
			if (i < 0 || i >= line.length() || line.water()[i] != HeightProfile.DRY || line.track()[i] >= surface) {
				return;
			}
			line.sections()[i] = Section.TUBE;
		}
	}

	/**
	 * Where a line runs double over its bridges and through its tubes: every bridge of at least
	 * {@value #MIN_DOUBLE_BRIDGE} blocks, with {@value #BRIDGE_APPROACH} blocks either side ({@value #TUBE_APPROACH}
	 * for a tube) where the rails may branch off and join on level ground. Two bridges with up to
	 * {@value #MAX_BRIDGE_GAP} blocks of ground between them make one, so the double track does not join and branch off
	 * again; one that ends within {@value #STATION_REACH} blocks of an open end runs on to it. Blocks {@code clear}
	 * refuses, beside another track, stay single.
	 */
	private static boolean[] bridgeDoubles(TrackPlan line, Area clear, boolean openStart, boolean openEnd) {
		int length = line.length();
		List<int[]> runs = new ArrayList<>();
		int start = -1;
		for (int i = 0; i <= length; i++) {
			Section section = i < length ? line.sections()[i] : null;
			boolean bridge = section == Section.BRIDGE || section == Section.TUBE;
			if (bridge && start < 0) {
				start = i;
			} else if (!bridge && start >= 0) {
				int[] previous = runs.isEmpty() ? null : runs.getLast();
				if (previous != null && start - previous[1] - 1 <= MAX_BRIDGE_GAP
						&& isGround(line, previous[1] + 1, start - 1)) {
					previous[1] = i - 1;
				} else {
					runs.add(new int[] {start, i - 1});
				}
				start = -1;
			}
		}
		boolean[] doubled = new boolean[length];
		for (int[] run : runs) {
			if (run[1] - run[0] + 1 < MIN_DOUBLE_BRIDGE) {
				continue;
			}
			boolean tube = false;
			for (int i = run[0]; i <= run[1]; i++) {
				tube |= line.sections()[i] == Section.TUBE;
			}
			int approach = tube ? TUBE_APPROACH : BRIDGE_APPROACH;
			int from = openStart && run[0] <= STATION_REACH ? 0 : Math.max(0, run[0] - approach);
			int to = openEnd && length - 1 - run[1] <= STATION_REACH ? length - 1
					: Math.min(length - 1, run[1] + approach);
			for (int i = from; i <= to; i++) {
				doubled[i] = clear.contains(line.x()[i], line.z()[i]);
			}
		}
		return doubled;
	}

	/** Whether blocks {@code first} to {@code last} of a line run on the ground. */
	private static boolean isGround(TrackPlan line, int first, int last) {
		for (int i = first; i <= last; i++) {
			Section section = line.sections()[i];
			if (section != Section.FLAT && section != Section.FILL && section != Section.TRENCH) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The plan of a station track: level all along at {@code height}. A station is never a bridge: where the ground
	 * drops away under it, it stands on a fill down to the ground.
	 */
	public TrackPlan planStation(List<Point> path, int height) {
		Layout layout = layout(path);
		int[] track = new int[layout.x().length];
		Arrays.fill(track, height);
		TrackPlan plan = plan(layout, track);
		for (int i = 0; i < plan.length(); i++) {
			if (plan.sections()[i] == Section.BRIDGE) {
				plan.sections()[i] = Section.FILL;
			}
		}
		return plan;
	}

	/**
	 * The second track of a western station: level at {@code height}, each block beside the block of the station
	 * track with the same index.
	 */
	public TrackPlan planStationSecond(List<Point> path, int height) {
		TrackPlan plan = planStation(path, height);
		int[] beside = new int[plan.length()];
		Arrays.setAll(beside, i -> i);
		return new TrackPlan(plan.x(), plan.z(), plan.track(), plan.ground(), plan.water(), plan.corner(), plan.sections(),
				new int[0], new int[0], List.of(), beside, false);
	}

	/** The rail height of a station standing at a block: on the ground, or a block over water. */
	public int stationHeight(Point at) {
		TerrainSampler.Column column = terrain.sample(at.x(), at.z());
		return column.water() == HeightProfile.DRY ? column.ground() : column.water() + 1;
	}

	private TrackPlan plan(Layout layout, int[] track) {
		Section[] sections = new Section[track.length];
		for (int i = 0; i < track.length; i++) {
			sections[i] = Section.of(track[i], layout.ground()[i], layout.water()[i], cutDepth);
		}
		return new TrackPlan(layout.x(), layout.z(), track, layout.ground(), layout.water(), layout.corner(), sections,
				new int[0], new int[0], List.of(), new int[0], false);
	}

	/**
	 * Cuts open the tunnels that are not worth one: shorter than {@value #MIN_TUNNEL} blocks, or with the ground beside
	 * the line, within {@value #TUNNEL_REACH} blocks, no higher than an open cut reaches, so the tunnel would run under
	 * a thin skin of hillside. A cut deeper than {@value #MAX_OPEN_CUT} blocks stays a tunnel.
	 */
	private TrackPlan openTunnels(TrackPlan line) {
		Section[] sections = line.sections();
		for (int i = 0; i < line.length(); i++) {
			if (sections[i] == Section.TUNNEL && line.ground()[i] - line.track()[i] <= MAX_OPEN_CUT
					&& !coveredAcross(line, i)) {
				sections[i] = Section.TRENCH;
			}
		}
		for (int i = 0; i < line.length(); i++) {
			if (sections[i] != Section.TUNNEL) {
				continue;
			}
			int last = i;
			while (last + 1 < line.length() && sections[last + 1] == Section.TUNNEL) {
				last++;
			}
			boolean shallow = true;
			for (int j = i; j <= last; j++) {
				shallow &= line.ground()[j] - line.track()[j] <= MAX_OPEN_CUT;
			}
			if (last - i + 1 < MIN_TUNNEL && shallow) {
				Arrays.fill(sections, i, last + 1, Section.TRENCH);
			}
			i = last;
		}
		return line;
	}

	/** Whether the ground on both sides of block i of a line stands high enough over the rail for a tunnel. */
	private boolean coveredAcross(TrackPlan line, int i) {
		Direction along = line.direction(i < line.length() - 1 ? i : i - 1, i < line.length() - 1 ? i + 1 : i);
		Direction left = along.getCounterClockWise();
		for (int side : new int[] {-TUNNEL_REACH, TUNNEL_REACH}) {
			TerrainSampler.Column column = terrain.sample(line.x()[i] + left.getStepX() * side,
					line.z()[i] + left.getStepZ() * side);
			if (column.water() != HeightProfile.DRY || column.ground() - line.track()[i] <= cutDepth) {
				return false;
			}
		}
		return true;
	}

	/** The second rails beside the blocks of a line marked {@code doubled}, those over bridges marked {@code bridged}. */
	private List<TrackPlan> secondRails(TrackPlan line, boolean[] doubled, boolean[] bridged, boolean openStart,
			boolean openEnd) {
		boolean[] split = new boolean[line.length()];
		for (int i = 0; i < line.length(); i++) {
			split[i] = maySplit(line, i, doubled);
		}
		List<TrackPlan> seconds = new ArrayList<>();
		for (int i = 0; i < line.length(); i++) {
			if (!doubled[i]) {
				continue;
			}
			int last = i;
			while (last + 1 < line.length() && doubled[last + 1]) {
				last++;
			}
			addSecondRails(line, split, bridged, i, last, seconds, openStart && i == 0,
					openEnd && last == line.length() - 1);
			i = last;
		}
		return List.copyOf(seconds);
	}

	/**
	 * Where a second rail may branch off or join: level, straight, open ground, with no corner close by and
	 * {@value #SPLIT_FROM_END} blocks or more from the line's ends.
	 */
	private static boolean maySplit(TrackPlan line, int i, boolean[] doubled) {
		if (i < SPLIT_FROM_END || i > line.length() - 1 - SPLIT_FROM_END || !doubled[i]) {
			return false;
		}
		for (int j = i - SPLIT_REACH; j <= i + SPLIT_REACH; j++) {
			if (line.track()[j] != line.track()[i] || line.corner()[j]) {
				return false;
			}
		}
		return isGround(line, i - 2, i + 2);
	}

	/**
	 * Second rails beside blocks {@code first} to {@code last} of a line: one that follows the line all the way, or
	 * pieces either side of a corner it cannot go round, too tight or with a ramp at it. At an open end the second
	 * rail starts or stops beside that block instead of branching off on level ground.
	 */
	private void addSecondRails(TrackPlan line, boolean[] split, boolean[] bridged, int first, int last,
			List<TrackPlan> seconds, boolean openFrom, boolean openTo) {
		int from = first;
		while (!openFrom && from <= last && !split[from]) {
			from++;
		}
		int to = last;
		while (!openTo && to >= from && !split[to]) {
			to--;
		}
		boolean overBridge = false;
		for (int i = Math.max(from, 0); i <= to; i++) {
			overBridge |= bridged[i];
		}
		if (to - from + 1 < (overBridge ? MIN_BRIDGE_SECOND_RAIL : MIN_SECOND_RAIL)) {
			return;
		}
		List<Integer> corners = new ArrayList<>();
		for (int i = from + 1; i < to; i++) {
			if (line.corner()[i]) {
				corners.add(i);
			}
		}
		List<Point> path = secondPath(line, from, to, corners, openFrom, openTo);
		int broken = brokenCorner(path, corners, openFrom ? 0 : 1);
		if (broken < 0) {
			TrackPlan second = secondRail(line, path, from, to, openFrom, openTo);
			if (second != null) {
				seconds.add(second);
				return;
			}
			if (corners.isEmpty()) {
				return;
			}
			// A ramp at a corner the second rail cannot take: the pieces either side of the corner.
			broken = corners.getFirst();
		}
		addSecondRails(line, split, bridged, from, broken - 1, seconds, openFrom, false);
		addSecondRails(line, split, bridged, broken + 1, to, seconds, false, openTo);
	}

	/**
	 * The corners of a second rail: a step out to the left at block {@code from}, the line's corners moved
	 * {@value #DOUBLE_TRACK} blocks out from both their straights, and the step back in at block {@code to}. At an open
	 * end there is no step: the second rail starts or stops beside the block.
	 */
	private static List<Point> secondPath(TrackPlan line, int from, int to, List<Integer> corners, boolean openFrom,
			boolean openTo) {
		List<Point> path = new ArrayList<>();
		Direction out = line.direction(from, from + 1).getCounterClockWise();
		if (!openFrom) {
			path.add(shifted(line, from, out, 1, null));
		}
		path.add(shifted(line, from, out, DOUBLE_TRACK, null));
		for (int corner : corners) {
			path.add(shifted(line, corner, line.direction(corner - 1, corner).getCounterClockWise(), DOUBLE_TRACK,
					line.direction(corner, corner + 1).getCounterClockWise()));
		}
		Direction in = line.direction(to - 1, to).getCounterClockWise();
		path.add(shifted(line, to, in, DOUBLE_TRACK, null));
		if (!openTo) {
			path.add(shifted(line, to, in, 1, null));
		}
		return path;
	}

	private static Point shifted(TrackPlan line, int i, Direction left, int distance, Direction alsoLeft) {
		int x = line.x()[i] + left.getStepX() * distance;
		int z = line.z()[i] + left.getStepZ() * distance;
		if (alsoLeft != null) {
			x += alsoLeft.getStepX() * distance;
			z += alsoLeft.getStepZ() * distance;
		}
		return new Point(x, z);
	}

	/**
	 * The line's corner at the first straight of a second rail that comes out too short or turns back on itself,
	 * where the line turns twice towards the second rail close together; -1 when every straight is fine.
	 */
	private static int brokenCorner(List<Point> path, List<Integer> corners, int start) {
		// The path is the step out, if any, a point per corner and the step in, so straight k runs from point k + start.
		if (corners.isEmpty()) {
			return -1;
		}
		for (int k = 0; k <= corners.size(); k++) {
			Point a = path.get(k + start);
			Point b = path.get(k + start + 1);
			int length = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
			if ((a.x() != b.x() && a.z() != b.z()) || length < MIN_SECOND_RUN) {
				return corners.get(Math.min(k, corners.size() - 1));
			}
		}
		return -1;
	}

	/**
	 * Lays a second rail out along its corners and gives every block the height and section of the block of the line
	 * it runs beside. Null when that would leave a slope it cannot take, which the corner clearance of a double
	 * track's ramps keeps from happening.
	 */
	private TrackPlan secondRail(TrackPlan line, List<Point> path, int from, int to, boolean openFrom, boolean openTo) {
		Layout layout = layout(path);
		int length = layout.x().length;
		// Where the second rail runs beside each block of the line; at a corner, beside it both ways.
		Map<Long, Integer> besideAt = new HashMap<>();
		for (int i = from; i <= to; i++) {
			if (i < line.length() - 1) {
				Direction ahead = line.direction(i, i + 1).getCounterClockWise();
				besideAt.put(ChunkPos.asLong(line.x()[i] + ahead.getStepX() * DOUBLE_TRACK,
						line.z()[i] + ahead.getStepZ() * DOUBLE_TRACK), i);
			}
			if (i > 0) {
				Direction back = line.direction(i - 1, i).getCounterClockWise();
				besideAt.putIfAbsent(ChunkPos.asLong(line.x()[i] + back.getStepX() * DOUBLE_TRACK,
						line.z()[i] + back.getStepZ() * DOUBLE_TRACK), i);
			}
		}
		int[] beside = new int[length];
		int[] track = new int[length];
		Section[] sections = new Section[length];
		int at = from;
		for (int p = 0; p < length; p++) {
			boolean stepIn = !openTo && p >= length - 2;
			Integer found = stepIn ? Integer.valueOf(to) : besideAt.get(ChunkPos.asLong(layout.x()[p], layout.z()[p]));
			if ((openFrom || p >= 2) && found != null) {
				at = Math.max(at, found);
			}
			beside[p] = at;
			track[p] = line.track()[at];
			sections[p] = line.sections()[at];
		}
		for (int p = 1; p < length; p++) {
			boolean slopeAtCorner = layout.corner()[p - 1] && (track[p] != track[p - 1]
					|| (p >= 2 && track[p - 2] != track[p - 1]));
			if (Math.abs(track[p] - track[p - 1]) > 1 || slopeAtCorner) {
				return null;
			}
		}
		return new TrackPlan(layout.x(), layout.z(), track, layout.ground(), layout.water(), layout.corner(), sections,
				new int[0], new int[0], List.of(), beside, false);
	}

	/** A path laid out block by block, with the terrain under each block. */
	private record Layout(int[] x, int[] z, boolean[] corner, int[] ground, int[] water) {
	}

	private Layout layout(List<Point> path) {
		int length = 1;
		for (int i = 1; i < path.size(); i++) {
			length += distance(path.get(i - 1), path.get(i));
		}
		int[] x = new int[length];
		int[] z = new int[length];
		boolean[] corner = new boolean[length];
		int index = 0;
		x[0] = path.getFirst().x();
		z[0] = path.getFirst().z();
		for (int i = 1; i < path.size(); i++) {
			Point from = path.get(i - 1);
			Point to = path.get(i);
			int stepX = Integer.signum(to.x() - from.x());
			int stepZ = Integer.signum(to.z() - from.z());
			for (int step = 1; step <= distance(from, to); step++) {
				index++;
				x[index] = from.x() + stepX * step;
				z[index] = from.z() + stepZ * step;
			}
			// A point the path goes straight through, like the middle of a station track, is no corner.
			if (i < path.size() - 1 && (Integer.signum(path.get(i + 1).x() - to.x()) != stepX
					|| Integer.signum(path.get(i + 1).z() - to.z()) != stepZ)) {
				corner[index] = true;
			}
		}
		int[] ground = new int[length];
		int[] water = new int[length];
		for (int i = 0; i < length; i++) {
			TerrainSampler.Column column = terrain.sample(x[i], z[i]);
			ground[i] = column.ground();
			water[i] = column.water();
		}
		return new Layout(x, z, corner, ground, water);
	}

	private static int distance(Point a, Point b) {
		return Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
	}
}
