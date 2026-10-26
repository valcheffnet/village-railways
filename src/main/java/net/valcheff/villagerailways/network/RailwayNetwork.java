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
import net.valcheff.villagerailways.village.PredictedVillage;
import net.valcheff.villagerailways.village.VillagePredictor;

/** The railway network of one level: predicted villages in, planned lines out, saved after every change. */
public class RailwayNetwork {
	private final VillagePredictor predictor;
	private final NetworkPlanner planner;
	private final NetworkStore store;
	private final OceanFilter oceanFilter;
	private final Path folder;

	private RailwayNetwork(VillagePredictor predictor, NetworkPlanner planner, NetworkStore store, OceanFilter oceanFilter,
			Path folder) {
		this.predictor = predictor;
		this.planner = planner;
		this.store = store;
		this.oceanFilter = oceanFilter;
		this.folder = folder;
	}

	public static RailwayNetwork load(ServerLevel level, VillagePredictor predictor) throws IOException {
		Path folder = level.getServer().getWorldPath(LevelResource.ROOT).resolve("village_railways");
		NetworkStore store = new NetworkStore(folder);
		PlannerSettings settings = PlannerSettings.DEFAULT;
		return new RailwayNetwork(predictor, store.load(settings), store, new OceanFilter(level, settings.maxOceanCrossing()),
				folder);
	}

	/** Plans lines for the villages within {@code radius} blocks of {@code center}. */
	public synchronized Result explore(BlockPos center, int radius) throws IOException {
		List<PredictedVillage> predicted = predictor.villagesNear(center, radius);
		List<Village> villages = predicted.stream().map(RailwayNetwork::toVillage).toList();
		long newVillages = villages.stream().filter(village -> !planner.knows(village.id())).count();
		List<Link> added = planner.addVillages(villages, oceanFilter);
		if (newVillages > 0) {
			store.save(planner);
		}
		return new Result((int) newVillages, added.size(), planner.villages().size(), planner.links().size(),
				planner.lastRejections());
	}

	public NetworkPlanner planner() {
		return planner;
	}

	public Path folder() {
		return folder;
	}

	private static Village toVillage(PredictedVillage predicted) {
		ChunkPos chunk = predicted.chunk();
		BoundingBox bounds = predicted.bounds();
		BlockPos center = predicted.center();
		return new Village(ChunkPos.pack(chunk.x(), chunk.z()), predicted.structure(), new Point(center.getX(), center.getZ()),
				bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
	}

	public OceanFilter oceanFilter() {
		return oceanFilter;
	}

	public record Result(int newVillages, int newLinks, int villages, int links, Map<NetworkPlanner.Rejection, Integer> rejections) {
	}
}
