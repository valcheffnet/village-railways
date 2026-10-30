package net.valcheff.villagerailways.network;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;
import net.valcheff.villagerailways.profile.HeightProfile;
import net.valcheff.villagerailways.structure.PredictedStructure;
import net.valcheff.villagerailways.structure.StructurePredictor;

/** The railway network of one level: predicted villages in, planned lines out, saved after every change. */
public class RailwayNetwork {
	private final StructurePredictor predictor;
	private final NetworkPlanner planner;
	private final NetworkStore store;
	private final OceanFilter oceanFilter;
	private final StructureFilter structureFilter;
	private final NetworkPlanner.PathFilter pathFilter;
	private final Path folder;

	private RailwayNetwork(StructurePredictor predictor, NetworkPlanner planner, NetworkStore store, OceanFilter oceanFilter,
			boolean seaTubes, StructureFilter structureFilter, NetworkPlanner.PathFilter buildable, Path folder) {
		this.predictor = predictor;
		this.planner = planner;
		this.store = store;
		this.oceanFilter = oceanFilter;
		this.structureFilter = structureFilter;
		// Cheapest checks first.
		this.pathFilter = path -> buildable.accepts(path) && (seaTubes || oceanFilter.accepts(path))
				&& structureFilter.accepts(path);
		this.folder = folder;
	}

	/**
	 * Loads the network of a level from its save.
	 *
	 * @param buildable turns down paths that can no longer be built, such as ones through chunks that already exist
	 * @param settings  the rules for lines planned from now on
	 * @param seaTubes  whether lines cross seas in tubes; without them they keep off open ocean
	 */
	public static RailwayNetwork load(ServerLevel level, StructurePredictor predictor, NetworkPlanner.PathFilter buildable,
			PlannerSettings settings, boolean seaTubes) throws IOException {
		Path folder = level.getServer().getWorldPath(LevelResource.ROOT).resolve("village_railways");
		NetworkStore store = new NetworkStore(folder);
		NetworkPlanner planner = store.load(settings);
		planner.setRouteScorer(new TerrainScorer(level));
		return new RailwayNetwork(predictor, planner, store, new OceanFilter(level, HeightProfile.MAX_BRIDGE), seaTubes,
				new StructureFilter(StructurePredictor.surfaceObstacles(level), settings.structureMargin()), buildable, folder);
	}

	/**
	 * Plans lines for the villages whose start lies in the square of {@code radius} blocks around {@code center}. A
	 * square that is explored already is skipped.
	 */
	public synchronized Result explore(BlockPos center, int radius) throws IOException {
		// Whole start chunks only: the square's edge chunks may hold starts just outside it.
		int minX = ((center.getX() - radius) >> 4) * 16;
		int minZ = ((center.getZ() - radius) >> 4) * 16;
		int maxX = ((center.getX() + radius) >> 4) * 16 + 15;
		int maxZ = ((center.getZ() + radius) >> 4) * 16 + 15;
		if (planner.isAreaExplored(minX, minZ, maxX, maxZ)) {
			return new Result(0, List.of(), planner.villages().size(), planner.links().size(), Map.of());
		}
		List<PredictedStructure> predicted = predictor.candidateChunks(minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4).stream()
				.flatMap(chunk -> predictor.predictAt(chunk).stream())
				.toList();
		List<Village> villages = predicted.stream().map(RailwayNetwork::toVillage).toList();
		long newVillages = villages.stream().filter(village -> !planner.knows(village.id())).count();
		planner.markExplored(minX, minZ, maxX, maxZ);
		List<Link> added = planner.addVillages(villages, pathFilter);
		store.save(planner);
		return new Result((int) newVillages, added, planner.villages().size(), planner.links().size(),
				planner.lastRejections());
	}

	/** Plans every line the known villages could still get, in land just opened to lines, and saves the network. */
	public synchronized List<Link> retryAll() throws IOException {
		List<Link> added = planner.retryAll(pathFilter);
		store.save(planner);
		return added;
	}

	public synchronized void save() throws IOException {
		store.save(planner);
	}

	public NetworkPlanner planner() {
		return planner;
	}

	public Path folder() {
		return folder;
	}

	public StructureFilter structureFilter() {
		return structureFilter;
	}

	public OceanFilter oceanFilter() {
		return oceanFilter;
	}

	private static Village toVillage(PredictedStructure predicted) {
		ChunkPos chunk = predicted.chunk();
		BoundingBox bounds = predicted.bounds();
		BlockPos center = predicted.center();
		List<Box> buildings = predicted.buildings().stream()
				.map(box -> new Box(box.minX(), box.minZ(), box.maxX(), box.maxZ()))
				.toList();
		return new Village(ChunkPos.asLong(chunk.x, chunk.z), predicted.structure(), new Point(center.getX(), center.getZ()),
				bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), buildings);
	}

	public record Result(int newVillages, List<Link> added, int villages, int links,
			Map<NetworkPlanner.Rejection, Integer> rejections) {
		public int newLinks() {
			return added.size();
		}
	}
}
