package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lays a line between two points out of straights along the x and z axes.
 *
 * <p>The line leaves and arrives along the axis with the larger distance (the main axis). The distance along the
 * other axis is covered by side steps. {@link #candidates} gives the shapes worth trying, from one long side step
 * anywhere along the way to staircases of a few steps; the planner picks among them by terrain. {@link #route} gives
 * the plain staircase with steps of about 48 blocks.
 */
public final class RouteStepper {
	private static final double PREFERRED_RUN = 48.0;
	/** Distance between the places tried for the side step of a single-step route. */
	private static final int SINGLE_STEP_SPACING = 32;
	private static final int MAX_STEPS = 4;
	private static final int[] STAIRCASE_SHIFTS = {0, 24, -24};

	private RouteStepper() {
	}

	public static List<Point> route(Point from, Point to, PlannerSettings settings) {
		return route(from, to, Math.abs(to.x() - from.x()) >= Math.abs(to.z() - from.z()), settings);
	}

	/** Same as {@link #route(Point, Point, PlannerSettings)} with the main axis given: x when {@code mainIsX}. */
	public static List<Point> route(Point from, Point to, boolean mainIsX, PlannerSettings settings) {
		int main = Math.abs(mainIsX ? to.x() - from.x() : to.z() - from.z());
		int side = Math.abs(mainIsX ? to.z() - from.z() : to.x() - from.x());
		return staircase(from, to, mainIsX, sideSteps(main, side, settings), 0, settings);
	}

	/**
	 * Every route shape worth trying between two points, main axis given: one straight when they line up; one side
	 * step placed anywhere along the way, every {@value #SINGLE_STEP_SPACING} blocks, so the line can run long to a
	 * good spot before it turns; and staircases of two to {@value #MAX_STEPS} steps, slid a little either way. No
	 * straight is shorter than {@code minRun} unless the ends force it.
	 */
	public static List<List<Point>> candidates(Point from, Point to, boolean mainIsX, PlannerSettings settings) {
		int main = mainIsX ? to.x() - from.x() : to.z() - from.z();
		int side = mainIsX ? to.z() - from.z() : to.x() - from.x();
		Set<List<Point>> routes = new LinkedHashSet<>();
		if (side == 0) {
			routes.add(List.of(from, to));
			return new ArrayList<>(routes);
		}
		int length = Math.abs(main);
		int sign = Integer.signum(main);
		int minRun = Math.min(settings.minRun(), length / 2);
		for (int offset = minRun; offset <= length - minRun; offset += SINGLE_STEP_SPACING) {
			routes.add(singleStep(from, to, mainIsX, (mainIsX ? from.x() : from.z()) + sign * offset));
		}
		routes.add(singleStep(from, to, mainIsX, (mainIsX ? from.x() : from.z()) + main / 2));
		for (int steps = 2; steps <= MAX_STEPS; steps++) {
			if (Math.abs(side) / steps < settings.minRun() || length / (steps + 1) < settings.minRun()) {
				break;
			}
			for (int shift : STAIRCASE_SHIFTS) {
				routes.add(staircase(from, to, mainIsX, steps, shift, settings));
			}
		}
		return new ArrayList<>(routes);
	}

	/** Straight along the main axis to {@code turnAt}, one side step, straight on to the end. */
	private static List<Point> singleStep(Point from, Point to, boolean mainIsX, int turnAt) {
		List<Point> corners = new ArrayList<>();
		corners.add(from);
		add(corners, mainIsX ? new Point(turnAt, from.z()) : new Point(from.x(), turnAt));
		add(corners, mainIsX ? new Point(turnAt, to.z()) : new Point(to.x(), turnAt));
		add(corners, to);
		return List.copyOf(corners);
	}

	/**
	 * A staircase of {@code steps} equal side steps between equal main-axis straights, every side step moved
	 * {@code shift} blocks along the main axis (cut down so no main straight gets shorter than {@code minRun}).
	 */
	static List<Point> staircase(Point from, Point to, boolean mainIsX, int steps, int shift, PlannerSettings settings) {
		int main = mainIsX ? to.x() - from.x() : to.z() - from.z();
		int side = mainIsX ? to.z() - from.z() : to.x() - from.x();
		int room = Math.max(0, Math.abs(main) / (steps + 1) - settings.minRun());
		int offset = Integer.signum(main) * Math.clamp(shift, -room, room);

		List<Point> corners = new ArrayList<>();
		corners.add(from);
		int mainStart = mainIsX ? from.x() : from.z();
		int sideStart = mainIsX ? from.z() : from.x();
		int sidePos = sideStart;
		for (int i = 1; i <= steps + 1; i++) {
			int mainPos = mainStart + (int) Math.round((double) main * i / (steps + 1)) + (i <= steps ? offset : 0);
			add(corners, mainIsX ? new Point(mainPos, sidePos) : new Point(sidePos, mainPos));
			if (i <= steps) {
				sidePos = sideStart + (int) Math.round((double) side * i / steps);
				add(corners, mainIsX ? new Point(mainPos, sidePos) : new Point(sidePos, mainPos));
			}
		}
		return List.copyOf(corners);
	}

	/** How many side steps of about 48 blocks cover {@code side} blocks while every straight stays a sensible length. */
	static int sideSteps(int main, int side, PlannerSettings settings) {
		if (side == 0) {
			return 0;
		}
		if (main < side) {
			// The main axis was forced and is the shorter one: one step, the line is short on that axis anyway.
			return 1;
		}
		int steps = Math.max(1, (int) Math.round(side / PREFERRED_RUN));
		steps = Math.max(steps, Math.ceilDiv(side, settings.maxRun()));
		while (steps > 1 && (side / steps < settings.minRun() || main / (steps + 1) < settings.minRun())) {
			steps--;
		}
		return steps;
	}

	private static void add(List<Point> corners, Point point) {
		if (!corners.getLast().equals(point)) {
			corners.add(point);
		}
	}
}
