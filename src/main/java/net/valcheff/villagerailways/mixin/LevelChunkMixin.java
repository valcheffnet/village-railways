package net.valcheff.villagerailways.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.valcheff.villagerailways.build.QuietWrites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While lines are built into a loaded chunk, the blocks written there do not react to being placed or removed, as the
 * newer versions' write flag has it: rails keep the shape and power they are laid with, and a replaced container does
 * not spill. A removed block entity still goes, which the removed block's own reaction would otherwise do.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@WrapWithCondition(method = "setBlockState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;onRemove(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
	private boolean villageRailways$quietRemove(BlockState removed, Level level, BlockPos pos, BlockState placed,
			boolean moving) {
		if (!QuietWrites.active()) {
			return true;
		}
		if (removed.hasBlockEntity() && !removed.is(placed.getBlock())) {
			level.removeBlockEntity(pos);
		}
		return false;
	}

	@WrapWithCondition(method = "setBlockState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
	private boolean villageRailways$quietPlace(BlockState placed, Level level, BlockPos pos, BlockState removed,
			boolean moving) {
		return !QuietWrites.active();
	}
}
