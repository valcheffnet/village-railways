package net.valcheff.villagerailways.fabric;

import java.nio.file.Path;
import java.util.function.Function;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.Platform;
import net.valcheff.villagerailways.VillageRailways;

/** Fabric entry point: wires Fabric's events and networking to the shared code. */
public final class FabricEntry implements ModInitializer {
	@Override
	public void onInitialize() {
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				return FabricLoader.getInstance().getConfigDir();
			}

			@Override
			public Path gameDir() {
				return FabricLoader.getInstance().getGameDir();
			}

			@Override
			public String version() {
				return FabricLoader.getInstance().getModContainer(VillageRailways.MOD_ID).orElseThrow().getMetadata()
						.getVersion().getFriendlyString();
			}
		});

		receive(ConfigSync.Request.ID, ConfigSync.Request::read,
				(server, player, request) -> ConfigSync.answerRequest(server, player));
		receive(ConfigSync.Save.ID, ConfigSync.Save::read, ConfigSync::answerSave);
		receive(ConfigSync.OpenLand.ID, ConfigSync.OpenLand::read, ConfigSync::answerOpenLand);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				VillageRailways.registerCommands(dispatcher));
		ServerLifecycleEvents.SERVER_STARTING.register(VillageRailways::serverStarting);
		ServerWorldEvents.LOAD.register((server, level) -> VillageRailways.levelLoaded(level));
		ServerTickEvents.END_SERVER_TICK.register(VillageRailways::serverTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				VillageRailways.playerJoined(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> VillageRailways.serverStopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VillageRailways.serverStopped());
		VillageRailways.LOGGER.info("Village Railways loaded");
	}

	/** A message from a client's config screen, read where it arrives and answered on the server thread. */
	private static <T> void receive(ResourceLocation id, Function<FriendlyByteBuf, T> read, Answer<T> answer) {
		ServerPlayNetworking.registerGlobalReceiver(id, (server, player, handler, buf, sender) -> {
			T message = read.apply(buf);
			server.execute(() -> {
				ConfigSync.State state = answer.answer(server, player, message);
				FriendlyByteBuf out = PacketByteBufs.create();
				state.write(out);
				sender.sendPacket(ConfigSync.State.ID, out);
			});
		});
	}

	@FunctionalInterface
	private interface Answer<T> {
		ConfigSync.State answer(MinecraftServer server, ServerPlayer player, T message);
	}
}
