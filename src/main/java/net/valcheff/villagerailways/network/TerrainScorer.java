package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.valcheff.villagerailways.profile.HeightProfile;
import net.valcheff.villagerailways.profile.TerrainSampler;

/**
 * Ranks the possible routes of a line by the terrain they cross, the way a railway would be laid out by hand.
 *
 * <p>Corners cost on their own, and a corner over water or down in a hollow costs a lot: that is where a curve would
 * stand on a bridge. Along the way, every stretch of water costs a little and every block of valley below the land on
 * both sides of it costs more, so a route that keeps to the higher ground beside a dip wins over one through it.
 * Terrain is read every {@value #STEP} blocks from the generator's noise, on a grid shared by all routes.
 */
public class TerrainScorer implements NetworkPlanner.RouteScorer {
	private static final int STEP = 16;
	private static final double CORNER = 120;
	private static final double CORNER_IN_WATER = 400;
	private static final double CORNER_DIP_PER_BLOCK = 40;
	private static final double WATER_PER_SAMPLE = 6;
	private static final double VALLEY_PER_BLOCK = 2;
	/** Samples on each side of a point that count as its surroundings: 48 blocks. */
	private static final int REACH = 3;
	/** The cache is dropped when it grows past this many columns; it only has to cover the lines being planned. */
	private static final int CACHE_LIMIT = 200_000;

	private final TerrainSampler terrain;
	private final Map<Long, TerrainSampler.Column> cache = new ConcurrentHashMap<>();

	public TerrainScorer(ServerLevel level) {
		this.terrain = new TerrainSampler(level);
	}

	@Override
	public double score(List<Point> path) {
		List<TerrainSampler.Column> samples = new ArrayList<>();
		List<Integer> cornerSamples = new ArrayList<>();
		for (int i = 1; i < path.size(); i++) {
			Point from = path.get(i - 1);
			Point to = path.get(i);
			int length = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
			for (int step = 0; step < length; step += STEP) {
				samples.add(column(from.x() + Integer.signum(to.x() - from.x()) * step,
						from.z() + Integer.signum(to.z() - from.z()) * step));
			}
			if (i < path.size() - 1) {
				cornerSamples.add(samples.size());
				samples.add(column(to.x(), to.z()));
			}
		}
		samples.add(column(path.get(path.size() - 1).x(), path.get(path.size() - 1).z()));

		double score = CORNER * cornerSamples.size();
		for (int i = 0; i < samples.size(); i++) {
			if (samples.get(i).water() != HeightProfile.DRY) {
				score += WATER_PER_SAMPLE;
			}
			score += VALLEY_PER_BLOCK * Math.max(0, valleyDepth(samples, i) - 3);
		}
		for (int corner : cornerSamples) {
			TerrainSampler.Column column = samples.get(corner);
			if (column.water() != HeightProfile.DRY) {
				score += CORNER_IN_WATER;
			}
			score += CORNER_DIP_PER_BLOCK * Math.max(0, valleyDepth(samples, corner) - 1);
		}
		return score;
	}

	/** How far a point lies below the land on both sides of it: the lower of the highest ground before and after. */
	private static int valleyDepth(List<TerrainSampler.Column> samples, int index) {
		int before = Integer.MIN_VALUE;
		int after = Integer.MIN_VALUE;
		for (int i = Math.max(0, index - REACH); i < index; i++) {
			before = Math.max(before, samples.get(i).ground());
		}
		for (int i = index + 1; i <= Math.min(samples.size() - 1, index + REACH); i++) {
			after = Math.max(after, samples.get(i).ground());
		}
		if (before == Integer.MIN_VALUE || after == Integer.MIN_VALUE) {
			return 0;
		}
		return Math.min(before, after) - samples.get(index).ground();
	}

	/** Terrain at the nearest point of an 8-block grid, so routes that run close together share their samples. */
	private TerrainSampler.Column column(int x, int z) {
		if (cache.size() > CACHE_LIMIT) {
			cache.clear();
		}
		int gridX = Math.floorDiv(x + 4, 8) * 8;
		int gridZ = Math.floorDiv(z + 4, 8) * 8;
		return cache.computeIfAbsent(ChunkPos.asLong(gridX, gridZ), key -> terrain.sample(gridX, gridZ));
	}
}
