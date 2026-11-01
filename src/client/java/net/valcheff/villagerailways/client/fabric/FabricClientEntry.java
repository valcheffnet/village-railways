package net.valcheff.villagerailways.client.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.client.ClientPlatform;
import net.valcheff.villagerailways.client.RailwayConfigScreen;

/** The client part of the mod on Fabric, for singleplayer and for a config screen that reads the server's settings. */
public class FabricClientEntry implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlatform.set(new ClientPlatform.Services() {
			@Override
			public boolean canSend(ResourceLocation channel) {
				return ClientPlayNetworking.canSend(channel);
			}

			@Override
			public void send(ConfigSync.Message message) {
				FriendlyByteBuf buf = PacketByteBufs.create();
				message.write(buf);
				ClientPlayNetworking.send(message.id(), buf);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(ConfigSync.State.ID, (client, handler, buf, sender) -> {
			ConfigSync.State state = ConfigSync.State.read(buf);
			client.execute(() -> {
				if (client.screen instanceof RailwayConfigScreen screen) {
					screen.show(state);
				}
			});
		});
	}
}
