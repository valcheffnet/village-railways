package net.valcheff.villagerailways;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageRailways implements ModInitializer {
	public static final String MOD_ID = "village_railways";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Village Railways loaded");
	}
}
