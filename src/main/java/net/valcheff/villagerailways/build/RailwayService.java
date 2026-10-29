package net.valcheff.villagerailways.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.valcheff.villagerailways.RailwayConfig;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.map.NetworkMapItem;
import net.valcheff.villagerailways.map.NetworkMaps;
import net.valcheff.villagerailways.network.Link;
import net.valcheff.villagerailways.network.NetworkPlanner;
import net.valcheff.villagerailways.network.NetworkPlanner.VillagePair;
import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.network.StationLayout;
import net.valcheff.villagerailways.network.Village;
import net.valcheff.villagerailways.profile.TerrainSampler;
import net.valcheff.villagerailways.profile.TrackPlan;
import net.valcheff.villagerailways.profile.TrackPlanner;
import net.valcheff.villagerailways.profile.TrackSettings;
import net.valcheff.villagerailways.structure.StructurePredictor;

/**
 * Runs the railway network of the overworld: plans lines ahead of chunk generation on a background thread and builds
 * them into chunks as the chunks generate.
 *
 * <p>The land is explored in squares of {@value #SQUARE} blocks. Around spawn the squares are planned before the
 * first chunk generates. After that every player, and every chunk that generates, keeps the squares within
 * {@value #PLAN_AHEAD} squares planned, so lines, trunk lines included, are in place before their chunks are made: a
 * chunk waits for the squares around it before it generates, which matters when chunks are generated ahead of any
 * player, as world pregenerators do. Players keep the planning far enough ahead that their chunks seldom wait.
 * Villages at the edge of what is planned get their lines to newly found villages as the planned area grows.
 */
public class RailwayService {
	private static final int SQUARE = 1024;
	/**
	 * Squares planned around each player and each generating chunk: three reach past the longest trunk line and the
	 * ground its far village needs explored.
	 */
	private static final int PLAN_AHEAD = 3;
	private static final int SPAWN_SQUARES = 2;
	/** Ticks players may spend in a chunk before it is closed to new lines: two minutes. */
	private static final int LIVED_TICKS = 2400;
	private static final int LIVED_CHECK_INTERVAL = 40;
	/** Seconds between reports of the planning that generating chunks wait for, as at the first start of a world. */
	private static final int PROGRESS_SECONDS = 3;
	/** Chunks around each player checked for time spent, beyond the simulation distance. */
	private static final int LIVED_RADIUS = 12;
	/** Chunks given their late lines per tick, so a burst does not stall the server. */
	private static final int LATER_PER_TICK = 2;
	/**
	 * How far round its villages a trunk line stays single: past the area where lines find their way out to their
	 * stations, with the other lines of the village close by.
	 */
	private static final int SINGLE_NEAR_VILLAGE = 32;
	/** How far round its track a western station builds: its house and platform, their eaves and the ground cleared. */
	private static final int WESTERN_REACH = 20;
	/** Closest another track may run to a line where it gets a second rail. */
	private static final int SECOND_RAIL_CLEARANCE = TrackPlanner.DOUBLE_TRACK + 2;

