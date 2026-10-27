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
	/** Shortest stretch of second rail worth laying. */
	private static final int MIN_SECOND_RAIL = 40;
	/** Level, straight blocks either side of the block where a second rail branches off or joins. */
	private static final int SPLIT_REACH = 3;
	/** Shortest straight of a second rail between two of its corners. */
	private static final int MIN_SECOND_RUN = 3;

	/** Where a second rail may run. */
	@FunctionalInterface
	public interface Area {
		boolean contains(int x, int z);
	}

	private final TerrainSampler terrain;

	public TrackPlanner(ServerLevel level) {
		this.terrain = new TerrainSampler(level);
	}

	/**
	 * The block-by-block plan of a line through the given corners, from a station at {@code startHeight} to one at
	 * {@code endHeight}, or null when no height profile fits it.
	 */
	public TrackPlan plan(List<Point> path, int startHeight, int endHeight) {
		return plan(path, startHeight, endHeight, null);
	}

	/**
	 * The plan of a line, as a double track wherever a second rail fits inside {@code doubled}: on the line's left,
	 * {@value #DOUBLE_TRACK} blocks over, at the line's heights. A second rail branches off the line and joins it
	 * again on level, straight ground. A null area makes a single line.
	 */
	public TrackPlan plan(List<Point> path, int startHeight, int endHeight, Area doubled) {
		Layout layout = layout(path);
		int[] track = HeightProfile.plan(layout.ground(), layout.water(), layout.corner(), startHeight, endHeight,
				doubled == null ? 1 : DOUBLE_CORNER_CLEARANCE);
		if (track == null) {
			return null;
		}
		TrackPlan line = plan(layout, track);
		return doubled == null ? line : line.withSeconds(secondRails(line, doubled));
	}

	/** The plan of a station track: level all along at {@code height}. */
	public TrackPlan planStation(List<Point> path, int height) {
		Layout layout = layout(path);
		int[] track = new int[layout.x().length];
		Arrays.fill(track, height);
		return plan(layout, track);
	}

	/** The rail height of a station standing at a block: on the ground, or a block over water. */
	public int stationHeight(Point at) {
		TerrainSampler.Column column = terrain.sample(at.x(), at.z());
		return column.water() == HeightProfile.DRY ? column.ground() : column.water() + 1;
	}

	private static TrackPlan plan(Layout layout, int[] track) {
		Section[] sections = new Section[track.length];
		for (int i = 0; i < track.length; i++) {
			sections[i] = Section.of(track[i], layout.ground()[i], layout.water()[i]);
		}
		return new TrackPlan(layout.x(), layout.z(), track, layout.ground(), layout.water(), layout.corner(), sections,
				new int[0], new int[0], List.of(), new int[0]);
	}

	private List<TrackPlan> secondRails(TrackPlan line, Area area) {
		boolean[] split = new boolean[line.length()];
		for (int i = 0; i < line.length(); i++) {
			split[i] = maySplit(line, i, area);
		}
		List<TrackPlan> seconds = new ArrayList<>();
		for (int i = 0; i < line.length(); i++) {
			if (!area.contains(line.x()[i], line.z()[i])) {
				continue;
			}
			int last = i;
			while (last + 1 < line.length() && area.contains(line.x()[last + 1], line.z()[last + 1])) {
				last++;
			}
			addSecondRails(line, split, i, last, seconds);
			i = last;
		}
		return List.copyOf(seconds);
	}

	/** Where a second rail may branch off or join: level, straight, open ground, with no corner close by. */
	private static boolean maySplit(TrackPlan line, int i, Area area) {
		if (i - SPLIT_REACH < 1 || i + SPLIT_REACH > line.length() - 2 || !area.contains(line.x()[i], line.z()[i])) {
			return false;
		}
		for (int j = i - SPLIT_REACH; j <= i + SPLIT_REACH; j++) {
			if (line.track()[j] != line.track()[i] || line.corner()[j]) {
				return false;
			}
		}
		for (int j = i - 2; j <= i + 2; j++) {
			Section section = line.sections()[j];
			if (section != Section.FLAT && section != Section.FILL && section != Section.TRENCH) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Second rails beside blocks {@code first} to {@code last} of a line: one that follows the line all the way, or
	 * pieces either side of a corner it cannot go round.
	 */
	private void addSecondRails(TrackPlan line, boolean[] split, int first, int last, List<TrackPlan> seconds) {
		int from = first;
		while (from <= last && !split[from]) {
			from++;
		}
		int to = last;
		while (to >= from && !split[to]) {
			to--;
		}
		if (to - from + 1 < MIN_SECOND_RAIL) {
			return;
		}
		List<Integer> corners = new ArrayList<>();
		for (int i = from + 1; i < to; i++) {
			if (line.corner()[i]) {
				corners.add(i);
			}
		}
		List<Point> path = secondPath(line, from, to, corners);
		int broken = brokenCorner(path, corners);
		if (broken >= 0) {
			addSecondRails(line, split, from, broken - 1, seconds);
			addSecondRails(line, split, broken + 1, to, seconds);
			return;
		}
		TrackPlan second = secondRail(line, path, from, to);
		if (second != null) {
			seconds.add(second);
		}
	}

	/**
	 * The corners of a second rail: a step out to the left at block {@code from}, the line's corners moved
	 * {@value #DOUBLE_TRACK} blocks out from both their straights, and the step back in at block {@code to}.
	 */
	private static List<Point> secondPath(TrackPlan line, int from, int to, List<Integer> corners) {
		List<Point> path = new ArrayList<>();
		Direction out = line.direction(from, from + 1).getCounterClockWise();
		path.add(shifted(line, from, out, 1, null));
		path.add(shifted(line, from, out, DOUBLE_TRACK, null));
		for (int corner : corners) {
			path.add(shifted(line, corner, line.direction(corner - 1, corner).getCounterClockWise(), DOUBLE_TRACK,
					line.direction(corner, corner + 1).getCounterClockWise()));
		}
		Direction in = line.direction(to - 1, to).getCounterClockWise();
		path.add(shifted(line, to, in, DOUBLE_TRACK, null));
		path.add(shifted(line, to, in, 1, null));
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
	private static int brokenCorner(List<Point> path, List<Integer> corners) {
		// The path is the step out, a point per corner and the step in, so straight k runs from point k + 1.
		if (corners.isEmpty()) {
			return -1;
		}
		for (int k = 0; k <= corners.size(); k++) {
			Point a = path.get(k + 1);
			Point b = path.get(k + 2);
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
	private TrackPlan secondRail(TrackPlan line, List<Point> path, int from, int to) {
		Layout layout = layout(path);
		int length = layout.x().length;
		// Where the second rail runs beside each block of the line; at a corner, beside it both ways.
		Map<Long, Integer> besideAt = new HashMap<>();
		for (int i = from; i <= to; i++) {
			Direction ahead = line.direction(i, i + 1).getCounterClockWise();
			besideAt.put(ChunkPos.pack(line.x()[i] + ahead.getStepX() * DOUBLE_TRACK,
					line.z()[i] + ahead.getStepZ() * DOUBLE_TRACK), i);
			Direction back = line.direction(i - 1, i).getCounterClockWise();
			besideAt.putIfAbsent(ChunkPos.pack(line.x()[i] + back.getStepX() * DOUBLE_TRACK,
					line.z()[i] + back.getStepZ() * DOUBLE_TRACK), i);
		}
		int[] beside = new int[length];
		int[] track = new int[length];
		Section[] sections = new Section[length];
		int at = from;
		for (int p = 0; p < length; p++) {
			Integer found = p >= length - 2 ? Integer.valueOf(to) : besideAt.get(ChunkPos.pack(layout.x()[p], layout.z()[p]));
			if (p >= 2 && found != null) {
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
				new int[0], new int[0], List.of(), beside);
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
