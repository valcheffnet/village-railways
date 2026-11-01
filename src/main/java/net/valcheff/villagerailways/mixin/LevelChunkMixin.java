package net.valcheff.villagerailways.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.valcheff.villagerailways.build.QuietWrites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While lines are built into a loaded chunk, the blocks written there do not react to being placed or removed, as the
 * newer versions' write flag has it: rails keep the shape and power they are laid with, and a replaced container does
 * not spill. A removed block entity still goes, which the removed block's own reaction would otherwise do. Plain
 * redirects, since Forge 47 does not ship the mixin extras the newer versions use for this.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Redirect(method = "setBlockState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;onRemove(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
	private void villageRailways$quietRemove(BlockState removed, Level level, BlockPos pos, BlockState placed,
			boolean moving) {
		if (!QuietWrites.active()) {
			removed.onRemove(level, pos, placed, moving);
		} else if (removed.hasBlockEntity() && !removed.is(placed.getBlock())) {
			level.removeBlockEntity(pos);
		}
	}

	@Redirect(method = "setBlockState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;onPlace(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
	private void villageRailways$quietPlace(BlockState placed, Level level, BlockPos pos, BlockState removed,
			boolean moving) {
		if (!QuietWrites.active()) {
			placed.onPlace(level, pos, removed, moving);
		}
	}
}
