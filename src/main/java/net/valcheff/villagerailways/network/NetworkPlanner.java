package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Decides which villages get a railway line between them.
 *
 * <p>Villages arrive in batches as the world is explored. Each batch is joined to the network greedily, shortest
 * line first, so a village links to its nearest neighbours and, when those already have their lines, to farther
 * ones within the maximum length. A line is only added when both villages have a free slot, it leaves each village
 * at a fair angle from that village's other lines, its stepped path keeps clear of every other line, it does not
 * pass through a village it does not serve, and the path filter accepts it (no open ocean, for example). Lines are
 * never removed, so the network only grows. The result is planar: no two lines ever cross.
 *
 * <p>Villages and line segments are kept in grids, so the work for a new village depends on its surroundings only,
 * not on how much of the world is already planned.
 */
public class NetworkPlanner {
	/** Accepts or rejects the path of a new line, for checks that need the world. */
	@FunctionalInterface
	public interface PathFilter {
		boolean accepts(List<Point> path);
	}

	/** Why a possible line was not planned. */
	public enum Rejection {
		NO_FREE_SLOT,
		SHARP_ANGLE,
		THROUGH_VILLAGE,
		TOO_CLOSE_TO_LINE,
		FILTERED
	}

	private static final int LINK_CELL = 256;
	/** Farthest a village footprint reaches from its center; vanilla villages stay well inside it. */
	private static final int MAX_VILLAGE_REACH = 256;

	private final PlannerSettings settings;
	private final Map<Long, Village> villages = new LinkedHashMap<>();
	private final List<Link> links = new ArrayList<>();
	private final Map<Long, List<Village>> villageGrid = new HashMap<>();
	private final Map<Long, List<Integer>> linkGrid = new HashMap<>();
	private final Map<Long, List<Integer>> linksByVillage = new HashMap<>();
	private final Map<Rejection, Integer> lastRejections = new EnumMap<>(Rejection.class);

	public NetworkPlanner(PlannerSettings settings) {
		this.settings = settings;
	}

	/** Restores a saved network as it was, without re-checking it. */
	public NetworkPlanner(PlannerSettings settings, Collection<Village> savedVillages, Collection<Link> savedLinks) {
		this(settings);
		savedVillages.forEach(this::addToGrid);
		savedLinks.forEach(this::addLink);
	}

	public synchronized List<Village> villages() {
		return List.copyOf(villages.values());
	}

	public synchronized List<Link> links() {
		return List.copyOf(links);
	}

	public synchronized boolean knows(long villageId) {
		return villages.containsKey(villageId);
	}

	/**
	 * Adds the villages not seen before and plans the lines they make possible.
	 *
	 * @return the new lines, in the order they were planned
	 */
	public synchronized List<Link> addVillages(Collection<Village> batch, PathFilter filter) {
		List<Village> fresh = new ArrayList<>();
		Set<Long> freshIds = new HashSet<>();
		for (Village village : batch) {
			if (!villages.containsKey(village.id())) {
				addToGrid(village);
				fresh.add(village);
				freshIds.add(village.id());
			}
		}
		// Pairs of two old villages were already turned down, and every reason for that only gets stronger.
		long maxLengthSq = (long) settings.maxLength() * settings.maxLength();
		List<Village[]> pairs = new ArrayList<>();
		for (Village a : fresh) {
			for (Village b : villagesNear(a.center(), settings.maxLength())) {
				if (a.id() == b.id() || (freshIds.contains(b.id()) && a.id() > b.id())) {
					continue;
				}
				if (a.center().distanceSq(b.center()) <= maxLengthSq) {
					pairs.add(a.id() < b.id() ? new Village[] {a, b} : new Village[] {b, a});
				}
			}
		}
		pairs.sort(Comparator.<Village[]>comparingLong(pair -> pair[0].center().distanceSq(pair[1].center()))
				.thenComparingLong(pair -> pair[0].id())
				.thenComparingLong(pair -> pair[1].id()));

		lastRejections.clear();
		List<Link> added = new ArrayList<>();
		for (Village[] pair : pairs) {
			Link link = tryLink(pair[0], pair[1], filter);
			if (link != null) {
				addLink(link);
				added.add(link);
			}
		}
		return added;
	}

	/** How many possible lines the last {@link #addVillages} call turned down, by reason. */
	public synchronized Map<Rejection, Integer> lastRejections() {
		return Map.copyOf(lastRejections);
	}

