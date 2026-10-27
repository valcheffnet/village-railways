package net.valcheff.villagerailways.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.valcheff.villagerailways.VillageRailways;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Builds railway lines into each chunk right after its features, so trees and structures are already in place. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
	@Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
	private void villageRailways$buildLines(WorldGenLevel level, ChunkAccess chunk, StructureManager structures,
			CallbackInfo info) {
		VillageRailways.decorate(level, chunk.getPos());
	}
}
