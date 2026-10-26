package net.valcheff.villagerailways.network;

import java.util.List;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.valcheff.villagerailways.structure.PredictedStructure;
import net.valcheff.villagerailways.structure.StructurePredictor;

/**
 * Turns down paths that run through a surface structure other than a village: outposts, temples, ruined portals,
 * igloos, mansions and the like, from the predictor, so the structures need not exist yet.
 */
public class StructureFilter implements NetworkPlanner.PathFilter {
	/** How far, in chunks, a structure's pieces may reach out of its start chunk. */
	private static final int REACH_CHUNKS = 8;

	private final StructurePredictor obstacles;
	private final int margin;

	public StructureFilter(StructurePredictor obstacles, int margin) {
		this.obstacles = obstacles;
		this.margin = margin;
	}

	/** Every obstacle whose start lies in the given block area. */
	public List<PredictedStructure> obstaclesIn(int minX, int minZ, int maxX, int maxZ) {
		return obstacles.candidateChunks(minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4).stream()
				.flatMap(chunk -> obstacles.predictAt(chunk).stream())
				.toList();
	}

	@Override
	public boolean accepts(List<Point> path) {
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Point point : path) {
			minX = Math.min(minX, point.x());
			minZ = Math.min(minZ, point.z());
			maxX = Math.max(maxX, point.x());
			maxZ = Math.max(maxZ, point.z());
		}
		List<ChunkPos> candidates = obstacles.candidateChunks((minX >> 4) - REACH_CHUNKS, (minZ >> 4) - REACH_CHUNKS,
				(maxX >> 4) + REACH_CHUNKS, (maxZ >> 4) + REACH_CHUNKS);
		for (ChunkPos chunk : candidates) {
			for (PredictedStructure structure : obstacles.predictAt(chunk)) {
				if (crosses(path, structure.bounds())) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean crosses(List<Point> path, BoundingBox bounds) {
		for (int i = 1; i < path.size(); i++) {
			Point a = path.get(i - 1);
			Point b = path.get(i);
			if (Math.max(a.x(), b.x()) >= bounds.minX() - margin && Math.min(a.x(), b.x()) <= bounds.maxX() + margin
					&& Math.max(a.z(), b.z()) >= bounds.minZ() - margin && Math.min(a.z(), b.z()) <= bounds.maxZ() + margin) {
				return true;
			}
		}
		return false;
	}
}
