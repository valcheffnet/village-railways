package net.valcheff.villagerailways.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client networking from the loader (Fabric or NeoForge), set by its client entry point. */
public final class ClientPlatform {
	public interface Services {
		/** Whether the server listens on this channel: it runs the mod. */
		boolean canSend(CustomPacketPayload.Type<?> type);

		void send(CustomPacketPayload payload);
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
