package net.valcheff.villagerailways.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.network.RailwayNetwork;

/**
 * Gives each player who joins a world for the first time a map of the railway network round where they arrive. A
 * player is new while the game has never seen them leave the world, so players from before the mod was added get
 * none. {@value #FILE} in the mod's folder of the save lists who got one, for a first visit that ended in a crash
 * before the game noted the leave.
 */
public final class StarterMaps {
	private static final String FILE = "starter-maps.txt";

	private StarterMaps() {
	}

	public static void playerJoined(ServerPlayer player, RailwayNetwork network) {
		if (player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) > 0) {
			return;
		}
		Path file = network.folder().resolve(FILE);
		String id = player.getUUID().toString();
		try {
			if (Files.exists(file) && Files.readAllLines(file).contains(id)) {
				return;
			}
			ItemStack map = NetworkMapItem.create(player.level().getServer().overworld(), network.planner(),
					player.getBlockX(), player.getBlockZ(), NetworkMapItem.DEFAULT_RADIUS);
			if (!player.addItem(map)) {
				VillageRailways.LOGGER.info("No room for a map of the railway network in {}'s inventory",
						player.getName().getString());
				return;
			}
			Files.createDirectories(network.folder());
			Files.writeString(file, id + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			VillageRailways.LOGGER.warn("Could not give {} a map of the railway network", player.getName().getString(),
					e);
		}
	}
}
