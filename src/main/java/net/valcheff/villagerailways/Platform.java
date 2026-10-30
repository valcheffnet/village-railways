package net.valcheff.villagerailways;

import java.nio.file.Path;

/** What the mod needs from the loader (Fabric or NeoForge). The loader's entry point sets it before anything else. */
public final class Platform {
	public interface Services {
		Path configDir();

		/** The game's or the server's own folder, where the world backups go. */
		Path gameDir();
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
