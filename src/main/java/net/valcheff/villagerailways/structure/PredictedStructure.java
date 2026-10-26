package net.valcheff.villagerailways.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A structure the world generator will place, computed before its chunks exist.
 *
 * @param structure registry name, such as {@code minecraft:village_plains}
 * @param chunk     chunk that holds the structure start
 * @param center    center of the start piece (for a village, its meeting point)
 * @param bounds    box around every piece of the structure
 */
public record PredictedStructure(String structure, ChunkPos chunk, BlockPos center, BoundingBox bounds) {
}
