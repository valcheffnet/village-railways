package net.valcheff.villagerailways.fabric;

import java.nio.file.Path;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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

		PayloadTypeRegistry.playC2S().register(ConfigSync.Request.TYPE, ConfigSync.Request.CODEC);
		PayloadTypeRegistry.playC2S().register(ConfigSync.Save.TYPE, ConfigSync.Save.CODEC);
		PayloadTypeRegistry.playC2S().register(ConfigSync.OpenLand.TYPE, ConfigSync.OpenLand.CODEC);
		PayloadTypeRegistry.playS2C().register(ConfigSync.State.TYPE, ConfigSync.State.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ConfigSync.Request.TYPE, (payload, context) -> context
				.responseSender().sendPacket(ConfigSync.answerRequest(context.server(), context.player())));
		ServerPlayNetworking.registerGlobalReceiver(ConfigSync.Save.TYPE, (payload, context) -> context
				.responseSender().sendPacket(ConfigSync.answerSave(context.server(), context.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(ConfigSync.OpenLand.TYPE, (payload, context) -> context
				.responseSender().sendPacket(ConfigSync.answerOpenLand(context.server(), context.player(), payload)));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				VillageRailways.registerCommands(dispatcher));
		ServerLifecycleEvents.SERVER_STARTING.register(VillageRailways::serverStarting);
		ServerWorldEvents.LOAD.register((server, level) -> VillageRailways.levelLoaded(level));
		ServerTickEvents.END_SERVER_TICK.register(VillageRailways::serverTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				VillageRailways.playerJoined(handler.getPlayer()));
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> VillageRailways.beforeSave());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> VillageRailways.serverStopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VillageRailways.serverStopped());
		VillageRailways.LOGGER.info("Village Railways loaded");
	}
}
