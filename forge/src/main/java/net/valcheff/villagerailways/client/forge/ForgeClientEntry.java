package net.valcheff.villagerailways.client.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.client.ClientPlatform;
import net.valcheff.villagerailways.client.RailwayConfigScreen;
import net.valcheff.villagerailways.forge.ForgeEntry;

/** Forge client part: the settings screen, which reads and saves the server's settings. */
public final class ForgeClientEntry {
	private ForgeClientEntry() {
	}

	public static void init() {
		ClientPlatform.set(new ClientPlatform.Services() {
			@Override
			public boolean canSend(ResourceLocation channel) {
				ClientPacketListener connection = Minecraft.getInstance().getConnection();
				return connection != null && ForgeEntry.CHANNEL.isRemotePresent(connection.getConnection());
			}

			@Override
			public void send(ConfigSync.Message message) {
				ForgeEntry.CHANNEL.sendToServer(message);
			}
		});
		// The Config button in Forge's mod list opens the settings.
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new RailwayConfigScreen(parent)));
		ForgeEntry.onClientState(state -> {
			if (Minecraft.getInstance().screen instanceof RailwayConfigScreen screen) {
				screen.show(state);
			}
		});
	}
}
