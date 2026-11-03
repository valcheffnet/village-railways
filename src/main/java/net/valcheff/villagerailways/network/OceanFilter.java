package net.valcheff.villagerailways.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Turns down lines that would cross open ocean, in a world without sea tubes. A narrow strait of at most
 * {@code maxCrossing} blocks passes and gets a bridge, like rivers and lakes. It also tells ocean from land for the
 * network map. The biome is read from the generator's noise at sea level every {@value #STEP}
 * blocks along the path (the biome grid is 4 blocks), so no chunk has to exist.
 */
public class OceanFilter implements NetworkPlanner.PathFilter {
	private static final int STEP = 4;

	private final BiomeSource biomes;
	private final Climate.Sampler sampler;
	private final int quartSeaLevel;
	private final int maxCrossing;

	public OceanFilter(ServerLevel level, int maxCrossing) {
		this.maxCrossing = maxCrossing;
		this.sampler = level.getChunkSource().randomState().sampler();
		this.biomes = level.getChunkSource().getGenerator().getBiomeSource();
		this.quartSeaLevel = QuartPos.fromBlock(level.getChunkSource().getGenerator().getSeaLevel());
	}

	@Override
	public boolean accepts(List<Point> path) {
		List<Point> samples = new ArrayList<>();
		for (int i = 1; i < path.size(); i++) {
			Point from = path.get(i - 1);
			Point to = path.get(i);
			int length = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
			for (int step = 0; step <= length; step += STEP) {
				samples.add(new Point(from.x() + Integer.signum(to.x() - from.x()) * step,
						from.z() + Integer.signum(to.z() - from.z()) * step));
			}
		}
		// Each biome lookup works the noise out anew; the samples of a long route are read on several threads.
		List<Boolean> ocean = samples.parallelStream().map(point -> isOcean(point.x(), point.z())).toList();
		int oceanRun = 0;
		for (boolean sample : ocean) {
			oceanRun = sample ? oceanRun + STEP : 0;
			if (oceanRun > maxCrossing) {
				return false;
			}
		}
		return true;
	}

	public boolean isOcean(int x, int z) {
		return biomes.getNoiseBiome(QuartPos.fromBlock(x), quartSeaLevel, QuartPos.fromBlock(z), sampler)
				.is(BiomeTags.IS_OCEAN);
	}
}
