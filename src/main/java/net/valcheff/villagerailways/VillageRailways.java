package net.valcheff.villagerailways;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.valcheff.villagerailways.command.VillageRailwaysCommand;
import net.valcheff.villagerailways.village.VillagePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageRailways implements ModInitializer {
	public static final String MOD_ID = "village_railways";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<ResourceKey<Level>, VillagePredictor> PREDICTORS = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				VillageRailwaysCommand.register(dispatcher));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PREDICTORS.clear());
		LOGGER.info("Village Railways loaded");
	}

	/** The village predictor of {@code level}, created on first use. */
	public static VillagePredictor predictor(ServerLevel level) {
		return PREDICTORS.computeIfAbsent(level.dimension(), key -> new VillagePredictor(level));
	}
}
