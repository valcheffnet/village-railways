package net.valcheff.villagerailways.forge;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.Platform;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.client.forge.ForgeClientEntry;

/** Forge entry point: wires Forge's events and networking to the shared code. */
@Mod(VillageRailways.MOD_ID)
public final class ForgeEntry {
	private static final String PROTOCOL = "1";
	/** The config screen's channel. A client or server without the mod has none, and joins all the same. */
	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			new ResourceLocation(VillageRailways.MOD_ID, "config"), () -> PROTOCOL,
			NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

	/**
	 * What a client does with the server's settings. The channel takes its handlers where it is made, here on both
	 * sides, so the client entry point sets this to keep client classes out of a server.
	 */
	private static volatile Consumer<ConfigSync.State> clientState = state -> {
	};

	public ForgeEntry() {
		String version = ModList.get().getModContainerById(VillageRailways.MOD_ID).orElseThrow().getModInfo()
				.getVersion().toString();
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				return FMLPaths.CONFIGDIR.get();
			}

			@Override
			public Path gameDir() {
				return FMLPaths.GAMEDIR.get();
			}

			@Override
			public String version() {
				return version;
			}
		});

		toServer(0, ConfigSync.Request.class, ConfigSync.Request::read,
				(player, request) -> ConfigSync.answerRequest(player.server, player));
		toServer(1, ConfigSync.Save.class, ConfigSync.Save::read,
				(player, save) -> ConfigSync.answerSave(player.server, player, save));
		toServer(2, ConfigSync.OpenLand.class, ConfigSync.OpenLand::read,
				(player, open) -> ConfigSync.answerOpenLand(player.server, player, open));
		CHANNEL.messageBuilder(ConfigSync.State.class, 3, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(ConfigSync.State::write).decoder(ConfigSync.State::read)
				.consumerMainThread((state, context) -> clientState.accept(state)).add();

		IEventBus bus = MinecraftForge.EVENT_BUS;
		bus.addListener((RegisterCommandsEvent event) -> VillageRailways.registerCommands(event.getDispatcher()));
		// Before the levels load, so a world that opens its existing land is backed up as it was.
		bus.addListener((ServerAboutToStartEvent event) -> VillageRailways.serverStarting(event.getServer()));
		bus.addListener((LevelEvent.Load event) -> {
			if (event.getLevel() instanceof ServerLevel level) {
				VillageRailways.levelLoaded(level);
			}
		});
		bus.addListener((TickEvent.ServerTickEvent event) -> {
			if (event.phase == TickEvent.Phase.END) {
				VillageRailways.serverTick(event.getServer());
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
			if (event.getEntity() instanceof ServerPlayer player) {
				VillageRailways.playerJoined(player);
			}
		});
		bus.addListener((ServerStoppingEvent event) -> VillageRailways.serverStopping());
		bus.addListener((ServerStoppedEvent event) -> VillageRailways.serverStopped());
		if (FMLEnvironment.dist == Dist.CLIENT) {
			ForgeClientEntry.init();
		}
		VillageRailways.LOGGER.info("Village Railways loaded");
	}

	public static void onClientState(Consumer<ConfigSync.State> handler) {
		clientState = handler;
	}

	/** A message from a client's config screen, answered with the settings as they are then. */
	private static <T extends ConfigSync.Message> void toServer(int index, Class<T> type,
			Function<FriendlyByteBuf, T> read, BiFunction<ServerPlayer, T, ConfigSync.State> answer) {
		CHANNEL.messageBuilder(type, index, NetworkDirection.PLAY_TO_SERVER)
				.encoder(ConfigSync.Message::write).decoder(read)
				.consumerMainThread((T message, Supplier<NetworkEvent.Context> context) -> {
					ServerPlayer player = context.get().getSender();
					if (player != null) {
						CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), answer.apply(player, message));
					}
				}).add();
	}
}
