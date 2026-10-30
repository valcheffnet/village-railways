package net.valcheff.villagerailways.client.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.client.ClientPlatform;
import net.valcheff.villagerailways.client.RailwayConfigScreen;

/** NeoForge client entry point: the settings screen, which reads and saves the server's settings. */
@Mod(value = VillageRailways.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClientEntry {
	public NeoForgeClientEntry(IEventBus modBus, ModContainer container) {
		ClientPlatform.set(new ClientPlatform.Services() {
			@Override
			public boolean canSend(CustomPacketPayload.Type<?> type) {
				ClientPacketListener connection = Minecraft.getInstance().getConnection();
				return connection != null && connection.hasChannel(type);
			}

			@Override
			public void send(CustomPacketPayload payload) {
				ClientPacketDistributor.sendToServer(payload);
			}
		});
		// The Config button in NeoForge's mod list opens the settings.
		container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new RailwayConfigScreen(parent));
		modBus.addListener(RegisterClientPayloadHandlersEvent.class, event ->
				event.register(ConfigSync.State.TYPE, (state, context) -> {
					if (Minecraft.getInstance().screen instanceof RailwayConfigScreen screen) {
						screen.show(state);
					}
				}));
	}
}
