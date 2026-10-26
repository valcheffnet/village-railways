package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a line between two points out of straights along the x and z axes.
 *
 * <p>The line leaves and arrives along the axis with the larger distance (the main axis). The distance along the
 * other axis is covered by side steps of {@code minRun} to {@code maxRun} blocks, about 48 each, spread evenly between
 * equal main-axis straights. A mostly straight line gets a few long straights; a diagonal turns into a staircase of
 * similar straights in both directions. A side distance shorter than {@code minRun} becomes one short step.
 */
public final class RouteStepper {
	private static final double PREFERRED_RUN = 48.0;

	private RouteStepper() {
	}

	public static List<Point> route(Point from, Point to, PlannerSettings settings) {
		return route(from, to, Math.abs(to.x() - from.x()) >= Math.abs(to.z() - from.z()), settings);
	}

	/** Same as {@link #route(Point, Point, PlannerSettings)} with the main axis given: x when {@code mainIsX}. */
	public static List<Point> route(Point from, Point to, boolean mainIsX, PlannerSettings settings) {
		return route(from, to, mainIsX, 0, settings);
	}

	/**
	 * A route with every side step moved {@code shift} blocks along the main axis, towards the end when positive. The
	 * staircase keeps its shape and slides between the two fixed ends, which is how a line steps around an obstacle.
	 * The shift is cut down so no main-axis straight gets shorter than {@code minRun}.
	 */
	public static List<Point> route(Point from, Point to, boolean mainIsX, int shift, PlannerSettings settings) {
		int dx = to.x() - from.x();
		int dz = to.z() - from.z();
		int main = mainIsX ? dx : dz;
		int side = mainIsX ? dz : dx;
		int steps = sideSteps(Math.abs(main), Math.abs(side), settings);
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

	/** How many side steps cover {@code side} blocks while every straight stays a sensible length. */
	static int sideSteps(int main, int side, PlannerSettings settings) {
		if (side == 0) {
			return 0;
		}
		if (main < side) {
			// The main axis was forced and is the shorter one: one step, the line is short on that axis anyway.
			return 1;
		}
		int steps = Math.max(1, (int) Math.round(side / PREFERRED_RUN));
		steps = Math.max(steps, ceilDiv(side, settings.maxRun()));
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

	private static int ceilDiv(int a, int b) {
		return -Math.floorDiv(-a, b);
	}
}
