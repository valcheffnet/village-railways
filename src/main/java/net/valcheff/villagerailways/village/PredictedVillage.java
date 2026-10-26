package net.valcheff.villagerailways.village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A village the world generator will place, computed before its chunks exist.
 *
 * @param structure registry name of the village structure, such as {@code minecraft:village_plains}
 * @param chunk     chunk that holds the structure start
 * @param center    center of the start piece (the village meeting point)
 * @param bounds    box around every piece of the village
 */
public record PredictedVillage(String structure, ChunkPos chunk, BlockPos center, BoundingBox bounds) {
}
