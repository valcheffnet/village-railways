package net.valcheff.villagerailways.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.network.Link;
import net.valcheff.villagerailways.network.NetworkPlanner;
import net.valcheff.villagerailways.network.NetworkPlanner.VillagePair;
import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.network.Village;
import net.valcheff.villagerailways.profile.TerrainSampler;
import net.valcheff.villagerailways.profile.TrackPlan;
import net.valcheff.villagerailways.profile.TrackPlanner;
import net.valcheff.villagerailways.structure.StructurePredictor;

/**
 * Runs the railway network of the overworld: plans lines ahead of the players on a background thread and builds them
 * into chunks as the chunks generate.
 *
 * <p>The land is explored in squares of {@value #SQUARE} blocks. Around spawn the squares are planned before the
 * first chunk generates; after that every player keeps the squares within {@value #PLAN_AHEAD} squares planned, which
 * is far beyond view distance, so lines, trunk lines included, are in place before their chunks are made. Villages
 * at the edge of what is planned get their lines to newly found villages as the planned area grows.
 */
public class RailwayService {
	private static final int SQUARE = 1024;
	/** Squares planned around each player: three reach past the longest trunk line plus view distance. */
	private static final int PLAN_AHEAD = 3;
	private static final int SPAWN_SQUARES = 2;
	/** Ticks players may spend in a chunk before it is closed to new lines: two minutes. */
	private static final int LIVED_TICKS = 2400;
	private static final int LIVED_CHECK_INTERVAL = 40;
	/** Chunks around each player checked for time spent, beyond the simulation distance. */
	private static final int LIVED_RADIUS = 12;
	/** Chunks given their late lines per tick, so a burst does not stall the server. */
	private static final int LATER_PER_TICK = 2;
	/**
	 * How far round its villages a trunk line stays single: past the area where lines find their way out to their
	 * stations, with the other lines of the village close by.
	 */
	private static final int SINGLE_NEAR_VILLAGE = 32;

	private final ServerLevel level;
	private final RailwayNetwork network;
	private final BuildState buildState;
	private final TrackPlanner trackPlanner;
	private final TerrainSampler terrain;
	private final Map<VillagePair, Link> lines = new ConcurrentHashMap<>();
	private final Map<VillagePair, Optional<TrackPlan>> plans = new ConcurrentHashMap<>();
	private final Set<Long> queuedSquares = ConcurrentHashMap.newKeySet();
	private final ExecutorService planner = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Village Railways planner");
		thread.setDaemon(true);
		return thread;
	});

	private RailwayService(ServerLevel level, RailwayNetwork network, BuildState buildState) {
		this.level = level;
		this.network = network;
		this.buildState = buildState;
		this.trackPlanner = new TrackPlanner(level);
		this.terrain = new TerrainSampler(level);
	}

	/** Loads the network and building state of the overworld and plans the land around the world origin. */
	public static RailwayService start(ServerLevel overworld) throws IOException {
		Path root = overworld.getServer().getWorldPath(LevelResource.ROOT);
		Path folder = root.resolve("village_railways");
		BuildState buildState = BuildState.load(folder, DimensionType.getStorageFolder(Level.OVERWORLD, root).resolve("region"));
		RailwayNetwork network = RailwayNetwork.load(overworld, StructurePredictor.villages(overworld),
				path -> !buildState.touchesClosed(path));
		RailwayService service = new RailwayService(overworld, network, buildState);
		List<Link> tracks = new ArrayList<>(network.planner().links());
		tracks.addAll(network.planner().stations());
		for (Link link : tracks) {
			if (link.status() == Link.Status.PLANNED) {
				service.lines.put(link.key(), link);
				buildState.register(link, true);
			}
		}
		// New worlds put spawn near the origin; plan it before any chunk there generates.
		for (int squareX = -SPAWN_SQUARES; squareX < SPAWN_SQUARES; squareX++) {
			for (int squareZ = -SPAWN_SQUARES; squareZ < SPAWN_SQUARES; squareZ++) {
				service.planSquare(squareX, squareZ);
			}
		}
		return service;
	}

	public RailwayNetwork network() {
		return network;
	}

	/** Queues planning for the squares around every player that are not planned yet. */
	public void planAroundPlayers() {
		for (ServerPlayer player : level.players()) {
			int squareX = Math.floorDiv(player.getBlockX(), SQUARE);
			int squareZ = Math.floorDiv(player.getBlockZ(), SQUARE);
			for (int dx = -PLAN_AHEAD; dx <= PLAN_AHEAD; dx++) {
				for (int dz = -PLAN_AHEAD; dz <= PLAN_AHEAD; dz++) {
					int x = squareX + dx;
					int z = squareZ + dz;
					if (queuedSquares.add(ChunkPos.pack(x, z))) {
						planner.execute(() -> planSquare(x, z));
					}
				}
			}
		}
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
		queuedSquares.add(ChunkPos.pack(squareX, squareZ));
		BlockPos center = new BlockPos(squareX * SQUARE + SQUARE / 2, 0, squareZ * SQUARE + SQUARE / 2);
		try {
			RailwayNetwork.Result result = network.explore(center, SQUARE / 2 - 1);
			for (Link link : result.added()) {
				forgetStationPlans(link);
				if (buildState.register(link, false)) {
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
				LineBuilder.build(level, chunk, toBuild, loaded,
						(x, z) -> loaded.test(x, z) && buildState.isDecorated(x, z));
			}
			for (VillagePair key : entry.getValue()) {
				if (buildState.isComplete(key)) {
					finish(key, Link.Status.BUILT);
				}
			}
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

	/** Builds every line that runs through a chunk; called once per chunk, while it generates. */
	public void decorate(WorldGenLevel region, ChunkPos chunk) {
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
		LineBuilder.build(region, chunk, toBuild, neighbours,
				(x, z) -> neighbours.test(x, z) && buildState.isDecorated(x, z));
		for (VillagePair key : built) {
			if (buildState.isComplete(key)) {
				finish(key, Link.Status.BUILT);
			}
		}
	}

	/**
	 * The block-by-block plan of a line or a station track, made once. A line runs from the rail height of one of its
	 * stations to the other's, with its halts; a station track lies level. A trunk line is a double track away from
	 * its villages.
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
		TrackPlanner.Area doubled = link.trunk() ? awayFrom(planner, link.from(), link.to()) : null;
		Optional<TrackPlan> plan = Optional.ofNullable(trackPlanner.plan(link.path(), heightOf(from), heightOf(to), doubled))
				.map(planned -> planned.withHalts(HaltPlanner.halts(planned, terrain::sample,
						(x, z) -> others.stream().anyMatch(other -> !other.key().equals(link.key())
								&& HaltPlanner.passesNear(other.path(), x, z, HaltPlanner.OTHER_LINE_CLEARANCE)))));
		return cache(link, plan);
	}

	/** Everywhere farther than {@value #SINGLE_NEAR_VILLAGE} blocks from the footprints of two villages. */
	private static TrackPlanner.Area awayFrom(NetworkPlanner planner, long first, long second) {
		List<Village> ends = planner.villages().stream().filter(village -> village.id() == first || village.id() == second)
				.toList();
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
		network.planner().setStatus(key, status);
		buildState.forget(key);
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