	/** Marks the line between two villages as built. */
	public synchronized void markBuilt(Link link) {
		int index = links.indexOf(link);
		if (index >= 0) {
			links.set(index, link.withStatus(Link.Status.BUILT));
		}
	}

	private Link tryLink(Village a, Village b, PathFilter filter) {
		if (linksOf(a.id()).size() >= settings.maxLinks() || linksOf(b.id()).size() >= settings.maxLinks()
				|| linked(a.id(), b.id())) {
			return reject(Rejection.NO_FREE_SLOT);
		}
		Point stationA = station(a, b.center());
		Point stationB = station(b, a.center());
		if (!angleIsFree(a, stationA) || !angleIsFree(b, stationB)) {
			return reject(Rejection.SHARP_ANGLE);
		}
		// The line runs along the axis its stations face, even when the stations alone would suggest the other one.
		boolean mainIsX = Math.abs(b.center().x() - a.center().x()) >= Math.abs(b.center().z() - a.center().z());
		List<Point> path = RouteStepper.route(stationA, stationB, mainIsX, settings);
		if (passesThroughVillage(path, a, b)) {
			return reject(Rejection.THROUGH_VILLAGE);
		}
		if (comesTooClose(path, a, b)) {
			return reject(Rejection.TOO_CLOSE_TO_LINE);
		}
		if (!filter.accepts(path)) {
			return reject(Rejection.FILTERED);
		}
		return new Link(a.id(), b.id(), path, Link.Status.PLANNED);
	}

	/**
	 * Where the station of {@code village} goes for a line towards {@code target}: just outside the footprint, on the
	 * side the line leaves from, as close to the straight line between the two centers as the footprint allows.
	 */
	Point station(Village village, Point target) {
		Point center = village.center();
		int dx = target.x() - center.x();
		int dz = target.z() - center.z();
		int margin = settings.stationMargin();
		if (Math.abs(dx) >= Math.abs(dz)) {
			int edgeX = dx >= 0 ? village.maxX() + margin : village.minX() - margin;
			int z = center.z() + (int) Math.round((double) dz * (edgeX - center.x()) / dx);
			return new Point(edgeX, Math.clamp(z, village.minZ(), village.maxZ()));
		}
		int edgeZ = dz >= 0 ? village.maxZ() + margin : village.minZ() - margin;
		int x = center.x() + (int) Math.round((double) dx * (edgeZ - center.z()) / dz);
		return new Point(Math.clamp(x, village.minX(), village.maxX()), edgeZ);
	}

	private Link reject(Rejection reason) {
		lastRejections.merge(reason, 1, Integer::sum);
		return null;
	}

	private boolean angleIsFree(Village village, Point station) {
		double angle = direction(village.center(), station);
		for (Link link : linksOf(village.id())) {
			Point otherStation = link.from() == village.id() ? link.path().getFirst() : link.path().getLast();
			double difference = Math.abs(angle - direction(village.center(), otherStation)) % 360.0;
			if (Math.min(difference, 360.0 - difference) < settings.minAngle()) {
				return false;
			}
		}
		return true;
	}

