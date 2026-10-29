package net.valcheff.villagerailways.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.valcheff.villagerailways.map.NetworkMaps;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places the markers on a network map that covers more than a vanilla map can at the map's real scale. The game works
 * out a marker's place from its world position, the map's centre and its vanilla scale; the position is moved so that
 * sum comes out at the real scale. Players and item frames holding a map update its markers through
 * {@code tickCarriedBy}, which tells which map it is.
 */
@Mixin(MapItemSavedData.class)
abstract class MapItemSavedDataMixin {
	@Shadow
	@Final
	public int centerX;
	@Shadow
	@Final
	public int centerZ;
	@Shadow
	@Final
	public byte scale;
	@Unique
	private int villageRailways$blocksPerPixel;

	@Inject(method = "tickCarriedBy", at = @At("HEAD"))
	private void villageRailways$findScale(Player player, ItemStack stack, ItemFrame frame, CallbackInfo info) {
		MapId id = stack.get(DataComponents.MAP_ID);
		villageRailways$blocksPerPixel = id == null ? 0 : NetworkMaps.blocksPerPixel(id);
	}

	@ModifyVariable(method = "addDecoration", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double villageRailways$markerX(double x) {
		return villageRailways$rescale(x, centerX);
	}

	@ModifyVariable(method = "addDecoration", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private double villageRailways$markerZ(double z) {
		return villageRailways$rescale(z, centerZ);
	}

	@Unique
	private double villageRailways$rescale(double position, int center) {
		if (villageRailways$blocksPerPixel <= 0) {
			return position;
		}
		return center + (position - center) * (1 << scale) / villageRailways$blocksPerPixel;
	}
}
