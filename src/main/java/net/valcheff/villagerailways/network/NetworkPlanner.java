package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>Nearest-neighbour lines leave groups of villages apart where the gaps are wider than the maximum length. After
 * every batch each such group is joined to its nearest other groups, up to {@code maxGroupLinks} of them, by one trunk
 * line each, which may be as long as {@code maxGroupLength} and follows the same rules otherwise.
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
		STATIONS_TOO_CLOSE,
		THROUGH_VILLAGE,
		TOO_CLOSE_TO_LINE,
		FILTERED
	}

	private static final int LINK_CELL = 256;
	/** Slides of the staircase tried, in order, when the plain one is blocked. */
	private static final int[] ROUTE_SHIFTS = {0, 16, -16, 32, -32, 48, -48};
	/** Slides of one station along its village edge, tried when no staircase gets through. */
	private static final int[] STATION_SHIFTS = {0, 16, -16, 32, -32};
	/** Village pairs tried, closest first, before giving up on joining two groups. */
	private static final int GROUP_LINK_TRIES = 6;
	/** Farthest a village footprint reaches from its center; vanilla villages stay well inside it. */
	private static final int MAX_VILLAGE_REACH = 256;
	/** Size of the cells the explored area is kept in: 16 x 16 chunks, one bit per chunk while partly explored. */
	private static final int EXPLORED_CELL = 256;
	private static final int CHUNKS_PER_CELL = EXPLORED_CELL / 16;
	/** How many blocks of extra length one existing line of a village counts as, when picking where a trunk starts. */
	private static final int TRUNK_LINE_PENALTY = 300;

	private final PlannerSettings settings;
	private final Map<Long, Village> villages = new LinkedHashMap<>();
	private final List<Link> links = new ArrayList<>();
	private final Map<Long, List<Village>> villageGrid = new HashMap<>();
	private final Map<Long, List<Integer>> linkGrid = new HashMap<>();
	private final Map<Long, List<Integer>> linksByVillage = new HashMap<>();
	private final Set<Long> exploredCells = new HashSet<>();
	private final Map<Long, long[]> partlyExplored = new HashMap<>();
	private final Set<VillagePair> deferred = new HashSet<>();
	private final Set<VillagePair> failedTrunks = new HashSet<>();
	private final Map<Rejection, Integer> lastRejections = new EnumMap<>(Rejection.class);

	public NetworkPlanner(PlannerSettings settings) {
		this.settings = settings;
	}

	/** Restores a saved network as it was, without re-checking it. */
	public NetworkPlanner(PlannerSettings settings, Collection<Village> savedVillages, Collection<Link> savedLinks,
			Collection<Long> savedExploredCells, Map<Long, long[]> savedPartlyExplored, Collection<VillagePair> savedDeferred) {
		this(settings);
		savedVillages.forEach(this::addToGrid);
		savedLinks.forEach(this::addLink);
		exploredCells.addAll(savedExploredCells);
		savedPartlyExplored.forEach((key, bits) -> partlyExplored.put(key, bits.clone()));
		deferred.addAll(savedDeferred);
	}

	/** Two villages in a fixed order, so A-B and B-A are the same pair. */
	public record VillagePair(long first, long second) {
		static VillagePair of(long a, long b) {
			return new VillagePair(Math.min(a, b), Math.max(a, b));
		}
	}

	public synchronized List<Long> exploredCells() {
		return List.copyOf(exploredCells);
	}

	/** Cells explored in part, with one bit per chunk that is. */
	public synchronized Map<Long, long[]> partlyExploredCells() {
		Map<Long, long[]> copy = new HashMap<>();
		partlyExplored.forEach((key, bits) -> copy.put(key, bits.clone()));
		return copy;
	}

	public synchronized List<VillagePair> deferredPairs() {
		return List.copyOf(deferred);
	}

	/**
	 * Records that every village whose start lies in the given block area is known. A line is only planned once the
	 * whole area a village could reach into around it is explored, so no village found later can sit on it. Only
	 * chunks lying fully inside the area count.
	 */
	public synchronized void markExplored(int minX, int minZ, int maxX, int maxZ) {
		int minChunkX = Math.ceilDiv(minX, 16);
		int minChunkZ = Math.ceilDiv(minZ, 16);
		int maxChunkX = Math.floorDiv(maxX + 1, 16) - 1;
		int maxChunkZ = Math.floorDiv(maxZ + 1, 16) - 1;
		for (int cx = Math.floorDiv(minChunkX, CHUNKS_PER_CELL); cx <= Math.floorDiv(maxChunkX, CHUNKS_PER_CELL); cx++) {
			for (int cz = Math.floorDiv(minChunkZ, CHUNKS_PER_CELL); cz <= Math.floorDiv(maxChunkZ, CHUNKS_PER_CELL); cz++) {
				long key = cellKey(cx, cz);
				if (exploredCells.contains(key)) {
					continue;
				}
				long[] bits = partlyExplored.computeIfAbsent(key, k -> new long[CHUNKS_PER_CELL * CHUNKS_PER_CELL / 64]);
				for (int chunkX = Math.max(minChunkX, cx * CHUNKS_PER_CELL);
						chunkX <= Math.min(maxChunkX, cx * CHUNKS_PER_CELL + CHUNKS_PER_CELL - 1); chunkX++) {
					for (int chunkZ = Math.max(minChunkZ, cz * CHUNKS_PER_CELL);
							chunkZ <= Math.min(maxChunkZ, cz * CHUNKS_PER_CELL + CHUNKS_PER_CELL - 1); chunkZ++) {
						int bit = (chunkX - cx * CHUNKS_PER_CELL) * CHUNKS_PER_CELL + (chunkZ - cz * CHUNKS_PER_CELL);
						bits[bit >> 6] |= 1L << (bit & 63);
					}
				}
				if (Arrays.stream(bits).allMatch(word -> word == -1L)) {
					partlyExplored.remove(key);
					exploredCells.add(key);
				}
			}
		}
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
	 * Adds the villages not seen before and plans the lines they make possible, together with lines that waited for
	 * their surroundings to be explored. Call {@link #markExplored} for the area the batch comes from first.
	 *
	 * @return the new lines, in the order they were planned
	 */
	public synchronized List<Link> addVillages(Collection<Village> batch, PathFilter filter) {
		Set<Long> freshIds = new HashSet<>();
		for (Village village : batch) {
			if (!villages.containsKey(village.id())) {
				addToGrid(village);
				freshIds.add(village.id());
			}
		}
		// Pairs of two old villages were already turned down, and every reason for that only gets stronger; only the
		// ones that waited for exploring come back.
		long maxLengthSq = (long) settings.maxLength() * settings.maxLength();
		Set<VillagePair> pairs = new HashSet<>(deferred);
		for (long id : freshIds) {
			Village a = villages.get(id);
			for (Village b : villagesNear(a.center(), settings.maxLength())) {
				if (a.id() != b.id() && a.center().distanceSq(b.center()) <= maxLengthSq) {
					pairs.add(VillagePair.of(a.id(), b.id()));
				}
			}
		}
		List<VillagePair> ordered = new ArrayList<>(pairs);
		ordered.sort(Comparator.comparingLong(this::lengthSq)
				.thenComparingLong(VillagePair::first)
				.thenComparingLong(VillagePair::second));

		lastRejections.clear();
		List<Link> added = new ArrayList<>();
		for (VillagePair pair : ordered) {
			Village a = villages.get(pair.first());
			Village b = villages.get(pair.second());
			if (!isExplored(a, b)) {
				deferred.add(pair);
				continue;
			}
			deferred.remove(pair);
			Link link = tryLink(a, b, filter, false);
			if (link != null) {
				addLink(link);
				added.add(link);
			}
		}
		added.addAll(linkGroups(filter));
		return added;
	}

	/**
	 * Joins groups of villages with trunk lines. Groups are the villages the ordinary lines connect; each group gets at
	 * most {@code maxGroupLinks} trunk lines and at most one to any other group, nearest groups first. A trunk starts
	 * from the villages of each group closest to the other group, preferring ones with fewer lines: every line a village
	 * already has counts as {@value #TRUNK_LINE_PENALTY} blocks of extra distance. Village pairs that failed once are
	 * not tried again; pairs whose surroundings are not explored yet wait.
	 */
	private List<Link> linkGroups(PathFilter filter) {
		Map<Long, Long> groupOf = localGroups();
		Map<Long, Integer> trunkCount = new HashMap<>();
		Set<GroupPair> joinedGroups = new HashSet<>();
		for (Link link : links) {
			if (link.trunk()) {
				long groupA = groupOf.get(link.from());
				long groupB = groupOf.get(link.to());
				trunkCount.merge(groupA, 1, Integer::sum);
				trunkCount.merge(groupB, 1, Integer::sum);
				joinedGroups.add(GroupPair.of(groupA, groupB));
			}
		}

		long maxLengthSq = (long) settings.maxGroupLength() * settings.maxGroupLength();
		Map<GroupPair, List<VillagePair>> candidates = new HashMap<>();
		for (Village a : villages.values()) {
			long groupA = groupOf.get(a.id());
			if (linksOf(a.id()).size() >= settings.maxLinks()
					|| trunkCount.getOrDefault(groupA, 0) >= settings.maxGroupLinks()) {
				continue;
			}
			for (Village b : villagesNear(a.center(), settings.maxGroupLength())) {
				long groupB = groupOf.get(b.id());
				VillagePair pair = VillagePair.of(a.id(), b.id());
				if (groupA >= groupB || linksOf(b.id()).size() >= settings.maxLinks()
						|| a.center().distanceSq(b.center()) > maxLengthSq || failedTrunks.contains(pair)
						|| joinedGroups.contains(GroupPair.of(groupA, groupB))) {
					continue;
				}
				candidates.computeIfAbsent(GroupPair.of(groupA, groupB), key -> new ArrayList<>()).add(pair);
			}
		}
		Comparator<VillagePair> byScore = Comparator.comparingDouble(this::trunkScore)
				.thenComparingLong(VillagePair::first)
				.thenComparingLong(VillagePair::second);
		candidates.values().forEach(list -> list.sort(byScore));
		List<GroupPair> groupPairs = new ArrayList<>(candidates.keySet());
		groupPairs.sort(Comparator.comparing((GroupPair groups) -> candidates.get(groups).getFirst(), byScore));

		List<Link> added = new ArrayList<>();
		for (GroupPair groups : groupPairs) {
			int tries = 0;
			for (VillagePair pair : candidates.get(groups)) {
				if (trunkCount.getOrDefault(groups.first(), 0) >= settings.maxGroupLinks()
						|| trunkCount.getOrDefault(groups.second(), 0) >= settings.maxGroupLinks()
						|| tries >= GROUP_LINK_TRIES) {
					break;
				}
				Village a = villages.get(pair.first());
				Village b = villages.get(pair.second());
				if (!isExplored(a, b)) {
					continue;
				}
				tries++;
				Link link = tryLink(a, b, filter, true);
				if (link == null) {
					failedTrunks.add(pair);
					continue;
				}
				addLink(link);
				added.add(link);
				trunkCount.merge(groups.first(), 1, Integer::sum);
				trunkCount.merge(groups.second(), 1, Integer::sum);
				break;
			}
		}
		return added;
	}

	/** Distance of a trunk candidate, plus the penalty for the lines its villages already have. */
	private double trunkScore(VillagePair pair) {
		int lines = linksOf(pair.first()).size() + linksOf(pair.second()).size();
		return Math.sqrt(lengthSq(pair)) + TRUNK_LINE_PENALTY * lines;
	}

	private long lengthSq(VillagePair pair) {
		return villages.get(pair.first()).center().distanceSq(villages.get(pair.second()).center());
	}

	/** Whether every village that could reach the area between two villages is already known. */
	private boolean isExplored(Village a, Village b) {
		int reach = MAX_VILLAGE_REACH + settings.villageMargin();
		int minX = Math.min(a.minX(), b.minX()) - reach;
		int minZ = Math.min(a.minZ(), b.minZ()) - reach;
		int maxX = Math.max(a.maxX(), b.maxX()) + reach;
		int maxZ = Math.max(a.maxZ(), b.maxZ()) + reach;
		for (int cx = Math.floorDiv(minX, EXPLORED_CELL); cx <= Math.floorDiv(maxX, EXPLORED_CELL); cx++) {
			for (int cz = Math.floorDiv(minZ, EXPLORED_CELL); cz <= Math.floorDiv(maxZ, EXPLORED_CELL); cz++) {
				if (!exploredCells.contains(cellKey(cx, cz))) {
					return false;
				}
			}
		}
		return true;
	}

	/** Group of every village, joined by ordinary lines only: the id of one village of the group. */
	private Map<Long, Long> localGroups() {
		Map<Long, Long> parent = new HashMap<>();
		villages.keySet().forEach(id -> parent.put(id, id));
		for (Link link : links) {
			if (!link.trunk()) {
				long rootA = root(parent, link.from());
				long rootB = root(parent, link.to());
				if (rootA != rootB) {
					parent.put(Math.max(rootA, rootB), Math.min(rootA, rootB));
				}
			}
		}
		Map<Long, Long> groupOf = new HashMap<>();
		villages.keySet().forEach(id -> groupOf.put(id, root(parent, id)));
		return groupOf;
	}

	private static long root(Map<Long, Long> parent, long id) {
		long root = id;
		while (parent.get(root) != root) {
			root = parent.get(root);
		}
		parent.put(id, root);
		return root;
	}

	/** Two groups in a fixed order, so A-B and B-A are the same pair. */
	private record GroupPair(long first, long second) {
		static GroupPair of(long groupA, long groupB) {
			return new GroupPair(Math.min(groupA, groupB), Math.max(groupA, groupB));
		}
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

	private Link tryLink(Village a, Village b, PathFilter filter, boolean trunk) {
		if (linksOf(a.id()).size() >= settings.maxLinks() || linksOf(b.id()).size() >= settings.maxLinks()
				|| linked(a.id(), b.id())) {
			return reject(Rejection.NO_FREE_SLOT);
		}
		// The line runs along the axis its stations face, even when the stations alone would suggest the other one.
		boolean mainIsX = Math.abs(b.center().x() - a.center().x()) >= Math.abs(b.center().z() - a.center().z());
		Point[] aligned = align(a, station(a, b.center()), b, station(b, a.center()), mainIsX);
		Rejection firstReason = null;
		// Move station A first, then station B; one at a time keeps the number of tries small.
		for (int end = 0; end < 2; end++) {
			for (int stationShift : STATION_SHIFTS) {
				if (end == 1 && stationShift == 0) {
					continue;
				}
				Point stationA = end == 0 ? slide(a, aligned[0], stationShift, mainIsX) : aligned[0];
				Point stationB = end == 1 ? slide(b, aligned[1], stationShift, mainIsX) : aligned[1];
				Rejection reason;
				if (!angleIsFree(a, stationA) || !angleIsFree(b, stationB)) {
					reason = Rejection.SHARP_ANGLE;
				} else if (stationIsCrowded(a, stationA) || stationIsCrowded(b, stationB)) {
					reason = Rejection.STATIONS_TOO_CLOSE;
				} else {
					PathResult result = findPath(a, stationA, b, stationB, mainIsX, filter);
					if (result.path() != null) {
						return new Link(a.id(), b.id(), result.path(), Link.Status.PLANNED, trunk);
					}
					reason = result.reason();
				}
				if (firstReason == null) {
					firstReason = reason;
				}
			}
		}
		return reject(firstReason);
	}

	/** A path that passed every check, or the reason the first try failed. */
	private record PathResult(List<Point> path, Rejection reason) {
	}

	/** The first staircase slide between two stations that passes every check. */
	private PathResult findPath(Village a, Point stationA, Village b, Point stationB, boolean mainIsX, PathFilter filter) {
		Rejection firstReason = null;
		List<Point> previous = null;
		for (int shift : ROUTE_SHIFTS) {
			List<Point> path = RouteStepper.route(stationA, stationB, mainIsX, shift, settings);
			if (path.equals(previous)) {
				continue;
			}
			previous = path;
			Rejection reason = passesThroughVillage(path, a, b) ? Rejection.THROUGH_VILLAGE
					: comesTooClose(path, a, b) ? Rejection.TOO_CLOSE_TO_LINE
					: !filter.accepts(path) ? Rejection.FILTERED
					: null;
			if (reason == null) {
				return new PathResult(path, null);
			}
			if (firstReason == null) {
				firstReason = reason;
			}
		}
		return new PathResult(null, firstReason);
	}

	/** A station moved {@code shift} blocks along its village edge, kept within the footprint's side. */
	private static Point slide(Village village, Point station, int shift, boolean mainIsX) {
		if (shift == 0) {
			return station;
		}
		return mainIsX ? new Point(station.x(), Math.clamp((long) station.z() + shift, village.minZ(), village.maxZ()))
				: new Point(Math.clamp((long) station.x() + shift, village.minX(), village.maxX()), station.z());
	}

	/**
	 * Slides one station along its village edge so both stations line up when they are only a short side step apart:
	 * a straight line beats a jog of a few blocks. Stations stay within their village's side of the footprint.
	 */
	private Point[] align(Village a, Point stationA, Village b, Point stationB, boolean mainIsX) {
		int sideA = mainIsX ? stationA.z() : stationA.x();
		int sideB = mainIsX ? stationB.z() : stationB.x();
		int gap = Math.abs(sideB - sideA);
		if (gap == 0 || gap >= settings.minRun()) {
			return new Point[] {stationA, stationB};
		}
		int minB = mainIsX ? b.minZ() : b.minX();
		int maxB = mainIsX ? b.maxZ() : b.maxX();
		if (sideA >= minB && sideA <= maxB) {
			return new Point[] {stationA, mainIsX ? new Point(stationB.x(), sideA) : new Point(sideA, stationB.z())};
		}
		int minA = mainIsX ? a.minZ() : a.minX();
		int maxA = mainIsX ? a.maxZ() : a.maxX();
		if (sideB >= minA && sideB <= maxA) {
			return new Point[] {mainIsX ? new Point(stationA.x(), sideB) : new Point(sideB, stationA.z()), stationB};
		}
		return new Point[] {stationA, stationB};
	}

	private boolean stationIsCrowded(Village village, Point station) {
		long spacingSq = (long) settings.stationSpacing() * settings.stationSpacing();
		for (Link link : linksOf(village.id())) {
			Point otherStation = link.from() == village.id() ? link.path().getFirst() : link.path().getLast();
			if (otherStation.distanceSq(station) < spacingSq) {
				return true;
			}
		}
		return false;
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
