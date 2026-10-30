package net.valcheff.villagerailways;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.valcheff.villagerailways.build.RailwayService;
import net.valcheff.villagerailways.command.SpeedDisplay;
import net.valcheff.villagerailways.command.VillageRailwaysCommand;
import net.valcheff.villagerailways.map.NetworkMaps;
import net.valcheff.villagerailways.map.StarterMaps;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.structure.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The shared part of the mod, which the loader's entry point drives. */
public final class VillageRailways {
	public static final String MOD_ID = "village_railways";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<ResourceKey<Level>, StructurePredictor> PREDICTORS = new ConcurrentHashMap<>();
	private static volatile RailwayService service;
	/** Whether players joining the world for the first time get a map of the network, as the config said at start. */
	private static volatile boolean starterMap;

	private VillageRailways() {
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		VillageRailwaysCommand.register(dispatcher);
	}

	/** Loads the railway network with the overworld, before its spawn chunks generate, so lines there are in time. */
	public static void levelLoaded(ServerLevel level) {
		if (level.dimension() != Level.OVERWORLD) {
			return;
		}
		try {
			long started = System.nanoTime();
			RailwayConfig config = RailwayConfig.load(Platform.get().configDir());
			starterMap = config.starterMap();
			service = RailwayService.start(level, config);
			NetworkMaps.load(service.network().folder());
			LOGGER.info("Railway network ready in {} ms", (System.nanoTime() - started) / 1_000_000);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not load the railway network of this world", e);
		}
	}

	public static void serverTick(MinecraftServer server) {
		RailwayService running = service;
		if (running != null) {
			running.tick(server.getTickCount());
		}
		SpeedDisplay.tick(server);
	}

	/** Gives a player joining the world for the first time a map of the network, when the config says so. */
	public static void playerJoined(ServerPlayer player) {
		RailwayService running = service;
		if (running != null && starterMap) {
			StarterMaps.playerJoined(player, running.network());
		}
	}

	public static void beforeSave() {
		RailwayService running = service;
		if (running != null) {
			running.save();
		}
	}

	public static void serverStopping() {
		RailwayService running = service;
		if (running != null) {
			running.stop();
		}
	}

	public static void serverStopped() {
		NetworkMaps.unload();
		PREDICTORS.clear();
		service = null;
	}

	/**
	 * Backs up a world whose existing land opens to lines at this start, before any of it loads. Without the backup the
	 * land stays closed.
	 */
	public static void serverStarting(MinecraftServer server) {
		Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path folder = world.resolve("village_railways");
		if (!RailwayService.isOpenLandRequested(folder)) {
			return;
		}
		try {
			Path backup = WorldBackup.write(world, Platform.get().gameDir().resolve("backups"));
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
