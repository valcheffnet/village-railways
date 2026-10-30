package net.valcheff.villagerailways.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.valcheff.villagerailways.Platform;
import net.valcheff.villagerailways.RailwayConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the player choose on the World tab of the create world screen whether the new world's railway lines cross seas
 * in tubes. The choice starts as the config has it and is kept by the world from its first start, like the config's
 * own value would be. It is laid out like the tab's own switches: the label on the left, the button on the right.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
abstract class CreateWorldTabMixin extends GridLayoutTab {
	/** As wide as the tab's other rows, under its switches, with a button as wide as theirs. */
	private static final int WIDTH = 310;
	private static final int BUTTON_WIDTH = 44;
	private static final int ROW = 3;

	private CreateWorldTabMixin(Component title) {
		super(title);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void villageRailways$addSeaTubes(CreateWorldScreen screen, CallbackInfo info) {
		boolean configured = RailwayConfig.load(Platform.get().configDir())
				.get(RailwayConfig.Setting.SEA_TUBES) == 1;
		RailwayConfig.chooseNewWorldSeaTubes(configured);
		Component label = Component.literal("Railway Sea Tubes");
		// Given to the builder, not the button: a cycle button sets its tooltip again on every click.
		Tooltip tooltip = Tooltip.create(Component.literal("Village Railways lines cross seas in glass tubes under "
				+ "water. Off, they keep off open sea and bridge only narrow straits. The world keeps this from its "
				+ "first start."));
		CycleButton<Boolean> button = CycleButton.onOffBuilder(configured).displayOnlyValue()
				.withTooltip(on -> tooltip)
				.create(0, 0, BUTTON_WIDTH, 20, label, (cycle, on) -> RailwayConfig.chooseNewWorldSeaTubes(on));
		GridLayout row = new GridLayout();
		row.addChild(SpacerElement.width(WIDTH - BUTTON_WIDTH), 0, 0);
		row.addChild(SpacerElement.width(BUTTON_WIDTH), 0, 1);
		row.addChild(new StringWidget(label, Minecraft.getInstance().font), 1, 0,
				row.newCellSettings().align(0, 0.5f));
		row.addChild(button, 1, 1, row.newCellSettings().alignHorizontallyRight());
		layout.addChild(row, ROW, 0, 1, 2);
	}
}
