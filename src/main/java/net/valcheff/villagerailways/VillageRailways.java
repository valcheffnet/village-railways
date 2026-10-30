package net.valcheff.villagerailways;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.valcheff.villagerailways.build.RailwayService;
import net.valcheff.villagerailways.command.SpeedDisplay;
import net.valcheff.villagerailways.command.VillageRailwaysCommand;
import net.valcheff.villagerailways.map.NetworkMaps;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.structure.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageRailways implements ModInitializer {
	public static final String MOD_ID = "village_railways";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<ResourceKey<Level>, StructurePredictor> PREDICTORS = new ConcurrentHashMap<>();
	private static volatile RailwayService service;

	@Override
	public void onInitialize() {
		ConfigSync.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				VillageRailwaysCommand.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTING.register(VillageRailways::backUpBeforeOpeningLand);
		// The overworld loads before its spawn chunks generate, so lines around spawn are planned in time.
		ServerLevelEvents.LOAD.register((server, level) -> {
			if (level.dimension() == Level.OVERWORLD) {
				try {
					long started = System.nanoTime();
					service = RailwayService.start(level, RailwayConfig.load(FabricLoader.getInstance().getConfigDir()));
					NetworkMaps.load(service.network().folder());
					LOGGER.info("Railway network ready in {} ms", (System.nanoTime() - started) / 1_000_000);
				} catch (IOException e) {
					throw new UncheckedIOException("Could not load the railway network of this world", e);
				}
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			RailwayService running = service;
			if (running != null) {
				running.tick(server.getTickCount());
			}
			SpeedDisplay.tick(server);
		});
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
			RailwayService running = service;
			if (running != null) {
				running.save();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			RailwayService running = service;
			if (running != null) {
				running.stop();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			NetworkMaps.unload();
			PREDICTORS.clear();
			service = null;
		});
		LOGGER.info("Village Railways loaded");
	}

	/**
	 * Backs up a world whose existing land opens to lines at this start, before any of it loads. Without the backup the
	 * land stays closed.
	 */
	private static void backUpBeforeOpeningLand(MinecraftServer server) {
		Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path folder = world.resolve("village_railways");
		if (!RailwayService.isOpenLandRequested(folder)) {
			return;
		}
		try {
			Path backup = WorldBackup.write(world, FabricLoader.getInstance().getGameDir().resolve("backups"));
			LOGGER.info("Backed up the world to {} ({} MB) before opening its existing land to railway lines", backup,
					Files.size(backup) / 1_000_000);
		} catch (IOException e) {
			LOGGER.error("Could not back up the world, so its existing land stays closed to railway lines", e);
			try {
				RailwayService.requestOpenLand(folder, false);
			} catch (IOException again) {
				throw new UncheckedIOException("Could not call off opening the existing land after the backup failed",
						again);
			}
		}
	}

	/** Builds the railway lines of a chunk that is generating; called from the chunk generator. */
	public static void decorate(WorldGenLevel level, ChunkPos chunk) {
		RailwayService running = service;
		if (running != null && level.getLevel().dimension() == Level.OVERWORLD) {
			running.decorate(level, chunk);
		}
	}

	/** The village predictor of {@code level}, created on first use. */
	public static StructurePredictor villagePredictor(ServerLevel level) {
		return PREDICTORS.computeIfAbsent(level.dimension(), key -> StructurePredictor.villages(level));
	}

	/** The railway service of the overworld. */
	public static RailwayService service() {
		RailwayService running = service;
		if (running == null) {
			throw new IllegalStateException("The overworld is not loaded");
		}
		return running;
	}

	/**
	 * How far the planning that generating chunks wait for has got, as planned and queued squares, or null while
	 * nothing waits; for the loading screen of a singleplayer world, whose server runs in the same game.
	 */
	public static int[] planningProgress() {
		RailwayService running = service;
		return running == null ? null : running.planningProgress();
	}

	/** The overworld railway network. */
	public static RailwayNetwork network() {
		RailwayService running = service;
		if (running == null) {
			throw new IllegalStateException("The overworld is not loaded");
		}
		return running.network();
	}
}