	private boolean passesThroughVillage(List<Point> path, Village a, Village b) {
		int[] box = bounds(path);
		int reach = MAX_VILLAGE_REACH + settings.villageMargin();
		for (Village village : villagesIn(box[0] - reach, box[1] - reach, box[2] + reach, box[3] + reach)) {
			int margin = village.id() == a.id() || village.id() == b.id() ? 0 : settings.villageMargin();
			for (int i = 1; i < path.size(); i++) {
				if (village.touches(path.get(i - 1), path.get(i), margin)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean comesTooClose(List<Point> path, Village a, Village b) {
		int[] box = bounds(path);
		int clearance = settings.clearance();
		int nearShared = settings.stationMargin() + settings.maxRun();
		for (int index : linksIn(box[0] - clearance, box[1] - clearance, box[2] + clearance, box[3] + clearance)) {
			Link link = links.get(index);
			Village shared = link.connects(a.id()) ? a : link.connects(b.id()) ? b : null;
			for (int i = 1; i < path.size(); i++) {
				Point p1 = path.get(i - 1);
				Point p2 = path.get(i);
				for (int j = 1; j < link.path().size(); j++) {
					Point q1 = link.path().get(j - 1);
					Point q2 = link.path().get(j);
					long gapSq = gapSq(p1, p2, q1, q2);
					if (gapSq == 0) {
						return true;
					}
					// Two lines of the same village start near each other; only their crossing counts there.
					if (shared != null && shared.touches(p1, p2, nearShared) && shared.touches(q1, q2, nearShared)) {
						continue;
					}
					if (gapSq < (long) clearance * clearance) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean linked(long a, long b) {
		return linksOf(a).stream().anyMatch(link -> link.other(a) == b);
	}

	private List<Link> linksOf(long village) {
		return linksByVillage.getOrDefault(village, List.of()).stream().map(links::get).toList();
	}

	private void addToGrid(Village village) {
		villages.put(village.id(), village);
		int cell = settings.maxLength();
		villageGrid.computeIfAbsent(cellKey(village.center().x(), village.center().z(), cell), key -> new ArrayList<>())
				.add(village);
	}

	private void addLink(Link link) {
		int index = links.size();
		links.add(link);
		linksByVillage.computeIfAbsent(link.from(), key -> new ArrayList<>()).add(index);
		linksByVillage.computeIfAbsent(link.to(), key -> new ArrayList<>()).add(index);
		Set<Long> cells = new HashSet<>();
		List<Point> path = link.path();
		for (int i = 1; i < path.size(); i++) {
			Point p = path.get(i - 1);
			Point q = path.get(i);
			forEachCell(Math.min(p.x(), q.x()), Math.min(p.z(), q.z()), Math.max(p.x(), q.x()), Math.max(p.z(), q.z()),
					LINK_CELL, cells::add);
		}
		cells.forEach(key -> linkGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(index));
	}

	private List<Village> villagesNear(Point center, int radius) {
		return villagesIn(center.x() - radius, center.z() - radius, center.x() + radius, center.z() + radius);
	}

	private List<Village> villagesIn(int minX, int minZ, int maxX, int maxZ) {
		List<Village> found = new ArrayList<>();
		forEachCell(minX, minZ, maxX, maxZ, settings.maxLength(),
				key -> found.addAll(villageGrid.getOrDefault(key, List.of())));
		return found;
	}

	private Set<Integer> linksIn(int minX, int minZ, int maxX, int maxZ) {
		Set<Integer> found = new LinkedHashSet<>();
		forEachCell(minX, minZ, maxX, maxZ, LINK_CELL, key -> found.addAll(linkGrid.getOrDefault(key, List.of())));
		return found;
	}

	private static void forEachCell(int minX, int minZ, int maxX, int maxZ, int cell, LongConsumer action) {
		for (int cx = Math.floorDiv(minX, cell); cx <= Math.floorDiv(maxX, cell); cx++) {
			for (int cz = Math.floorDiv(minZ, cell); cz <= Math.floorDiv(maxZ, cell); cz++) {
				action.accept(cellKey(cx, cz));
			}
		}
	}

	private static long cellKey(int x, int z, int cell) {
		return cellKey(Math.floorDiv(x, cell), Math.floorDiv(z, cell));
	}

	private static long cellKey(int cellX, int cellZ) {
		return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
	}

	/** Bounding box of a path as minX, minZ, maxX, maxZ. */
	private static int[] bounds(List<Point> path) {
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Point point : path) {
			box[0] = Math.min(box[0], point.x());
			box[1] = Math.min(box[1], point.z());
			box[2] = Math.max(box[2], point.x());
			box[3] = Math.max(box[3], point.z());
		}
		return box;
	}

	/** Squared distance between two axis-aligned segments. */
	static long gapSq(Point p1, Point p2, Point q1, Point q2) {
		long gapX = Math.max(0, Math.max(Math.min(p1.x(), p2.x()), Math.min(q1.x(), q2.x()))
				- Math.min(Math.max(p1.x(), p2.x()), Math.max(q1.x(), q2.x())));
		long gapZ = Math.max(0, Math.max(Math.min(p1.z(), p2.z()), Math.min(q1.z(), q2.z()))
				- Math.min(Math.max(p1.z(), p2.z()), Math.max(q1.z(), q2.z())));
		return gapX * gapX + gapZ * gapZ;
	}

	private static double direction(Point from, Point to) {
		return Math.toDegrees(Math.atan2(to.z() - from.z(), to.x() - from.x()));
	}
}
