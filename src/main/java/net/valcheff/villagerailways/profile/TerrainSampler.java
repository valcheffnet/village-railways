package net.valcheff.villagerailways.profile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Reads the terrain of a block column from the generator's noise, so the chunk need not exist. The noise is the
 * terrain before caves, ravines and surface features are carved in. Two height lookups, each stopping at the first
 * block from the top, are far cheaper than building the whole column.
 */
public class TerrainSampler {
	/**
	 * One column.
	 *
	 * @param ground first free y above solid ground
	 * @param water  first free y above water, or {@link HeightProfile#DRY}
	 */
	public record Column(int ground, int water) {
	}

	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final RandomState randomState;

	public TerrainSampler(ServerLevel level) {
		this.level = level;
		this.generator = level.getChunkSource().getGenerator();
		this.randomState = level.getChunkSource().randomState();
	}

	public Column sample(int x, int z) {
		int ground = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
		int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
		return new Column(ground, surface > ground ? surface : HeightProfile.DRY);
	}
}
