package net.valcheff.villagerailways;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.valcheff.villagerailways.build.RailwayService;
import net.valcheff.villagerailways.profile.TrackSettings;

/**
 * Lets the config screen of a player who has the mod read the settings of the server they play on, and an operator,
 * or the player hosting a singleplayer world, change them. Changes are written to the server's config file and apply
 * as the file does, from the next start. Players without the mod never see any of this.
 */
public final class ConfigSync {
	private static final StreamCodec<ByteBuf, List<Integer>> INTS = ByteBufCodecs.VAR_INT
			.apply(ByteBufCodecs.list());

	private ConfigSync() {
	}

	/** The config screen asks for the server's settings. */
	public record Request() implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRailways.MOD_ID,
				"config_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());

		@Override
		public Type<Request> type() {
			return TYPE;
		}
	}

	/**
	 * The server's settings in {@link RailwayConfig.Setting} order, the cut depth, pillar spacing and sea tubes (1 or
	 * 0) its world keeps, whether its existing land opens to lines at the next start, and whether the player may
	 * change the settings.
	 */
	public record State(List<Integer> values, List<Integer> world, boolean openLand, boolean canEdit)
			implements CustomPacketPayload {
		public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRailways.MOD_ID,
				"config_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(INTS,
				State::values, INTS, State::world, ByteBufCodecs.BOOL, State::openLand, ByteBufCodecs.BOOL,
				State::canEdit, State::new);

		@Override
		public Type<State> type() {
			return TYPE;
		}
	}

	/** New settings from the config screen, in {@link RailwayConfig.Setting} order. */
	public record Save(List<Integer> values) implements CustomPacketPayload {
		public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRailways.MOD_ID,
				"config_save"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = StreamCodec.composite(INTS,
				Save::values, Save::new);

		@Override
		public Type<Save> type() {
			return TYPE;
		}
	}

	/** The config screen asks for the world's existing land to open to lines at the next start, or takes that back. */
	public record OpenLand(boolean open) implements CustomPacketPayload {
		public static final Type<OpenLand> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRailways.MOD_ID,
				"open_land"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenLand> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, OpenLand::open, OpenLand::new);

		@Override
		public Type<OpenLand> type() {
			return TYPE;
		}
	}

	/** The settings for a player who asked for them. */
	public static State answerRequest(MinecraftServer server, ServerPlayer player) {
		return state(server, player);
	}

	/** Saves new settings when the player may change them, and answers with the settings as they are now. */
	public static State answerSave(MinecraftServer server, ServerPlayer player, Save save) {
		if (canEdit(server, player)) {
			RailwayConfig.of(save.values()).save(Platform.get().configDir());
			VillageRailways.LOGGER.info("{} changed the Village Railways settings; they apply from the next start",
					player.getName().getString());
		}
		return state(server, player);
	}

	/** Asks for the existing land to open at the next start, or takes that back, when the player may. */
	public static State answerOpenLand(MinecraftServer server, ServerPlayer player, OpenLand request) {
		if (canEdit(server, player)) {
			try {
				RailwayService.requestOpenLand(folder(server), request.open());
				VillageRailways.LOGGER.info("{} {} opening the existing land to railway lines at the next start",
						player.getName().getString(), request.open() ? "asked for" : "called off");
			} catch (IOException e) {
				VillageRailways.LOGGER.warn("Could not ask for the existing land to open to lines", e);
			}
		}
		return state(server, player);
	}

	private static Path folder(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("village_railways");
	}

	private static State state(MinecraftServer server, ServerPlayer player) {
		RailwayConfig config = RailwayConfig.load(Platform.get().configDir());
		Path folder = folder(server);
		TrackSettings kept = RailwayConfig.keptBy(folder);
		return new State(config.values(), List.of(kept.cutDepth(), kept.pillarSpacing(), kept.seaTubes() ? 1 : 0),
				RailwayService.isOpenLandRequested(folder), canEdit(server, player));
	}

	static boolean canEdit(MinecraftServer server, ServerPlayer player) {
		return server.isSingleplayerOwner(player.getGameProfile())
				|| player.hasPermissions(Commands.LEVEL_ADMINS);
	}
}
