package net.valcheff.villagerailways.client;

import net.minecraft.resources.ResourceLocation;
import net.valcheff.villagerailways.ConfigSync;

/** Client networking from the loader (Fabric or Forge), set by its client entry point. */
public final class ClientPlatform {
	public interface Services {
		/** Whether the server listens on this channel: it runs the mod. */
		boolean canSend(ResourceLocation channel);

		void send(ConfigSync.Message message);
	}

	private static Services services;

	private ClientPlatform() {
	}

	public static void set(Services services) {
		ClientPlatform.services = services;
	}

	public static Services get() {
		if (services == null) {
			throw new IllegalStateException("Village Railways: the client entry point has not run");
		}
		return services;
	}
}
