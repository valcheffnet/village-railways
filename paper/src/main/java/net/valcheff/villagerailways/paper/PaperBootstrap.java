package net.valcheff.villagerailways.paper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;

/** Runs before the server loads its data: the station structures are in the jar as a data pack, enabled at start. */
public final class PaperBootstrap implements PluginBootstrap {
	@Override
	public void bootstrap(BootstrapContext context) {
		context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
			URL pack = getClass().getResource("/datapack");
			if (pack == null) {
				context.getLogger().error("The data pack is missing from the jar");
				return;
			}
			try {
				URI uri = pack.toURI();
				event.registrar().discoverPack(uri, "data", configurer -> configurer
						.title(Component.text("Village Railways")).autoEnableOnServerStart(true));
			} catch (URISyntaxException | IOException e) {
				context.getLogger().error("Could not add the data pack", e);
			}
		});
	}
}
