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
			StructureFilter structureFilter, Path folder) {
		this.predictor = predictor;
		this.planner = planner;
		this.store = store;
		this.oceanFilter = oceanFilter;
		this.structureFilter = structureFilter;
		// Ocean first: it is the cheaper check.
		this.pathFilter = path -> oceanFilter.accepts(path) && structureFilter.accepts(path);
		this.folder = folder;
	}

	public static RailwayNetwork load(ServerLevel level, StructurePredictor predictor) throws IOException {
		Path folder = level.getServer().getWorldPath(LevelResource.ROOT).resolve("village_railways");
		NetworkStore store = new NetworkStore(folder);
		PlannerSettings settings = PlannerSettings.DEFAULT;
		return new RailwayNetwork(predictor, store.load(settings), store, new OceanFilter(level, settings.maxOceanCrossing()),
				new StructureFilter(StructurePredictor.surfaceObstacles(level), settings.structureMargin()), folder);
	}

	/** Plans lines for the villages whose start lies in the square of {@code radius} blocks around {@code center}. */
	public synchronized Result explore(BlockPos center, int radius) throws IOException {
		int minX = center.getX() - radius;
		int minZ = center.getZ() - radius;
		int maxX = center.getX() + radius;
		int maxZ = center.getZ() + radius;
		List<PredictedStructure> predicted = predictor.candidateChunks(minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4).stream()
				.flatMap(chunk -> predictor.predictAt(chunk).stream())
				.toList();
		List<Village> villages = predicted.stream().map(RailwayNetwork::toVillage).toList();
		long newVillages = villages.stream().filter(village -> !planner.knows(village.id())).count();
		// Whole start chunks only: the square's edge chunks may hold starts just outside it.
		planner.markExplored((minX >> 4) * 16, (minZ >> 4) * 16, (maxX >> 4) * 16 + 15, (maxZ >> 4) * 16 + 15);
		List<Link> added = planner.addVillages(villages, pathFilter);
		store.save(planner);
		return new Result((int) newVillages, added.size(), planner.villages().size(), planner.links().size(),
				planner.lastRejections());
	}

	public NetworkPlanner planner() {
		return planner;
	}

	public Path folder() {
		return folder;
	}

	private static Village toVillage(PredictedStructure predicted) {
		ChunkPos chunk = predicted.chunk();
		BoundingBox bounds = predicted.bounds();
		BlockPos center = predicted.center();
		return new Village(ChunkPos.pack(chunk.x(), chunk.z()), predicted.structure(), new Point(center.getX(), center.getZ()),
				bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
	}

	public StructureFilter structureFilter() {
		return structureFilter;
	}

	public OceanFilter oceanFilter() {
		return oceanFilter;
	}

	public record Result(int newVillages, int newLinks, int villages, int links, Map<NetworkPlanner.Rejection, Integer> rejections) {
	}
}
