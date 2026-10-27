package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the one station of a village stands and where its lines meet it.
 *
 * <p>The station is a straight track of {@value #LENGTH} blocks just outside the village, along the middle of one side
 * of its footprint, the station building between it and the village. A line meets it at one of three slots: either
 * end, carrying on along the track, so two lines at the ends run straight through; or the middle, square on from
 * outside, where a switch takes it onto the track.
 *
 * <p>Directions are indices into {@link #DIRECTIONS}: 0 east, 1 south, 2 west, 3 north.
 */
public final class StationLayout {
	static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
	/** Blocks from the middle of the station track to either end. */
	static final int HALF_LENGTH = 12;
	static final int LENGTH = 2 * HALF_LENGTH + 1;

	/** The three places a line meets the station. */
	public enum Slot {
		FIRST_END, SECOND_END, MIDDLE
	}

	/**
	 * A station.
	 *
	 * @param middle the middle block of the track
	 * @param along  the way from the middle to the second end
	 * @param out    the way out of the village, square to the track
	 */
	public record Station(Point middle, int along, int out) {
		public Point end(Slot slot) {
			return switch (slot) {
				case FIRST_END -> step(middle, (along + 2) % 4, HALF_LENGTH);
				case SECOND_END -> step(middle, along, HALF_LENGTH);
				case MIDDLE -> middle;
			};
		}

		/** The way a line leaves the station from a slot. */
		public int leaving(Slot slot) {
			return switch (slot) {
				case FIRST_END -> (along + 2) % 4;
				case SECOND_END -> along;
				case MIDDLE -> out;
			};
		}

		/** The track, end to end, as a path of corners. */
		public List<Point> track() {
			return List.of(end(Slot.FIRST_END), middle, end(Slot.SECOND_END));
		}
	}

	private StationLayout() {
	}

	/**
	 * The stations a village could have, best first: one on each side of its footprint, {@code margin} blocks out,
	 * the sides facing its nearest neighbours first. The middle of a station sits where the way to the nearest
	 * neighbour it faces crosses that side, kept so the whole track lies along the side.
	 *
	 * @param neighbours the centers of the nearest other villages, nearest first
	 */
	public static List<Station> candidates(Village village, List<Point> neighbours, int margin) {
		List<Station> stations = new ArrayList<>();
		List<Double> scores = new ArrayList<>();
		for (int out = 0; out < 4; out++) {
			double score = 0;
			Point toward = null;
			for (int k = 0; k < neighbours.size(); k++) {
				Point neighbour = neighbours.get(k);
				double dx = neighbour.x() - village.center().x();
				double dz = neighbour.z() - village.center().z();
				double distance = Math.max(1, Math.hypot(dx, dz));
				double facing = (dx * DIRECTIONS[out][0] + dz * DIRECTIONS[out][1]) / distance;
				score += facing / (k + 1);
				if (toward == null && facing > Math.sqrt(0.5)) {
					toward = neighbour;
				}
			}
			stations.add(station(village, out, toward, margin));
			scores.add(score);
		}
		List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
		order.sort(Comparator.comparingDouble((Integer out) -> -scores.get(out)).thenComparingInt(out -> out));
		return order.stream().map(stations::get).toList();
	}

	private static Station station(Village village, int out, Point toward, int margin) {
		boolean alongX = DIRECTIONS[out][0] == 0;
		int edge = switch (out) {
			case 0 -> village.maxX() + margin;
			case 1 -> village.maxZ() + margin;
			case 2 -> village.minX() - margin;
			default -> village.minZ() - margin;
		};
		Point center = village.center();
		int side = alongX ? center.x() : center.z();
		if (toward != null) {
			// Where the straight way from the center to that neighbour crosses the station's side.
			double mainDelta = alongX ? toward.z() - center.z() : toward.x() - center.x();
			double sideDelta = alongX ? toward.x() - center.x() : toward.z() - center.z();
			double mainToEdge = edge - (alongX ? center.z() : center.x());
			side += (int) Math.round(sideDelta * mainToEdge / mainDelta);
		}
		int low = (alongX ? village.minX() : village.minZ()) + HALF_LENGTH;
		int high = (alongX ? village.maxX() : village.maxZ()) - HALF_LENGTH;
		side = low <= high ? Math.clamp(side, low, high) : (low + high) / 2;
		Point middle = alongX ? new Point(side, edge) : new Point(edge, side);
		return new Station(middle, alongX ? 0 : 1, out);
	}

	static Point step(Point point, int direction, int blocks) {
		return new Point(point.x() + DIRECTIONS[direction][0] * blocks, point.z() + DIRECTIONS[direction][1] * blocks);
	}

	/** The station a track's corners describe: its middle, the way to its second end, and the way out of the village. */
	public static Station of(List<Point> track, Village village) {
		Point first = track.getFirst();
		Point middle = track.get(1);
		int along = ExitFinder.direction(first, middle);
		int out = (along + 1) % 4;
		Point outside = step(middle, out, 1);
		Point inside = step(middle, (out + 2) % 4, 1);
		if (outside.distanceSq(village.center()) < inside.distanceSq(village.center())) {
			out = (out + 2) % 4;
		}
		return new Station(middle, along, out);
	}
}
