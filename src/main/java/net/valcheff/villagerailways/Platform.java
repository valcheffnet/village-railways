package net.valcheff.villagerailways;

import java.nio.file.Path;

import net.minecraft.world.level.WorldGenLevel;

/**
 * What the mod needs from the loader (Fabric, NeoForge or Paper). The loader's entry point sets it before anything
 * else.
 */
public final class Platform {
	public interface Services {
		Path configDir();

		/** The game's or the server's own folder, where the world backups go. */
		Path gameDir();

		/** This mod's version, as in its jar's name: 1.0.0+26.3. */
		String version();

		/** Whether this is the Paper plugin, whose downloads are on Hangar rather than CurseForge. */
		default boolean paper() {
			return false;
		}

		/**
		 * Runs a build into {@code level} so that the blocks it removes there do not react to it. Fabric and NeoForge
		 * hook into the chunk for that; the Paper plugin, which cannot, has the server skip the reactions itself.
		 */
		default void quietly(WorldGenLevel level, Runnable build) {
			build.run();
		}
	}

	private static Services services;

	private Platform() {
	}

	public static void set(Services services) {
		Platform.services = services;
	}

	public static Services get() {
		if (services == null) {
			throw new IllegalStateException("Village Railways: the loader entry point has not run");
		}
		return services;
	}
}
