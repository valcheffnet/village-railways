package net.valcheff.villagerailways.mixin;

import net.minecraft.server.MinecraftServer;
import net.valcheff.villagerailways.VillageRailways;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Saves the railway state whenever the server saves its worlds; this version's loaders have no event for that. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	@Inject(method = "saveAllChunks", at = @At("HEAD"))
	private void villageRailways$save(boolean quiet, boolean flush, boolean force,
			CallbackInfoReturnable<Boolean> info) {
		VillageRailways.beforeSave();
	}
}
