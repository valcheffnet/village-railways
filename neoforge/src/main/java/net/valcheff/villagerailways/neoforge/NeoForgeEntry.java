package net.valcheff.villagerailways.neoforge;

import java.nio.file.Path;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.Platform;
import net.valcheff.villagerailways.VillageRailways;

/** NeoForge entry point: wires NeoForge's events and networking to the shared code. */
@Mod(VillageRailways.MOD_ID)
public final class NeoForgeEntry {
	public NeoForgeEntry(IEventBus modBus) {
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				return FMLPaths.CONFIGDIR.get();
			}

			@Override
			public Path gameDir() {
				return FMLPaths.GAMEDIR.get();
			}
		});

		// Optional channels: a vanilla client, or one without the mod, joins without them.
		modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
			PayloadRegistrar registrar = event.registrar("1").optional();
			registrar.playToServer(ConfigSync.Request.TYPE, ConfigSync.Request.CODEC, (payload, context) -> {
				if (context.player() instanceof ServerPlayer player) {
					context.reply(ConfigSync.answerRequest(player.level().getServer(), player));
				}
			});
			registrar.playToServer(ConfigSync.Save.TYPE, ConfigSync.Save.CODEC, (payload, context) -> {
				if (context.player() instanceof ServerPlayer player) {
					context.reply(ConfigSync.answerSave(player.level().getServer(), player, payload));
				}
			});
			registrar.playToServer(ConfigSync.OpenLand.TYPE, ConfigSync.OpenLand.CODEC, (payload, context) -> {
				if (context.player() instanceof ServerPlayer player) {
					context.reply(ConfigSync.answerOpenLand(player.level().getServer(), player, payload));
				}
			});
			registrar.playToClient(ConfigSync.State.TYPE, ConfigSync.State.CODEC);
		});

		IEventBus bus = NeoForge.EVENT_BUS;
		bus.addListener(RegisterCommandsEvent.class, event -> VillageRailways.registerCommands(event.getDispatcher()));
		// Before the levels load, so a world that opens its existing land is backed up as it was.
		bus.addListener(ServerAboutToStartEvent.class, event -> VillageRailways.serverStarting(event.getServer()));
		bus.addListener(LevelEvent.Load.class, event -> {
			if (event.getLevel() instanceof ServerLevel level) {
				VillageRailways.levelLoaded(level);
			}
		});
		bus.addListener(ServerTickEvent.Post.class, event -> VillageRailways.serverTick(event.getServer()));
		bus.addListener(LevelEvent.Save.class, event -> {
			if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
				VillageRailways.beforeSave();
			}
		});
		bus.addListener(ServerStoppingEvent.class, event -> VillageRailways.serverStopping());
		bus.addListener(ServerStoppedEvent.class, event -> VillageRailways.serverStopped());
		VillageRailways.LOGGER.info("Village Railways loaded");
	}
}