	private final ServerLevel level;
	private final RailwayNetwork network;
	private final BuildState buildState;
	private final TrackPlanner trackPlanner;
	private final TrackSettings trackSettings;
	private final TerrainSampler terrain;
	private final Map<VillagePair, Link> lines = new ConcurrentHashMap<>();
	private final Map<VillagePair, Optional<TrackPlan>> plans = new ConcurrentHashMap<>();
	/** Whether lines were planned or finished since the maps on the station walls were drawn. */
	private volatile boolean wallsStale = true;
	/** Generating chunks waiting for planning now. */
	private final AtomicInteger waiting = new AtomicInteger();
	/** When the planning that chunks wait for was last reported, in nanoseconds. */
	private final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
	/** The squares queued for planning, done once planned. */
	private final Map<Long, CompletableFuture<Void>> squares = new ConcurrentHashMap<>();
	private final ExecutorService planner = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Village Railways planner");
		thread.setDaemon(true);
		return thread;
	});

	private RailwayService(ServerLevel level, RailwayNetwork network, BuildState buildState,
			TrackSettings trackSettings) {
		this.level = level;
		this.network = network;
		this.buildState = buildState;
		this.trackSettings = trackSettings;
		this.trackPlanner = new TrackPlanner(level, trackSettings);
		this.terrain = new TerrainSampler(level);
	}

	/** Loads the network and building state of the overworld and plans the land around the world origin. */
	public static RailwayService start(ServerLevel overworld, RailwayConfig config) throws IOException {
		Path root = overworld.getServer().getWorldPath(LevelResource.ROOT);
		Path folder = root.resolve("village_railways");
		BuildState buildState = BuildState.load(folder, DimensionType.getStorageFolder(Level.OVERWORLD, root).resolve("region"));
		RailwayNetwork network = RailwayNetwork.load(overworld, StructurePredictor.villages(overworld),
				path -> !buildState.touchesClosed(path), config.planner());
		RailwayService service = new RailwayService(overworld, network, buildState, config.trackFor(folder));
		network.planner().setUnbuilt(link -> buildState.isUnbuilt(link.key()));
		List<Link> tracks = new ArrayList<>(network.planner().links());
		tracks.addAll(network.planner().stations());
		for (Link link : tracks) {
			if (link.status() == Link.Status.PLANNED) {
				service.lines.put(link.key(), link);
				buildState.register(link, true, service.reachOf(link));
			}
		}
		// New worlds put spawn near the origin; plan it before any chunk there generates.
		for (int squareX = -SPAWN_SQUARES; squareX < SPAWN_SQUARES; squareX++) {
			for (int squareZ = -SPAWN_SQUARES; squareZ < SPAWN_SQUARES; squareZ++) {
				service.planSquare(squareX, squareZ);
				service.squares.put(ChunkPos.pack(squareX, squareZ), CompletableFuture.completedFuture(null));
			}
		}
		return service;
	}

	public RailwayNetwork network() {
		return network;
	}

	/** Runs {@code task} on the planner thread, once the planning queued before it is done. */
	public void afterPlanning(Runnable task) {
		planner.execute(task);
	}

	/** How many chunks of a line have generated with it so far, and how many it has; null for a line not registered. */
	public int[] progress(VillagePair line) {
		return buildState.progress(line);
	}

	/** Queues planning for the squares around every player that are not planned yet. */
	public void planAroundPlayers() {
		for (ServerPlayer player : level.players()) {
			planAround(player.getBlockX(), player.getBlockZ());
		}
	}

	/**
	 * Queues planning for the squares within {@value #PLAN_AHEAD} squares of a block that are not queued yet, nearest
	 * first, and returns them all, done once planned.
	 */
	private List<CompletableFuture<Void>> planAround(int blockX, int blockZ) {
		int squareX = Math.floorDiv(blockX, SQUARE);
		int squareZ = Math.floorDiv(blockZ, SQUARE);
		List<CompletableFuture<Void>> around = new ArrayList<>();
		for (int ring = 0; ring <= PLAN_AHEAD; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					int x = squareX + dx;
					int z = squareZ + dz;
					around.add(squares.computeIfAbsent(ChunkPos.pack(x, z),
							key -> CompletableFuture.runAsync(() -> planSquare(x, z), planner)));
				}
			}
		}
		return around;
	}

	/**
	 * Plans every square that touches the area of {@code radius} blocks around {@code center}, on the planner thread,
	 * then runs {@code done} there.
	 */
	public void planArea(BlockPos center, int radius, Runnable done) {
		planner.execute(() -> {
			for (int squareX = Math.floorDiv(center.getX() - radius, SQUARE); squareX <= Math.floorDiv(center.getX() + radius, SQUARE); squareX++) {
				for (int squareZ = Math.floorDiv(center.getZ() - radius, SQUARE); squareZ <= Math.floorDiv(center.getZ() + radius, SQUARE); squareZ++) {
					planSquare(squareX, squareZ);
				}
			}
			done.run();
		});
	}

	private void planSquare(int squareX, int squareZ) {
		BlockPos center = new BlockPos(squareX * SQUARE + SQUARE / 2, 0, squareZ * SQUARE + SQUARE / 2);
		try {
			RailwayNetwork.Result result = network.explore(center, SQUARE / 2 - 1);
			wallsStale |= !result.added().isEmpty();
			for (Link link : result.added()) {
				forgetStationPlans(link);
				if (lines.containsKey(link.key())) {
					// Planned before and changed since: a line moved to another end, a station made western.
					buildState.forget(link.key());
					plans.remove(link.key());
				}
				if (buildState.register(link, false, reachOf(link))) {
					lines.put(link.key(), link);
					if (buildState.hasQueued(link.key())) {
						// Part of it goes into chunks that exist already: profile it here, not on the server thread.
						planOf(link);
					}
				} else {
					// A chunk on the line generated while it was being planned.
					network.planner().setStatus(link.key(), Link.Status.BLOCKED);
				}
			}
		} catch (RuntimeException | IOException e) {
			VillageRailways.LOGGER.error("Could not plan railway lines around {}", center, e);
		}
	}

	/**
	 * Called every server tick: gives up to {@value #LATER_PER_TICK} loaded chunks the lines planned after they
	 * generated, and every {@value #LIVED_CHECK_INTERVAL} ticks notes the chunks around players they have lived in.
	 */
	public void tick(int tickCount) {
		if (tickCount % LIVED_CHECK_INTERVAL == 0) {
			markLivedChunks();
			planAroundPlayers();
			updateMapWalls();
		}
		Map<Long, List<VillagePair>> queued = buildState.takeQueued(
				chunk -> level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null, LATER_PER_TICK);
		for (Map.Entry<Long, List<VillagePair>> entry : queued.entrySet()) {
			ChunkPos chunk = ChunkPos.unpack(entry.getKey());
			List<TrackPlan> toBuild = new ArrayList<>();
			for (VillagePair key : entry.getValue()) {
				Optional<TrackPlan> plan = plans.get(key);
				if (plan == null) {
					// Not profiled yet: profile it off the server thread and try the chunk again after.
					Link link = lines.get(key);
					if (link != null) {
						planner.execute(() -> planOf(link));
						buildState.requeue(entry.getKey(), key);
					}
				} else if (plan.isPresent()) {
					toBuild.add(plan.get());
				}
			}
			if (!toBuild.isEmpty()) {
				LineBuilder.Reachable loaded = (x, z) -> level.getChunkSource().getChunkNow(x, z) != null;
				LineBuilder.build(level, chunk, toBuild, loaded, builtWith(toBuild, entry.getValue(), loaded),
						trackSettings);
				for (VillagePair key : entry.getValue()) {
					if (plans.get(key) != null && plans.get(key).isPresent()) {
						buildState.markBuilt(entry.getKey(), key);
					}
				}
			}
			for (VillagePair key : entry.getValue()) {
				if (buildState.isComplete(key)) {
					finish(key, Link.Status.BUILT);
				}
			}
		}
	}

	/**
	 * Hangs the map wall in every western house built so far that has none, once the chunks of the wall are loaded,
	 * and draws the walls' maps again when lines have been planned or finished since.
	 */
	private void updateMapWalls() {
		NetworkPlanner planner = network.planner();
		for (Link station : planner.stations()) {
			if (!station.trunk() || station.status() == Link.Status.BLOCKED || NetworkMaps.hasWall(station.key())) {
				continue;
			}
			Optional<TrackPlan> plan = planOf(station);
			if (plan.isPresent()) {
				LineBuilder.MapWall wall = LineBuilder.mapWall(plan.get());
				List<BlockPos> spots = new ArrayList<>(wall.frames());
				spots.add(wall.door());
				if (spots.stream().allMatch(frame -> isBuiltAndLoaded(station, frame)
						&& isBuiltAndLoaded(station, frame.relative(wall.facing().getOpposite())))) {
					hangMapWall(station, plan.get(), wall);
				}
			}
		}
		if (wallsStale) {
			wallsStale = false;
			NetworkMaps.wallMaps().forEach(id -> NetworkMapItem.redraw(level, planner, id));
		}
	}

	private boolean isBuiltAndLoaded(Link station, BlockPos block) {
		int chunkX = block.getX() >> 4;
		int chunkZ = block.getZ() >> 4;
		return (station.status() == Link.Status.BUILT || buildState.isBuilt(ChunkPos.pack(chunkX, chunkZ), station.key()))
				&& level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
	}

	/**
	 * Glow item frames with the station's maps on its wall, and one with a single map over its door; a spot that has
	 * a frame already keeps it.
	 */
	private void hangMapWall(Link station, TrackPlan plan, LineBuilder.MapWall wall) {
		int middle = plan.length() / 2;
		BlockPos at = new BlockPos(plan.x()[middle], plan.track()[middle], plan.z()[middle]);
		try {
			List<ItemStack> maps = new ArrayList<>(NetworkMapItem.stationWall(level, network.planner(), at));
			List<BlockPos> spots = new ArrayList<>(wall.frames());
			maps.add(NetworkMapItem.stationMap(level, network.planner(), at));
			spots.add(wall.door());
			for (int i = 0; i < maps.size(); i++) {
				BlockPos spot = spots.get(i);
				if (level.getEntitiesOfClass(ItemFrame.class, new AABB(spot)).isEmpty()) {
					GlowItemFrame frame = new GlowItemFrame(level, spot, wall.facing());
					frame.setItem(maps.get(i), false);
					level.addFreshEntity(frame);
				}
			}
			NetworkMaps.registerWall(station.key(), maps.stream().map(map -> map.get(DataComponents.MAP_ID)).toList());
		} catch (IOException e) {
			VillageRailways.LOGGER.error("Could not hang the map wall of the station at {}", at, e);
		}
	}

	/** Closes the chunks around players that they have spent too long in for new lines. */
	private void markLivedChunks() {
		for (ServerPlayer player : level.players()) {
			int centerX = player.getBlockX() >> 4;
			int centerZ = player.getBlockZ() >> 4;
			for (int dx = -LIVED_RADIUS; dx <= LIVED_RADIUS; dx++) {
				for (int dz = -LIVED_RADIUS; dz <= LIVED_RADIUS; dz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(centerX + dx, centerZ + dz);
					if (chunk != null && chunk.getInhabitedTime() >= LIVED_TICKS) {
						buildState.markLived(ChunkPos.pack(centerX + dx, centerZ + dz));
					}
				}
			}
		}
	}

	/**
	 * Waits until the given squares are planned. While it takes long, as it does at the first start of a world, how
	 * far the planning has got goes to the log every {@value #PROGRESS_SECONDS} seconds, so the start does not look
	 * stuck.
	 */
	private void awaitPlanned(List<CompletableFuture<Void>> around) {
		CompletableFuture<Void> all = CompletableFuture.allOf(around.toArray(CompletableFuture[]::new));
		if (all.isDone()) {
			return;
		}
		waiting.incrementAndGet();
		try {
			while (true) {
				try {
					all.get(PROGRESS_SECONDS, TimeUnit.SECONDS);
					return;
				} catch (TimeoutException e) {
					reportProgress();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (ExecutionException e) {
					throw new IllegalStateException("Railway planning failed", e.getCause());
				}
			}
		} finally {
			waiting.decrementAndGet();
		}
	}

	private void reportProgress() {
		long now = System.nanoTime();
		long last = lastProgress.get();
		int[] progress = planningProgress();
		if (progress != null && now - last >= TimeUnit.SECONDS.toNanos(PROGRESS_SECONDS)
				&& lastProgress.compareAndSet(last, now)) {
			VillageRailways.LOGGER.info("Planning railway lines before the land generates: {} of {} areas of "
					+ SQUARE + " by " + SQUARE + " blocks", progress[0], progress[1]);
		}
	}

	/**
	 * How many of the squares queued so far are planned, and how many there are, while generating chunks wait for
	 * them; null while none waits.
	 */
	public int[] planningProgress() {
		if (waiting.get() == 0) {
			return null;
		}
		int planned = (int) squares.values().stream().filter(CompletableFuture::isDone).count();
		return new int[] {planned, squares.size()};
	}

	/**
	 * Builds every line that runs through a chunk; called once per chunk, while it generates. The chunk waits until
	 * the squares around it are planned, so no line that should run through it is planned after it is made.
	 */
	public void decorate(WorldGenLevel region, ChunkPos chunk) {
		awaitPlanned(planAround(chunk.getMinBlockX(), chunk.getMinBlockZ()));
		List<VillagePair> keys = buildState.decorate(chunk);
		List<TrackPlan> toBuild = new ArrayList<>();
		List<VillagePair> built = new ArrayList<>();
		for (VillagePair key : keys) {
			Link link = lines.get(key);
			if (link == null) {
				continue;
			}
			Optional<TrackPlan> plan = planOf(link);
			if (plan.isEmpty()) {
				finish(key, Link.Status.BLOCKED);
				continue;
			}
			toBuild.add(plan.get());
			built.add(key);
		}
		if (toBuild.isEmpty()) {
			return;
		}
		LineBuilder.Reachable neighbours = (x, z) -> Math.abs(x - chunk.x()) <= 1 && Math.abs(z - chunk.z()) <= 1;
		LineBuilder.build(region, chunk, toBuild, neighbours, builtWith(toBuild, built, neighbours), trackSettings);
		long packed = ChunkPos.pack(chunk.x(), chunk.z());
		for (VillagePair key : built) {
			buildState.markBuilt(packed, key);
		}
		for (VillagePair key : built) {
			if (buildState.isComplete(key)) {
				finish(key, Link.Status.BUILT);
			}
		}
	}

	/**
	 * Which neighbouring chunks, of those {@code reachable} accepts, have each line being built already: there the
	 * builder lays again a rail a later feature took, and nowhere else.
	 */
	private LineBuilder.Built builtWith(List<TrackPlan> toBuild, List<VillagePair> keys, LineBuilder.Reachable reachable) {
		Map<TrackPlan, VillagePair> keyOf = new IdentityHashMap<>();
		for (VillagePair key : keys) {
			Optional<TrackPlan> plan = plans.get(key);
			if (plan != null && plan.isPresent() && toBuild.contains(plan.get())) {
				keyOf.put(plan.get(), key);
			}
		}
		return (x, z, line) -> {
			VillagePair key = keyOf.get(line);
			return key != null && reachable.test(x, z) && buildState.isBuilt(ChunkPos.pack(x, z), key);
		};
	}

	/**
	 * The block-by-block plan of a line or a station track, made once. A line runs from the rail height of one of its
	 * stations to the other's, with its halts; a station track lies level. A trunk line is a double track away from
	 * its villages, and any line over its bridges.
	 */
	public Optional<TrackPlan> planOf(Link link) {
		Optional<TrackPlan> cached = plans.get(link.key());
		if (cached != null) {
			return cached;
		}
		NetworkPlanner planner = network.planner();
		if (planner.isStation(link.key())) {
			return cache(link, Optional.of(stationPlan(link)));
		}
		Link from = planner.stationOf(link.from());
		Link to = planner.stationOf(link.to());
		if (from == null || to == null) {
			return cache(link, Optional.empty());
		}
		List<Link> others = new ArrayList<>(planner.links());
		others.addAll(planner.stations());
		// At a western station a line's second rail, on its left, runs on into the station's second track: out of a
		// station at its first end, into one at its second. The line is laid out the way round that lets it do so at
		// more of its ends; at an end where it cannot, it comes in single.
		boolean openStart = isWesternEnd(from, link.path().getFirst(), StationLayout.Slot.FIRST_END, link);
		boolean openEnd = isWesternEnd(to, link.path().getLast(), StationLayout.Slot.SECOND_END, link);
		boolean backStart = isWesternEnd(to, link.path().getLast(), StationLayout.Slot.FIRST_END, link);
		boolean backEnd = isWesternEnd(from, link.path().getFirst(), StationLayout.Slot.SECOND_END, link);
		boolean reversed = count(backStart, backEnd) > count(openStart, openEnd);
		if (reversed) {
			openStart = backStart;
			openEnd = backEnd;
			Link swap = from;
			from = to;
			to = swap;
		}
		List<Point> path = reversed ? link.path().reversed() : link.path();
		List<Long> single = new ArrayList<>();
		if (!openStart) {
			single.add(from.from());
		}
		if (!openEnd) {
			single.add(to.from());
		}
		TrackPlanner.Area doubled = link.trunk() ? awayFrom(planner, single) : null;
		// A second rail keeps clear of every other track, but for the line's own stations, which it runs into.
		List<Link> near = others.stream().filter(other -> !other.key().equals(link.key())
				&& !(planner.isStation(other.key()) && link.connects(other.from()))).toList();
		TrackPlanner.Area clear = (x, z) -> near.stream()
				.noneMatch(other -> HaltPlanner.passesNear(other.path(), x, z, SECOND_RAIL_CLEARANCE));
		Optional<TrackPlan> plan = Optional.ofNullable(trackPlanner.plan(path, heightOf(from), heightOf(to), doubled,
				openStart, openEnd, clear))
				.map(planned -> planned.withHalts(HaltPlanner.halts(planned, terrain::sample,
						(x, z) -> others.stream().anyMatch(other -> !other.key().equals(link.key())
								&& HaltPlanner.passesNear(other.path(), x, z, HaltPlanner.OTHER_LINE_CLEARANCE)))));
		return cache(link, plan);
	}

	private static int count(boolean a, boolean b) {
		return (a ? 1 : 0) + (b ? 1 : 0);
	}

	/**
	 * Whether a line's end is the {@code slot} of a western station whose second track beside it is free, so the
	 * line can come in there as a double track.
	 */
	private boolean isWesternEnd(Link station, Point end, StationLayout.Slot slot, Link line) {
		if (!station.trunk()) {
			return false;
		}
		StationLayout.Station layout = StationLayout.of(station.path(), villageOf(station.from()));
		Point outer = layout.end(slot == StationLayout.Slot.FIRST_END ? StationLayout.Slot.OUTER_FIRST
				: StationLayout.Slot.OUTER_SECOND);
		return layout.end(slot).equals(end) && network.planner().links().stream().noneMatch(other -> other != line
				&& (other.path().getFirst().equals(outer) || other.path().getLast().equals(outer)));
	}

	private Village villageOf(long id) {
		return network.planner().villages().stream().filter(village -> village.id() == id).findFirst().orElseThrow();
	}

	/** How far round its path a track builds: a line's second rail beside it, a western station's house. */
	private int reachOf(Link link) {
		if (network.planner().isStation(link.key())) {
			return link.trunk() ? WESTERN_REACH : BuildState.REACH;
		}
		return BuildState.REACH + TrackPlanner.DOUBLE_TRACK;
	}

	/** Everywhere farther than {@value #SINGLE_NEAR_VILLAGE} blocks from the footprints of the given villages. */
	private static TrackPlanner.Area awayFrom(NetworkPlanner planner, List<Long> ids) {
		List<Village> ends = planner.villages().stream().filter(village -> ids.contains(village.id())).toList();
		return (x, z) -> ends.stream().noneMatch(village -> x >= village.minX() - SINGLE_NEAR_VILLAGE
				&& x <= village.maxX() + SINGLE_NEAR_VILLAGE && z >= village.minZ() - SINGLE_NEAR_VILLAGE
				&& z <= village.maxZ() + SINGLE_NEAR_VILLAGE);
	}

	/** A new line may come in at the middle of its stations, whose plans then need their switch. */
	private void forgetStationPlans(Link link) {
		for (long village : new long[] {link.from(), link.to()}) {
			Link station = network.planner().stationOf(village);
			if (station != null && !station.key().equals(link.key())) {
				plans.remove(station.key());
			}
		}
	}

	private Optional<TrackPlan> cache(Link link, Optional<TrackPlan> plan) {
		Optional<TrackPlan> previous = plans.putIfAbsent(link.key(), plan);
		return previous != null ? previous : plan;
	}

	/** The rail height of a station: on the ground where the middle of its track stands. */
	private int heightOf(Link station) {
		return trackPlanner.stationHeight(station.path().get(1));
	}

	/**
	 * The plan of a station track, built only as far as its lines need. With lines at both ends it runs straight
	 * through, and a line meeting its middle comes in through a switch there, the station building on the other side.
	 * With a line at the middle and at most one end it is a bend from that end, or the first one, into the line, and
	 * the other half is left out. Without a line at the middle the building stands there as a halt.
	 */
	private TrackPlan stationPlan(Link station) {
		List<Point> track = station.path();
		if (station.trunk()) {
			// A western station: its track and second track straight through, the house beside them.
			int height = heightOf(station);
			List<Point> second = StationLayout.of(track, villageOf(station.from())).secondTrack();
			return trackPlanner.planStation(track, height)
					.withSeconds(List.of(trackPlanner.planStationSecond(second, height)))
					.withWestern(true);
		}
		Point middle = track.get(1);
		List<Link> lines = network.planner().links();
		Point branch = null;
		boolean firstUsed = false;
		boolean secondUsed = false;
		for (Link line : lines) {
			List<Point> path = line.path();
			for (int end : new int[] {0, path.size() - 1}) {
				Point at = path.get(end);
				firstUsed |= at.equals(track.get(0));
				secondUsed |= at.equals(track.get(2));
				if (at.equals(middle)) {
					branch = path.get(end == 0 ? 1 : path.size() - 2);
				}
			}
		}
		List<Point> laid = track;
		if (branch != null && !(firstUsed && secondUsed)) {
			Point step = new Point(middle.x() + Integer.signum(branch.x() - middle.x()),
					middle.z() + Integer.signum(branch.z() - middle.z()));
			laid = List.of(secondUsed ? track.get(2) : track.get(0), middle, step);
		}
		TrackPlan plan = trackPlanner.planStation(laid, heightOf(station));
		int at = 0;
		while (plan.x()[at] != middle.x() || plan.z()[at] != middle.z()) {
			at++;
		}
		if (branch == null) {
			return plan.withHalts(new int[] {at});
		}
		Direction right = plan.direction(at, at - 1).getClockWise();
		boolean fromRight = Integer.signum(branch.x() - middle.x()) == right.getStepX()
				&& Integer.signum(branch.z() - middle.z()) == right.getStepZ();
		return plan.withJunctions(new int[] {2 * at + (fromRight ? 1 : 0)});
	}

	private void finish(VillagePair key, Link.Status status) {
		wallsStale = true;
		network.planner().setStatus(key, status);
		buildState.finish(key);
		lines.remove(key);
		plans.remove(key);
	}

	public void save() {
		try {
			network.save();
			buildState.save();
		} catch (IOException e) {
			VillageRailways.LOGGER.error("Could not save the railway network", e);
		}
	}

	public void stop() {
		planner.shutdownNow();
		try {
			planner.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		save();
	}
}
