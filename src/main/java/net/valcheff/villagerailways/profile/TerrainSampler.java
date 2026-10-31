package net.valcheff.villagerailways.profile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Reads the terrain of a block column from the generator's noise, so the chunk need not exist. The noise is the
 * terrain before caves, ravines and surface features are carved in. On this game version each lookup sets up the
 * noise anew, which costs more than working out the column, so the column is worked out once and both heights read
 * from it, as the game's own height lookups would find them.
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
		NoiseColumn column = generator.getBaseColumn(x, z, level, randomState);
		// As Heightmap.Types.OCEAN_FLOOR_WG and WORLD_SURFACE_WG: the first free y over the top block that blocks
		// motion, and over the top block that is not air, or the bottom of the world.
		int ground = level.getMinBuildHeight();
		int surface = level.getMinBuildHeight();
		boolean surfaceFound = false;
		for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
			BlockState state = column.getBlock(y);
			if (!surfaceFound && !state.isAir()) {
				surface = y + 1;
				surfaceFound = true;
			}
			if (state.blocksMotion()) {
				ground = y + 1;
				break;
			}
		}
		return new Column(ground, surface > ground ? surface : HeightProfile.DRY);
	}
}
