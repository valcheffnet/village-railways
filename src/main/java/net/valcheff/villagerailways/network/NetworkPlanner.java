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
import java.util.function.Predicate;
import java.util.function.LongConsumer;

/**
 * Decides which villages get a railway line between them.
 *
 * <p>Villages arrive in batches as the world is explored. Each batch is joined to the network greedily, shortest
 * line first, so a village links to its nearest neighbours and, when those already have their lines, to farther
 * ones within the maximum length. A line is only added when both villages have a free slot at their station, its
 * stepped path keeps clear of every other line and out of every village, and the path filter accepts it (no open
 * ocean, for example). Lines are never removed, so the network only grows. The result is planar: no two lines ever
 * cross.
 *
 * <p>Nearest-neighbour lines leave groups of villages apart where the gaps are wider than the maximum length. After
 * every batch each such group is joined to its nearest other groups, up to {@code maxGroupLinks} of them, by one trunk
 * line each, which may be as long as {@code maxGroupLength} and follows the same rules otherwise.
 *
 * <p>Every village has one station, just outside it on the side facing its nearest neighbours, where all its lines
 * meet; see {@link StationLayout}. A line leaves the station from a free slot, round the village when its other
 * village lies behind it ({@link ExitFinder}), and runs on in the open.
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

	/** How bad a route is, lower is better; the planner tries the routes for a line in this order. */
	@FunctionalInterface
	public interface RouteScorer {
		double score(List<Point> path);
	}

	/** Without terrain to go by: fewer corners first. */
	public static final RouteScorer FEWEST_CORNERS = path -> path.size();

	/** Why a possible line was not planned. */
	public enum Rejection {
		NO_FREE_SLOT,
		NO_STATION,
		NO_WAY_OUT,
		THROUGH_VILLAGE,
		TOO_CLOSE_TO_LINE,
		FILTERED
	}

	private static final int LINK_CELL = 256;
	/**
	 * Closest two lines may come anywhere, as two tracks side by side: their corridors share the strip between them.
	 */
	private static final int DOUBLE_TRACK = 2;
	/** How far out of its village's footprint a line leaves the village before it heads off. */
	private static final int EXIT_MARGIN = 24;
	/** Longest way out of a village. */
	private static final int MAX_EXIT_LENGTH = 800;
	/** Gap a line keeps from the footprints of the villages it serves, away from their stations. */
	private static final int SERVED_MARGIN = 4;
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
	/** The station tracks of the villages; {@link Link#from} is the village. */
	private final List<Link> stations = new ArrayList<>();
	private final Map<Long, List<Integer>> stationGrid = new HashMap<>();
	private final Map<Long, Integer> stationByVillage = new HashMap<>();
	/** Ways out of villages already found, by village, slot and side, until the village gets another line. */
	private final Map<ExitKey, List<Point>> exits = new HashMap<>();
	private final Map<Long, List<Village>> villageGrid = new HashMap<>();
	private final Map<Long, List<Integer>> linkGrid = new HashMap<>();
	private final Map<Long, List<Integer>> linksByVillage = new HashMap<>();
	private final Set<Long> exploredCells = new HashSet<>();
	private final Map<Long, long[]> partlyExplored = new HashMap<>();
	private final Set<VillagePair> deferred = new HashSet<>();
	private final Set<VillagePair> failedTrunks = new HashSet<>();
	private final Map<Rejection, Integer> lastRejections = new EnumMap<>(Rejection.class);
	/** Whether nothing of a line or station planned before is built yet, so it may still change. */
	private Predicate<Link> unbuilt = link -> true;
	/** Villages whose station a trunk line is trying out as a western one: its house closes the ground beside it. */
	private final Set<Long> tryingWestern = new HashSet<>();
	private RouteScorer scorer = FEWEST_CORNERS;

	public NetworkPlanner(PlannerSettings settings) {
		this.settings = settings;
	}

	/** Sets how routes are ranked; the default only counts corners. */
	public synchronized void setRouteScorer(RouteScorer scorer) {
		this.scorer = scorer;
	}

	/** Restores a saved network as it was, without re-checking it. */
	public NetworkPlanner(PlannerSettings settings, Collection<Village> savedVillages, Collection<Link> savedLinks,
			Collection<Link> savedStations, Collection<Long> savedExploredCells, Map<Long, long[]> savedPartlyExplored,
			Collection<VillagePair> savedDeferred) {
		this(settings);
		savedVillages.forEach(this::addToGrid);
		savedLinks.forEach(this::addLink);
		savedStations.forEach(this::addStation);
		exploredCells.addAll(savedExploredCells);
		savedPartlyExplored.forEach((key, bits) -> partlyExplored.put(key, bits.clone()));
		deferred.addAll(savedDeferred);
	}

	/** Two villages in a fixed order, so A-B and B-A are the same pair. */
	public record VillagePair(long first, long second) {
		public static VillagePair of(long a, long b) {
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

	/** Whether every chunk of the given block area is explored. */
	public synchronized boolean isAreaExplored(int minX, int minZ, int maxX, int maxZ) {
		for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
				long cell = cellKey(Math.floorDiv(chunkX, CHUNKS_PER_CELL), Math.floorDiv(chunkZ, CHUNKS_PER_CELL));
				if (exploredCells.contains(cell)) {
					continue;
				}
				long[] bits = partlyExplored.get(cell);
				int bit = Math.floorMod(chunkX, CHUNKS_PER_CELL) * CHUNKS_PER_CELL + Math.floorMod(chunkZ, CHUNKS_PER_CELL);
				if (bits == null || (bits[bit >> 6] & (1L << (bit & 63))) == 0) {
					return false;
				}
			}
		}
		return true;
	}

	public synchronized List<Village> villages() {
		return List.copyOf(villages.values());
	}

	public synchronized List<Link> links() {
		return List.copyOf(links);
	}

	public synchronized List<Link> stations() {
		return List.copyOf(stations);
	}

	public synchronized boolean isStation(VillagePair key) {
		Integer index = stationByVillage.get(key.first());
		Integer other = stationByVillage.get(key.second());
		return index != null && stations.get(index).key().equals(key) || other != null && stations.get(other).key().equals(key);
	}

	/** The station track of a village, or null while it has no line. */
	public synchronized Link stationOf(long village) {
		Integer index = stationByVillage.get(village);
		return index == null ? null : stations.get(index);
	}

	public synchronized boolean knows(long villageId) {
		return villages.containsKey(villageId);
	}

	/**
	 * Adds the villages not seen before and plans the lines they make possible, together with lines that waited for
	 * their surroundings to be explored. Call {@link #markExplored} for the area the batch comes from first.
	 *
	 * @return the new lines and the stations they need, in the order they were planned
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
			Link link = tryLink(a, b, filter, false, added);
			if (link != null) {
				addLink(link);
				added.add(link);
			}
		}
		linkGroups(filter, added);
		return added;
	}

	/**
	 * Joins groups of villages with trunk lines. Groups are the villages the ordinary lines connect; each group gets at
	 * most {@code maxGroupLinks} trunk lines and at most one to any other group, nearest groups first. A trunk starts
	 * from the villages of each group closest to the other group, preferring ones with fewer lines: every line a village
	 * already has counts as {@value #TRUNK_LINE_PENALTY} blocks of extra distance. Village pairs that failed once are
	 * not tried again; pairs whose surroundings are not explored yet wait.
	 */
	private void linkGroups(PathFilter filter, List<Link> added) {
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
				Link link = tryLink(a, b, filter, true, added);
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
	/** Tells the planner which lines and stations planned before are not built at all yet, so they may change. */
	public synchronized void setUnbuilt(Predicate<Link> unbuilt) {
		this.unbuilt = unbuilt;
	}

	public synchronized Map<Rejection, Integer> lastRejections() {
		return Map.copyOf(lastRejections);
	}

	/** Sets the status of the line or station with the given key. */
	public synchronized void setStatus(VillagePair key, Link.Status status) {
		for (int i = 0; i < links.size(); i++) {
			if (links.get(i).key().equals(key)) {
				links.set(i, links.get(i).withStatus(status));
			}
		}
		for (int i = 0; i < stations.size(); i++) {
			if (stations.get(i).key().equals(key)) {
				stations.set(i, stations.get(i).withStatus(status));
			}
		}
	}

	/** Which way out of which station a found way belongs to: by slot and the side of the village it leaves from. */
	private record ExitKey(long village, Point station, StationLayout.Slot slot, int side) {
	}

	/**
	 * The station of a village. Once the village has a line it stays where it is; until then this is where it would
	 * go, not yet kept: on the side that faces the village's nearest neighbours best, among the sides where the track
	 * keeps out of other villages and clear of other tracks and the filter accepts it. Null when no side has room.
	 */
	private Link stationFor(Village village, PathFilter filter) {
		Link known = stationOf(village.id());
		if (known != null) {
			return known;
		}
		List<Point> neighbours = villagesNear(village.center(), settings.maxGroupLength()).stream()
				.filter(other -> other.id() != village.id())
				.sorted(Comparator.comparingLong(other -> other.center().distanceSq(village.center())))
				.limit(settings.maxLinks())
				.map(Village::center)
				.toList();
		for (StationLayout.Station station : StationLayout.candidates(village, neighbours, settings.stationMargin())) {
			List<Point> track = trackFacingVillage(station);
			if (clearOfOtherVillages(village, track) && !comesTooClose(track, village, village) && filter.accepts(track)) {
				return new Link(village.id(), stationId(village.id(), station.middle()), track, Link.Status.PLANNED, false);
			}
		}
		return null;
	}

	/**
	 * The track of a station, run the way that has the village on its right: the side the builder puts a halt on, so
	 * the station building stands between the track and the village.
	 */
	private static List<Point> trackFacingVillage(StationLayout.Station station) {
		List<Point> track = station.track();
		// Going from the first end to the second, the right is the way along turned clockwise.
		boolean villageOnRight = (station.along() + 1) % 4 == (station.out() + 2) % 4;
		return villageOnRight ? track : track.reversed();
	}

	/**
	 * The slots of a village's station no line uses yet. A station is built only as far as its lines need, so once a
	 * chunk of it is built no slot is free. A western station has the ends of its second track too, but no middle,
	 * which would cross them; an outer end beside a trunk line's end carries the trunk's second rail.
	 */
	private List<StationLayout.Slot> freeSlots(Village village, Link station, StationLayout.Station layout) {
		List<StationLayout.Slot> free = new ArrayList<>();
		if (station.status() != Link.Status.PLANNED || !unbuilt.test(station)) {
			return free;
		}
		for (StationLayout.Slot slot : StationLayout.Slot.values()) {
			StationLayout.Slot inner = StationLayout.Station.innerOf(slot);
			boolean open = station.trunk() ? slot != StationLayout.Slot.MIDDLE
					&& (inner == null || !isTrunkEnd(village, layout.end(inner))) : inner == null;
			if (open && !isUsed(village, layout.end(slot))) {
				free.add(slot);
			}
		}
		return free;
	}

	private boolean isTrunkEnd(Village village, Point slot) {
		return linksOf(village.id()).stream().anyMatch(link -> link.trunk()
				&& (link.path().getFirst().equals(slot) || link.path().getLast().equals(slot)));
	}

	private boolean isUsed(Village village, Point slot) {
		return linksOf(village.id()).stream()
				.anyMatch(link -> link.path().getFirst().equals(slot) || link.path().getLast().equals(slot));
	}

	/**
	 * The corners of the way a line leaves a station slot towards {@code target}: from the slot, round the village
	 * where need be, out past the side of the village facing the target. Null when no way fits.
	 */
	private List<Point> wayOut(Village village, StationLayout.Station station, StationLayout.Slot slot, Point target) {
		int dx = target.x() - village.center().x();
		int dz = target.z() - village.center().z();
		int side = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? 0 : 2) : (dz >= 0 ? 1 : 3);
		ExitKey key = new ExitKey(village.id(), station.middle(), slot, side);
		if (!exits.containsKey(key)) {
			exits.put(key, findWayOut(village, station, slot, side));
		}
		return exits.get(key);
	}

	private List<Point> findWayOut(Village village, StationLayout.Station station, StationLayout.Slot slot, int side) {
		int beyond = switch (side) {
			case 0 -> village.maxX() + EXIT_MARGIN;
			case 1 -> village.maxZ() + EXIT_MARGIN;
			case 2 -> village.minX() - EXIT_MARGIN;
			default -> village.minZ() - EXIT_MARGIN;
		};
		int reach = EXIT_MARGIN + settings.villageMargin();
		int[] area = {village.minX() - reach, village.minZ() - reach, village.maxX() + reach, village.maxZ() + reach};
		// The footprint and the band just round it are closed, all but the strip the station stands on.
		ExitGrid grid = new ExitGrid(area, List.of(new Box(village.minX(), village.minZ(), village.maxX(), village.maxZ())),
				settings.stationMargin() - 1);
		Link own = stationOf(village.id());
		if (own != null && own.trunk() || tryingWestern.contains(village.id())) {
			grid.close(station.house());
		}
		tracksIn(area[0], area[1], area[2], area[3], linkGrid, links).forEach(link -> grid.addTrack(link.path()));
		List<List<Point>> stationTracks = new ArrayList<>(List.of(station.track()));
		tracksIn(area[0], area[1], area[2], area[3], stationGrid, stations).forEach(track -> stationTracks.add(track.path()));
		for (List<Point> path : stationTracks) {
			// In two halves, so the middle slot is an end too, where a way may start.
			grid.addTrack(List.of(path.get(0), path.get(1)));
			grid.addTrack(List.of(path.get(1), path.get(2)));
		}
		Point start = station.end(slot);
		return ExitFinder.leave(start, station.leaving(slot), side, beyond, area, grid.blocked(List.of(start)),
				MAX_EXIT_LENGTH);
	}

	private boolean clearOfOtherVillages(Village village, List<Point> route) {
		int[] box = bounds(route);
		int reach = MAX_VILLAGE_REACH + settings.villageMargin();
		for (Village other : villagesIn(box[0] - reach, box[1] - reach, box[2] + reach, box[3] + reach)) {
			for (int i = 1; i < route.size() && other.id() != village.id(); i++) {
				if (other.touches(route.get(i - 1), route.get(i), settings.villageMargin())) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * An id for the second end of a station's key, which no village has: a village id is a packed start chunk, whose
	 * low half is a chunk x, never between 2^30 and 2^31.
	 */
	private static long stationId(long village, Point middle) {
		int hash = Long.hashCode(village) * 31 + middle.hashCode();
		return ((long) hash << 32) | 0x40000000L | (Integer.toUnsignedLong(Integer.rotateLeft(hash, 7)) & 0x3FFFFFFFL);
	}

	/**
	 * A line between two villages, from a free slot of one's station to a free slot of the other's: out of each village
	 * and on through the open in stepped straights, the best of the routes that keep clear of every village and track,
	 * by the route scorer, that the filter accepts. A village's station is kept with its first line, and goes into
	 * {@code added} before it.
	 */
	private Link tryLink(Village a, Village b, PathFilter filter, boolean trunk, List<Link> added) {
		if (linksOf(a.id()).size() >= settings.maxLinks() || linksOf(b.id()).size() >= settings.maxLinks()
				|| linked(a.id(), b.id())) {
			return reject(Rejection.NO_FREE_SLOT);
		}
		Link stationA = stationFor(a, filter);
		Link stationB = stationFor(b, filter);
		if (stationA == null || stationB == null) {
			return reject(Rejection.NO_STATION);
		}
		StationLayout.Station layoutA = StationLayout.of(stationA.path(), a);
		StationLayout.Station layoutB = StationLayout.of(stationB.path(), b);
		if (trunk) {
			// Either village may be the one the trunk leaves from, taking it at its first end; the way round where those
			// ends face each other goes first.
			boolean aFirst = facing(layoutA, StationLayout.Slot.FIRST_END, b) + facing(layoutB, StationLayout.Slot.SECOND_END, a)
					>= facing(layoutB, StationLayout.Slot.FIRST_END, a) + facing(layoutA, StationLayout.Slot.SECOND_END, b);
			Link western = aFirst ? westernTrunk(a, b, stationA, stationB, layoutA, layoutB, filter, added)
					: westernTrunk(b, a, stationB, stationA, layoutB, layoutA, filter, added);
			if (western == null) {
				western = aFirst ? westernTrunk(b, a, stationB, stationA, layoutB, layoutA, filter, added)
						: westernTrunk(a, b, stationA, stationB, layoutA, layoutB, filter, added);
			}
			if (western != null) {
				return western;
			}
		}
		List<StationLayout.Slot> slotsA = freeSlots(a, stationA, layoutA);
		List<StationLayout.Slot> slotsB = freeSlots(b, stationB, layoutB);
		if (slotsA.isEmpty() || slotsB.isEmpty()) {
			return reject(Rejection.NO_FREE_SLOT);
		}
		Village from = a;
		Village to = b;
		RouteChoice choice = null;
		if (trunk) {
			// At a western station a trunk line leaves at the first end and comes in at the second, where its second rail,
			// on its left, runs on into the station's second track. Either village may be the one it leaves.
			for (boolean aLeaves : new boolean[] {true, false}) {
				Village out = aLeaves ? a : b;
				Village in = aLeaves ? b : a;
				List<StationLayout.Slot> outSlots = trunkSlots(aLeaves ? stationA : stationB, aLeaves ? slotsA : slotsB,
						StationLayout.Slot.FIRST_END);
				List<StationLayout.Slot> inSlots = trunkSlots(aLeaves ? stationB : stationA, aLeaves ? slotsB : slotsA,
						StationLayout.Slot.SECOND_END);
				if (outSlots.isEmpty() || inSlots.isEmpty()) {
					continue;
				}
				RouteChoice directed = bestRoute(out, in, aLeaves ? layoutA : layoutB, aLeaves ? layoutB : layoutA,
						outSlots, inSlots, filter);
				if (directed.path() != null) {
					choice = directed;
					from = out;
					to = in;
					break;
				}
			}
		}
		if (choice == null) {
			// Where the sides cannot meet, the trunk line comes in single at a western station.
			choice = bestRoute(a, b, layoutA, layoutB, slotsA, slotsB, filter);
		}
		if (choice.path() == null) {
			return reject(choice.reason());
		}
		for (Link station : List.of(stationA, stationB)) {
			if (stationOf(station.from()) == null) {
				addStation(station);
				added.add(station);
			}
		}
		return new Link(from.id(), to.id(), choice.path(), Link.Status.PLANNED, trunk);
	}

	/**
	 * The free slots of a station a trunk line may use going the way {@code end} takes it: at a western station only
	 * that end, the first for a line leaving, the second for one coming in; at any other station all of them.
	 */
	private static List<StationLayout.Slot> trunkSlots(Link station, List<StationLayout.Slot> free, StationLayout.Slot end) {
		if (!station.trunk()) {
			return free;
		}
		return free.contains(end) ? List.of(end) : List.of();
	}

	/** How straight a line leaving a station at {@code slot} heads for another village: 1 straight at it, -1 away. */
	private static double facing(StationLayout.Station station, StationLayout.Slot slot, Village other) {
		Point end = station.end(slot);
		int[] way = StationLayout.DIRECTIONS[station.leaving(slot)];
		double dx = other.center().x() - end.x();
		double dz = other.center().z() - end.z();
		return (way[0] * dx + way[1] * dz) / Math.max(1, Math.hypot(dx, dz));
	}

	/** The route a line takes, or else why none fits. */
	private record RouteChoice(List<Point> path, Rejection reason) {
	}

	/**
	 * A trunk line between two western stations: out of the first village's station at its first end and into the
	 * second village's at its second end. At those ends the trunk's second rail, on its left, comes out on the village
	 * side of the track, where the station's own second track runs. Lines in the way at either station move to its free
	 * ends first. Null, with nothing changed, when a station cannot become western there or no way between those ends
	 * fits; the trunk is then planned like any other line. A line or station changed that was planned before goes into
	 * {@code added} again.
	 */
	private Link westernTrunk(Village a, Village b, Link stationA, Link stationB, StationLayout.Station layoutA,
			StationLayout.Station layoutB, PathFilter filter, List<Link> added) {
		List<Link[]> moves = new ArrayList<>();
		RouteChoice choice = null;
		if (clearFor(a, layoutA, StationLayout.Slot.FIRST_END, filter, added, moves)
				&& clearFor(b, layoutB, StationLayout.Slot.SECOND_END, filter, added, moves)
				&& canBeWestern(a, stationA, layoutA, StationLayout.Slot.FIRST_END, filter, added)
				&& canBeWestern(b, stationB, layoutB, StationLayout.Slot.SECOND_END, filter, added)) {
			// The ways out keep off the houses the stations are about to get.
			tryingWestern.add(a.id());
			tryingWestern.add(b.id());
			exits.keySet().removeIf(key -> key.village() == a.id() || key.village() == b.id());
			choice = bestRoute(a, b, layoutA, layoutB, List.of(StationLayout.Slot.FIRST_END),
					List.of(StationLayout.Slot.SECOND_END), filter);
			tryingWestern.clear();
			exits.keySet().removeIf(key -> key.village() == a.id() || key.village() == b.id());
		}
		if (choice == null || choice.path() == null) {
			for (Link[] move : moves.reversed()) {
				replaceLink(move[1], move[0], added);
			}
			return null;
		}
		for (Link station : List.of(stationA, stationB)) {
			if (station.trunk()) {
				// Western already, from another trunk line: nothing of it changes.
				continue;
			}
			Link western = station.withTrunk(true);
			Integer index = stationByVillage.get(station.from());
			if (index == null) {
				addStation(western);
				added.add(western);
			} else {
				stations.set(index, western);
				added.removeIf(link -> link.key().equals(western.key()));
				added.add(western);
				// Its house closes the ground beside it to the village's other ways out.
				exits.keySet().removeIf(key -> key.village() == station.from());
			}
		}
		return new Link(a.id(), b.id(), choice.path(), Link.Status.PLANNED, true);
	}

	/**
	 * Clears a village's station for a western house with a trunk at {@code trunkEnd}: the lines that come in at that
	 * end, at the end of the second track beside it, or at the middle move to the other free ends, of the track or of the
	 * second track, at the same slot of their other villages. Each move goes into {@code moves} as the old and the new
	 * line. False when a line there cannot move: one that is building already, or one with no way from a free end.
	 */
	private boolean clearFor(Village village, StationLayout.Station layout, StationLayout.Slot trunkEnd, PathFilter filter,
			List<Link> added, List<Link[]> moves) {
		StationLayout.Slot trunkOuter = trunkEnd == StationLayout.Slot.FIRST_END ? StationLayout.Slot.OUTER_FIRST
				: StationLayout.Slot.OUTER_SECOND;
		List<Point> taken = List.of(layout.end(trunkEnd), layout.end(trunkOuter), layout.end(StationLayout.Slot.MIDDLE));
		for (Link line : linksOf(village.id())) {
			Point here = line.from() == village.id() ? line.path().getFirst() : line.path().getLast();
			if (!taken.contains(here)) {
				continue;
			}
			if (!added.contains(line) && !unbuilt.test(line)) {
				return false;
			}
			List<StationLayout.Slot> ends = new ArrayList<>();
			for (StationLayout.Slot slot : List.of(StationLayout.Slot.FIRST_END, StationLayout.Slot.SECOND_END,
					StationLayout.Slot.OUTER_FIRST, StationLayout.Slot.OUTER_SECOND)) {
				if (slot != trunkEnd && slot != trunkOuter && !isUsed(village, layout.end(slot))) {
					ends.add(slot);
				}
			}
			if (ends.isEmpty()) {
				return false;
			}
			Village other = villages.get(line.other(village.id()));
			StationLayout.Station otherLayout = StationLayout.of(stationOf(other.id()).path(), other);
			Point otherEnd = line.from() == village.id() ? line.path().getLast() : line.path().getFirst();
			StationLayout.Slot otherSlot = Arrays.stream(StationLayout.Slot.values())
					.filter(slot -> otherLayout.end(slot).equals(otherEnd)).findFirst().orElseThrow();
			int index = links.indexOf(line);
			// Planned again without itself in the way.
			removeFromGrid(linkGrid, line.path(), index);
			RouteChoice choice = line.from() == village.id()
					? bestRoute(village, other, layout, otherLayout, ends, List.of(otherSlot), filter)
					: bestRoute(other, village, otherLayout, layout, List.of(otherSlot), ends, filter);
			addToGrid(linkGrid, line.path(), index);
			if (choice.path() == null) {
				return false;
			}
			Link moved = new Link(line.from(), line.to(), choice.path(), line.status(), line.trunk());
			replaceLink(line, moved, added);
			moves.add(new Link[] {line, moved});
		}
		return true;
	}

	/** Puts a line in the place of another between the same villages, in the grid and in {@code added} too. */
	private void replaceLink(Link old, Link replacement, List<Link> added) {
		int index = links.indexOf(old);
		removeFromGrid(linkGrid, old.path(), index);
		links.set(index, replacement);
		addToGrid(linkGrid, replacement.path(), index);
		added.remove(old);
		added.add(replacement);
		exits.keySet().removeIf(key -> key.village() == old.from() || key.village() == old.to());
	}

	/**
	 * Whether a village's station can be or already is a western one with a trunk line at {@code slot}: not built yet,
	 * that end free, nothing at its middle, and the ground for the house clear of tracks, other villages and what the
	 * filter keeps lines away from. A station from an earlier batch only becomes western while nothing of it is built.
	 */
	private boolean canBeWestern(Village village, Link station, StationLayout.Station layout, StationLayout.Slot slot,
			PathFilter filter, List<Link> added) {
		if (station.status() != Link.Status.PLANNED || isUsed(village, layout.end(slot))
				|| isUsed(village, layout.end(StationLayout.Slot.MIDDLE))) {
			return false;
		}
		if (station.trunk()) {
			return true;
		}
		if (stationOf(village.id()) != null && !added.contains(station) && !unbuilt.test(station)) {
			return false;
		}
		Box house = layout.house();
		List<Point> outline = List.of(new Point(house.minX(), house.minZ()), new Point(house.maxX(), house.minZ()),
				new Point(house.maxX(), house.maxZ()), new Point(house.minX(), house.maxZ()),
				new Point(house.minX(), house.minZ()));
		List<Link> nearby = new ArrayList<>(tracksIn(house.minX() - 2, house.minZ() - 2, house.maxX() + 2,
				house.maxZ() + 2, linkGrid, links));
		nearby.addAll(tracksIn(house.minX() - 2, house.minZ() - 2, house.maxX() + 2, house.maxZ() + 2, stationGrid,
				stations));
		for (Link track : nearby) {
			if (stations.contains(track) && track.from() == village.id()) {
				continue;
			}
			// The village's own lines come in at the ends of the track, beside the house.
			boolean own = track.connects(village.id());
			for (int i = 1; i < track.path().size(); i++) {
				if (!(own && (i == 1 || i == track.path().size() - 1))
						&& house.touches(track.path().get(i - 1), track.path().get(i), 1)) {
					return false;
				}
			}
		}
		int reach = MAX_VILLAGE_REACH + settings.villageMargin();
		for (Village other : villagesIn(house.minX() - reach, house.minZ() - reach, house.maxX() + reach,
				house.maxZ() + reach)) {
			for (int i = 1; i < outline.size() && other.id() != village.id(); i++) {
				if (other.touches(outline.get(i - 1), outline.get(i), settings.villageMargin())) {
					return false;
				}
			}
		}
		return filter.accepts(outline);
	}

	/**
	 * The best route between two villages from the given station slots, by the route scorer, that keeps clear of every
	 * village and track and that the filter accepts.
	 */
	private RouteChoice bestRoute(Village a, Village b, StationLayout.Station layoutA, StationLayout.Station layoutB,
			List<StationLayout.Slot> slotsA, List<StationLayout.Slot> slotsB, PathFilter filter) {
		// Every route shape out of every pair of free slots. The checks go first, cheapest first, so only routes that
		// can be built are scored on the terrain, which costs the most: most routes of a long line that fails cross too
		// much sea.
		Set<List<Point>> routes = new LinkedHashSet<>();
		for (StationLayout.Slot slotA : slotsA) {
			for (StationLayout.Slot slotB : slotsB) {
				List<Point> outA = wayOut(a, layoutA, slotA, b.center());
				List<Point> outB = wayOut(b, layoutB, slotB, a.center());
				if (outA == null || outB == null) {
					continue;
				}
				Point fromA = outA.getLast();
				Point fromB = outB.getLast();
				boolean mainIsX = Math.abs(fromB.x() - fromA.x()) >= Math.abs(fromB.z() - fromA.z());
				for (List<Point> open : RouteStepper.candidates(fromA, fromB, mainIsX, settings)) {
					List<Point> route = joined(outA, open, outB.reversed());
					if (route != null && hasApproaches(route)) {
						routes.add(route);
					}
				}
			}
		}
		if (routes.isEmpty()) {
			return new RouteChoice(null, Rejection.NO_WAY_OUT);
		}
		Rejection firstReason = null;
		boolean filtered = false;
		List<Point> best = null;
		double bestScore = Double.MAX_VALUE;
		for (List<Point> path : routes) {
			Rejection reason = passesThroughVillage(path, a, b) ? Rejection.THROUGH_VILLAGE
					: comesTooClose(path, a, b) ? Rejection.TOO_CLOSE_TO_LINE
					: null;
			if (reason != null) {
				firstReason = firstReason == null ? reason : firstReason;
			} else if (!filter.accepts(path)) {
				filtered = true;
			} else {
				double score = scorer.score(path);
				if (score < bestScore) {
					best = path;
					bestScore = score;
				}
			}
		}
		return best != null ? new RouteChoice(best, null)
				: new RouteChoice(null, filtered ? Rejection.FILTERED : firstReason);
	}

	/** Whether a route leaves both its stations straight for {@value ExitFinder#APPROACH} blocks before it turns. */
	private static boolean hasApproaches(List<Point> route) {
		Point first = route.get(0);
		Point second = route.get(1);
		Point last = route.getLast();
		Point beforeLast = route.get(route.size() - 2);
		return Math.abs(second.x() - first.x()) + Math.abs(second.z() - first.z()) >= ExitFinder.APPROACH
				&& Math.abs(last.x() - beforeLast.x()) + Math.abs(last.z() - beforeLast.z()) >= ExitFinder.APPROACH;
	}

	/**
	 * The corners of routes laid end to end, or null where one would turn back on itself at the joint. Points in the
	 * middle of a straight are dropped.
	 */
	@SafeVarargs
	private static List<Point> joined(List<Point>... parts) {
		List<Point> points = new ArrayList<>();
		for (List<Point> part : parts) {
			for (Point point : part) {
				if (points.isEmpty() || !points.getLast().equals(point)) {
					points.add(point);
				}
			}
		}
		List<Point> corners = new ArrayList<>();
		for (Point point : points) {
			if (corners.size() >= 2) {
				int before = ExitFinder.direction(corners.get(corners.size() - 2), corners.getLast());
				int after = ExitFinder.direction(corners.getLast(), point);
				if (before == (after + 2) % 4) {
					return null;
				}
				if (before == after) {
					corners.removeLast();
				}
			}
			corners.add(point);
		}
		return List.copyOf(corners);
	}

	private Link reject(Rejection reason) {
		lastRejections.merge(reason, 1, Integer::sum);
		return null;
	}

	/**
	 * Whether a path runs into a village: near any part of a village it does not serve, or into the footprint of the
	 * two it does, whose stations it reaches from outside.
	 */
	private boolean passesThroughVillage(List<Point> path, Village a, Village b) {
		int[] box = bounds(path);
		int reach = MAX_VILLAGE_REACH + settings.villageMargin();
		for (Village village : villagesIn(box[0] - reach, box[1] - reach, box[2] + reach, box[3] + reach)) {
			boolean served = village.id() == a.id() || village.id() == b.id();
			for (int i = 1; i < path.size(); i++) {
				Point from = path.get(i - 1);
				Point to = path.get(i);
				boolean hit = village.touches(from, to, served ? SERVED_MARGIN : settings.villageMargin());
				if (hit) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether a path comes too close to a planned line or station. Lines keep {@code clearance} apart, except two lines
	 * of the same village: they may run {@value #DOUBLE_TRACK} apart, side by side or where one turns away from the
	 * other, and near that village they may come as close as that anywhere. Rails never come closer, but for a line's
	 * first and last straights, which meet its villages' stations.
	 */
	private boolean comesTooClose(List<Point> path, Village a, Village b) {
		int[] box = bounds(path);
		int clearance = settings.clearance();
		long clearanceSq = (long) clearance * clearance;
		long doubleTrackSq = (long) DOUBLE_TRACK * DOUBLE_TRACK;
		int nearShared = settings.stationMargin() + settings.maxRun();
		List<Link> nearby = new ArrayList<>(tracksIn(box[0] - clearance, box[1] - clearance, box[2] + clearance,
				box[3] + clearance, linkGrid, links));
		List<Link> nearbyStations = tracksIn(box[0] - clearance, box[1] - clearance, box[2] + clearance,
				box[3] + clearance, stationGrid, stations);
		nearby.addAll(nearbyStations);
		for (Link link : nearby) {
			// A station belongs to its village alone: its from is the village, its to no village at all.
			Village shared = link.connects(a.id()) ? a : link.connects(b.id()) ? b : null;
			boolean ownStation = shared != null && nearbyStations.contains(link);
			for (int i = 1; i < path.size(); i++) {
				Point p1 = path.get(i - 1);
				Point p2 = path.get(i);
				if (ownStation && (i == 1 || i == path.size() - 1)) {
					continue;
				}
				for (int j = 1; j < link.path().size(); j++) {
					Point q1 = link.path().get(j - 1);
					Point q2 = link.path().get(j);
					long gapSq = gapSq(p1, p2, q1, q2);
					if (gapSq < doubleTrackSq) {
						return true;
					}
					if (gapSq >= clearanceSq || shared == null) {
						if (gapSq < clearanceSq) {
							return true;
						}
						continue;
					}
					// Exactly a double track apart: side by side, or where one track turns away from the other.
					boolean doubleTrack = gapSq == doubleTrackSq;
					boolean leavingTogether = shared.touches(p1, p2, nearShared) && shared.touches(q1, q2, nearShared);
					if (!doubleTrack && !leavingTogether) {
						return true;
					}
				}
			}
		}
		for (Link station : nearbyStations) {
			if (!station.trunk()) {
				continue;
			}
			// A western station's house: its own lines leave from the ends of the track, beside it.
			boolean own = station.from() == a.id() || station.from() == b.id();
			Box house = StationLayout.of(station.path(), villages.get(station.from())).house();
			for (int i = 1; i < path.size(); i++) {
				if (!(own && (i == 1 || i == path.size() - 1)) && house.touches(path.get(i - 1), path.get(i), 1)) {
					return true;
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
		addToGrid(linkGrid, link.path(), index);
		// The ways out of its two villages change with the new line.
		exits.keySet().removeIf(key -> key.village() == link.from() || key.village() == link.to());
	}

	private void addStation(Link station) {
		stationByVillage.put(station.from(), stations.size());
		addToGrid(stationGrid, station.path(), stations.size());
		stations.add(station);
	}

	private static void removeFromGrid(Map<Long, List<Integer>> grid, List<Point> path, int index) {
		for (int i = 1; i < path.size(); i++) {
			Point p = path.get(i - 1);
			Point q = path.get(i);
			forEachCell(Math.min(p.x(), q.x()), Math.min(p.z(), q.z()), Math.max(p.x(), q.x()), Math.max(p.z(), q.z()),
					LINK_CELL, key -> {
						List<Integer> cell = grid.get(key);
						if (cell != null) {
							cell.remove(Integer.valueOf(index));
						}
					});
		}
	}

	private static void addToGrid(Map<Long, List<Integer>> grid, List<Point> path, int index) {
		Set<Long> cells = new HashSet<>();
		for (int i = 1; i < path.size(); i++) {
			Point p = path.get(i - 1);
			Point q = path.get(i);
			forEachCell(Math.min(p.x(), q.x()), Math.min(p.z(), q.z()), Math.max(p.x(), q.x()), Math.max(p.z(), q.z()),
					LINK_CELL, cells::add);
		}
		cells.forEach(key -> grid.computeIfAbsent(key, k -> new ArrayList<>()).add(index));
	}

	/** The lines or stations with a segment in a cell the given block area touches. */
	private static List<Link> tracksIn(int minX, int minZ, int maxX, int maxZ, Map<Long, List<Integer>> grid,
			List<Link> tracks) {
		Set<Integer> found = new LinkedHashSet<>();
		forEachCell(minX, minZ, maxX, maxZ, LINK_CELL, key -> found.addAll(grid.getOrDefault(key, List.of())));
		return found.stream().map(tracks::get).toList();
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

}
