package net.valcheff.villagerailways.command;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

/** Shows players who turned it on how fast their minecart goes, above the hotbar. */
public final class SpeedDisplay {
	private static final int INTERVAL = 5;
	/**
	 * Where each watching player's minecart was at the last update. A minecart's motion can grow past what it really
	 * moves in a tick, so the speed comes from the distance covered.
	 */
	private static final Map<UUID, Vec3> LAST_POSITION = new ConcurrentHashMap<>();
	private static final Vec3 NOT_RIDING = new Vec3(Double.NaN, Double.NaN, Double.NaN);

	private SpeedDisplay() {
	}

	/** Turns the display on or off for a player; true when it is now on. */
	public static boolean toggle(ServerPlayer player) {
		if (LAST_POSITION.remove(player.getUUID()) != null) {
			return false;
		}
		LAST_POSITION.put(player.getUUID(), NOT_RIDING);
		return true;
	}

	public static void tick(MinecraftServer server) {
		if (LAST_POSITION.isEmpty() || server.getTickCount() % INTERVAL != 0) {
			return;
		}
		for (Map.Entry<UUID, Vec3> entry : LAST_POSITION.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			Entity vehicle = player.getVehicle();
			if (!(vehicle instanceof AbstractMinecart)) {
				entry.setValue(NOT_RIDING);
				continue;
			}
			Vec3 now = vehicle.position();
			Vec3 before = entry.getValue();
			entry.setValue(now);
			if (before != NOT_RIDING) {
				// A tick is a twentieth of a second.
				double speed = now.distanceTo(before) * 20 / INTERVAL;
				player.displayClientMessage(Component.literal(String.format(Locale.ROOT, "%.1f blocks/s (%.0f km/h)",
						speed, speed * 3.6)), true);
			}
		}
	}
}
