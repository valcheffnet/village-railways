package net.valcheff.villagerailways.client.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.client.ClientPlatform;
import net.valcheff.villagerailways.client.RailwayConfigScreen;

/** The client part of the mod on Fabric, for singleplayer and for a config screen that reads the server's settings. */
public class FabricClientEntry implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlatform.set(new ClientPlatform.Services() {
			@Override
			public boolean canSend(CustomPacketPayload.Type<?> type) {
				return ClientPlayNetworking.canSend(type);
			}

			@Override
			public void send(CustomPacketPayload payload) {
				ClientPlayNetworking.send(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(ConfigSync.State.TYPE, (state, context) -> {
			if (context.client().gui.screen() instanceof RailwayConfigScreen screen) {
				screen.show(state);
			}
		});
	}
}
