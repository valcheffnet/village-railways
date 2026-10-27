package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the way a line leaves its village's station for open land, round the village when the line's other village
 * lies behind it.
 *
 * <p>The way runs along the axes with the fewest turns, and the shortest way among those. Every straight between two
 * turns is at least {@value #MIN_RUN} blocks long.
 *
 * <p>The search goes by turns: from where it stands it runs straight on as far as it can, and every block passed on
 * the way, a long enough straight behind it, is a place to turn from in the next round. Each round covers the area
 * once, so a way with a few turns costs a few passes over the area.
 */
final class ExitFinder {
	static final int MIN_RUN = 3;
	/** Most turns a way out may take. */
	private static final int MAX_TURNS = 8;
	private static final int[][] DIRECTIONS = StationLayout.DIRECTIONS;
	/** The planner thread searches one way after another; their tables are kept rather than made anew each time. */
	private static final ThreadLocal<Table> TABLE = ThreadLocal.withInitial(Table::new);

	/**
	 * What a search knows per cell and way: entered going that way, the fewest turns it was reached with, the length
	 * then, where that straight began and the way of the straight before it; and whether a straight going that way
	 * already set out from it. An entry counts only when stamped with the current search, so the table need not be
	 * cleared between searches.
	 */
	private static final class Table {
		int[] turns = new int[0];
		int[] length = new int[0];
		int[] begun = new int[0];
		int[] previous = new int[0];
		int[] stamp = new int[0];
		int[] setOut = new int[0];
		int search;

		void start(int states) {
			if (turns.length < states) {
				turns = new int[states];
				length = new int[states];
				begun = new int[states];
				previous = new int[states];
				stamp = new int[states];
				setOut = new int[states];
				search = 0;
			}
			search++;
		}

		boolean reached(int state, int byTurns, int byLength) {
			return stamp[state] == search
					&& (turns[state] < byTurns || turns[state] == byTurns && length[state] <= byLength);
		}

		void reach(int state, int byTurns, int byLength, int begunAt, int previousWay) {
			stamp[state] = search;
			turns[state] = byTurns;
			length[state] = byLength;
			begun[state] = begunAt;
			previous[state] = previousWay;
		}

		/** Marks that a straight sets out from a cell going a way; false when one did before. */
		boolean setsOut(int state) {
			if (setOut[state] == search) {
				return false;
			}
			setOut[state] = search;
			return true;
		}
	}

	/** Cells a way out may not use. */
	@FunctionalInterface
	interface Blocked {
		boolean test(int x, int z);
	}

	private ExitFinder() {
	}

	/**
	 * The corners of the way out from {@code start}, a station slot left going {@code dir}, to the first block past
	 * {@code beyond} going {@code exit}: past that line, on the side of the village facing the line's other village,
	 * the line runs on in the open. Null when none fits in {@code box} (minX, minZ, maxX, maxZ).
	 */
	static List<Point> leave(Point start, int dir, int exit, int beyond, int[] box, Blocked blocked, int maxLength) {
		int sign = DIRECTIONS[exit][0] + DIRECTIONS[exit][1];
		boolean alongX = DIRECTIONS[exit][0] != 0;
		List<Point> route = search(start, dir, box, blocked, maxLength,
				(x, z, way, run) -> way == exit && run >= MIN_RUN && sign * ((alongX ? x : z) - beyond) > 0);
		return route == null ? null : corners(route, 1, 1);
	}

	/** Whether stepping onto (x, z) going {@code dir}, the {@code run}th block of a straight, ends the way. */
	@FunctionalInterface
	private interface Goal {
		boolean reached(int x, int z, int dir, int run);
	}

	/**
	 * A straight to run in one round: from {@code cell} going {@code dir}, reached after {@code length} blocks, with
	 * {@code run} straight blocks behind it, coming off a straight going {@code previous} (-1 at the start).
	 */
	private record Straight(int cell, int dir, int length, int run, int previous) {
	}

	/**
	 * The corners of the way from {@code start}, entered going {@code startDir}, to the goal: the start, every turn and
	 * the goal block. Null when none fits within {@value #MAX_TURNS} turns and {@code maxLength} blocks.
	 */
	private static List<Point> search(Point start, int startDir, int[] box, Blocked blocked, int maxLength, Goal goal) {
		if (start.x() < box[0] || start.z() < box[1] || start.x() > box[2] || start.z() > box[3]) {
			return null;
		}
		int width = box[2] - box[0] + 1;
		Table table = TABLE.get();
		table.start(width * (box[3] - box[1] + 1) * 4);
		int first = cell(start.x(), start.z(), box, width);
		table.reach(first * 4 + startDir, 0, 0, first, -1);
		// The way leaves its start straight on: a station's rail there runs the way of the track.
		List<Straight> round = new ArrayList<>(List.of(new Straight(first, startDir, 0, 0, -1)));
		for (int turns = 0; turns <= MAX_TURNS && !round.isEmpty(); turns++) {
			round = byLength(round, maxLength);
			List<Straight> next = new ArrayList<>();
			for (Straight straight : round) {
				int x = box[0] + straight.cell() % width;
				int z = box[1] + straight.cell() / width;
				int dir = straight.dir();
				int length = straight.length();
				int run = straight.run();
				for (int step = 0; ; step++) {
					if (step > 0) {
						x += DIRECTIONS[dir][0];
						z += DIRECTIONS[dir][1];
						length++;
						run++;
						if (goal.reached(x, z, dir, run)) {
							return trace(new Point(x, z), straight, box, width, table);
						}
						if (length > maxLength || !inside(box, x, z) || blocked.test(x, z)) {
							break;
						}
						int state = cell(x, z, box, width) * 4 + dir;
						if (table.reached(state, turns, length)) {
							break;
						}
						table.reach(state, turns, length, straight.cell(), straight.previous());
					}
					if (run >= MIN_RUN) {
						int here = cell(x, z, box, width);
						for (int side : new int[] {(dir + 1) % 4, (dir + 3) % 4}) {
							if (table.setsOut(here * 4 + side)) {
								next.add(new Straight(here, side, length, 0, dir));
							}
						}
					}
				}
			}
			round = next;
		}
		return null;
	}

	/** The corners back from the goal: each straight began at a turn, which the straight before it reached. */
	private static List<Point> trace(Point goal, Straight last, int[] box, int width, Table table) {
		List<Point> corners = new ArrayList<>();
		corners.add(goal);
		int cell = last.cell();
		int previous = last.previous();
		while (true) {
			corners.add(new Point(box[0] + cell % width, box[1] + cell / width));
			if (previous < 0) {
				break;
			}
			int state = cell * 4 + previous;
			cell = table.begun[state];
			previous = table.previous[state];
		}
		return corners.reversed();
	}

	/** The straights of a round, shortest way first, so where two reach a block with as many turns the shorter wins. */
	private static List<Straight> byLength(List<Straight> straights, int maxLength) {
		int[] counts = new int[maxLength + 2];
		for (Straight straight : straights) {
			counts[straight.length() + 1]++;
		}
		for (int length = 1; length < counts.length; length++) {
			counts[length] += counts[length - 1];
		}
		Straight[] sorted = new Straight[straights.size()];
		for (Straight straight : straights) {
			sorted[counts[straight.length()]++] = straight;
		}
		return Arrays.asList(sorted);
	}

	private static boolean inside(int[] box, int x, int z) {
		return x >= box[0] && z >= box[1] && x <= box[2] && z <= box[3];
	}

	private static int cell(int x, int z, int[] box, int width) {
		return (z - box[1]) * width + (x - box[0]);
	}

	/** Keeps the blocks where the way turns, and the first {@code start} and last {@code end} blocks. */
	private static List<Point> corners(List<Point> blocks, int start, int end) {
		// A way that turns at its start begins its first two straights on the same block.
		List<Point> points = new ArrayList<>();
		for (Point point : blocks) {
			if (points.isEmpty() || !points.getLast().equals(point)) {
				points.add(point);
			}
		}
		List<Point> corners = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) {
			if (i < start || i >= points.size() - end
					|| direction(points.get(i - 1), points.get(i)) != direction(points.get(i), points.get(i + 1))) {
				corners.add(points.get(i));
			}
		}
		return corners;
	}

	/** The index of the direction from one block to another along an axis, or -1. */
	static int direction(Point from, Point to) {
		int dx = Integer.signum(to.x() - from.x());
		int dz = Integer.signum(to.z() - from.z());
		for (int dir = 0; dir < 4; dir++) {
			if (DIRECTIONS[dir][0] == dx && DIRECTIONS[dir][1] == dz && (dx == 0) != (dz == 0)) {
				return dir;
			}
		}
		return -1;
	}

}
