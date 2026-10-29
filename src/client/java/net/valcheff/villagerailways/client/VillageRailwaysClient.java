package net.valcheff.villagerailways.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.valcheff.villagerailways.ConfigSync;

/** The client part of the mod, for singleplayer and for a config screen that reads the server's settings. */
public class VillageRailwaysClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(ConfigSync.State.TYPE, (state, context) -> {
			if (context.client().gui.screen() instanceof RailwayConfigScreen screen) {
				screen.show(state);
			}
		});
	}
}
