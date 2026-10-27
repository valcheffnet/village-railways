package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The blocks round a village that a way out of its station may not use, worked out once rather than block by block:
 * inside the given boxes, or within two blocks of a track. Near a slot of the station, a track ending there lets the
 * way through, since that is where the way starts.
 */
final class ExitGrid {
	/** A track that ends at a slot lets the way through this close to it, squared. */
	private static final int MEETING_SQ = 8;
	private static final int NONE = -1;
	private static final int SEVERAL = -2;

	private final int[] box;
	private final int width;
	private final boolean[] closed;
	private final int[] track;
	private final List<List<Point>> tracks = new ArrayList<>();

	/**
	 * @param box       minX, minZ, maxX, maxZ of the area
	 * @param closedAreas areas the way may not enter
	 * @param margin    gap kept from them
	 */
	ExitGrid(int[] box, List<Box> closedAreas, int margin) {
		this.box = box;
		this.width = box[2] - box[0] + 1;
		int height = box[3] - box[1] + 1;
		this.closed = new boolean[width * height];
		this.track = new int[width * height];
		Arrays.fill(track, NONE);
		for (Box area : closedAreas) {
			fill(area.minX() - margin, area.minZ() - margin, area.maxX() + margin, area.maxZ() + margin, cell ->
					closed[cell] = true);
		}
	}

	/** Adds a track; the blocks within two blocks of it are closed. */
	void addTrack(List<Point> path) {
		int index = tracks.size();
		tracks.add(path);
		for (int i = 1; i < path.size(); i++) {
			Point p = path.get(i - 1);
			Point q = path.get(i);
			fill(Math.min(p.x(), q.x()) - 1, Math.min(p.z(), q.z()) - 1, Math.max(p.x(), q.x()) + 1,
					Math.max(p.z(), q.z()) + 1,
					cell -> track[cell] = track[cell] == NONE || track[cell] == index ? index : SEVERAL);
		}
	}

	/** The test for a way starting where the tracks ending at {@code stations} end. */
	ExitFinder.Blocked blocked(List<Point> stations) {
		return (x, z) -> {
			if (x < box[0] || z < box[1] || x > box[2] || z > box[3]) {
				return true;
			}
			int cell = (z - box[1]) * width + (x - box[0]);
			if (closed[cell]) {
				return true;
			}
			if (track[cell] == NONE) {
				return false;
			}
			Point point = new Point(x, z);
			if (track[cell] >= 0) {
				return !meets(tracks.get(track[cell]), point, stations);
			}
			for (List<Point> path : tracks) {
				if (closeTo(path, point) && !meets(path, point, stations)) {
					return true;
				}
			}
			return false;
		};
	}

	private static boolean meets(List<Point> path, Point point, List<Point> stations) {
		for (Point station : stations) {
			if (point.distanceSq(station) <= MEETING_SQ
					&& (path.getFirst().equals(station) || path.getLast().equals(station))) {
				return true;
			}
		}
		return false;
	}

	private static boolean closeTo(List<Point> path, Point point) {
		for (int i = 1; i < path.size(); i++) {
			if (NetworkPlanner.gapSq(path.get(i - 1), path.get(i), point, point) < 4) {
				return true;
			}
		}
		return false;
	}

	private void fill(int minX, int minZ, int maxX, int maxZ, IntConsumer action) {
		for (int z = Math.max(minZ, box[1]); z <= Math.min(maxZ, box[3]); z++) {
			for (int x = Math.max(minX, box[0]); x <= Math.min(maxX, box[2]); x++) {
				action.accept((z - box[1]) * width + (x - box[0]));
			}
		}
	}
}
