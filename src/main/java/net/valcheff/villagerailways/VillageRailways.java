package net.valcheff.villagerailways;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.valcheff.villagerailways.command.VillageRailwaysCommand;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.structure.StructurePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageRailways implements ModInitializer {
	public static final String MOD_ID = "village_railways";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<ResourceKey<Level>, StructurePredictor> PREDICTORS = new ConcurrentHashMap<>();
	private static volatile RailwayNetwork network;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				VillageRailwaysCommand.register(dispatcher));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PREDICTORS.clear();
			network = null;
		});
		LOGGER.info("Village Railways loaded");
	}

	/** The village predictor of {@code level}, created on first use. */
	public static StructurePredictor villagePredictor(ServerLevel level) {
		return PREDICTORS.computeIfAbsent(level.dimension(), key -> StructurePredictor.villages(level));
	}

	/** The overworld railway network, loaded from the world save on first use. */
	public static synchronized RailwayNetwork network(ServerLevel overworld) {
		if (network == null) {
			try {
				network = RailwayNetwork.load(overworld, villagePredictor(overworld));
			} catch (IOException e) {
				throw new UncheckedIOException("Could not read the railway network of this world", e);
			}
		}
		return network;
	}
}
