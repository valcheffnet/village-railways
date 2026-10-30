package net.valcheff.villagerailways.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.valcheff.villagerailways.VillageRailways;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows under the loading screen of a singleplayer world how far the railway planning has got while the land waits
 * for it, as it does at the first start of a world, so the wait does not look like a hang.
 */
@Mixin(LevelLoadingScreen.class)
abstract class LevelLoadingScreenMixin extends Screen {
	private LevelLoadingScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void villageRailways$showPlanning(GuiGraphics graphics, int mouseX, int mouseY, float delta,
			CallbackInfo info) {
		int[] progress = VillageRailways.planningProgress();
		if (progress != null) {
			graphics.drawCenteredString(font, Component.literal("Planning railway lines: " + progress[0] + " of "
					+ progress[1] + " areas"), width / 2, height - 30, 0xFFFFFFFF);
		}
	}
}
