package net.valcheff.villagerailways;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
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
	private ConfigSync() {
	}

	/** A message between the config screen and the server: the channel it goes on and how it is written. */
	public interface Message {
		ResourceLocation id();

		void write(FriendlyByteBuf buf);
	}

	/** The config screen asks for the server's settings. */
	public record Request() implements Message {
		public static final ResourceLocation ID = new ResourceLocation(VillageRailways.MOD_ID, "config_request");

		public static Request read(FriendlyByteBuf buf) {
			return new Request();
		}

		@Override
		public ResourceLocation id() {
			return ID;
		}

		@Override
		public void write(FriendlyByteBuf buf) {
		}
	}

	/**
	 * The server's settings in {@link RailwayConfig.Setting} order, the cut depth, pillar spacing and sea tubes (1 or
	 * 0) its world keeps, whether its existing land opens to lines at the next start, and whether the player may
	 * change the settings.
	 */
	public record State(List<Integer> values, List<Integer> world, boolean openLand, boolean canEdit) implements Message {
		public static final ResourceLocation ID = new ResourceLocation(VillageRailways.MOD_ID, "config_state");

		public static State read(FriendlyByteBuf buf) {
			return new State(buf.readList(FriendlyByteBuf::readVarInt), buf.readList(FriendlyByteBuf::readVarInt),
					buf.readBoolean(), buf.readBoolean());
		}

		@Override
		public ResourceLocation id() {
			return ID;
		}

		@Override
		public void write(FriendlyByteBuf buf) {
			buf.writeCollection(values, FriendlyByteBuf::writeVarInt);
			buf.writeCollection(world, FriendlyByteBuf::writeVarInt);
			buf.writeBoolean(openLand);
			buf.writeBoolean(canEdit);
		}
	}

	/** New settings from the config screen, in {@link RailwayConfig.Setting} order. */
	public record Save(List<Integer> values) implements Message {
		public static final ResourceLocation ID = new ResourceLocation(VillageRailways.MOD_ID, "config_save");

		public static Save read(FriendlyByteBuf buf) {
			return new Save(buf.readList(FriendlyByteBuf::readVarInt));
		}

		@Override
		public ResourceLocation id() {
			return ID;
		}

		@Override
		public void write(FriendlyByteBuf buf) {
			buf.writeCollection(values, FriendlyByteBuf::writeVarInt);
		}
	}

	/** The config screen asks for the world's existing land to open to lines at the next start, or takes that back. */
	public record OpenLand(boolean open) implements Message {
		public static final ResourceLocation ID = new ResourceLocation(VillageRailways.MOD_ID, "open_land");

		public static OpenLand read(FriendlyByteBuf buf) {
			return new OpenLand(buf.readBoolean());
		}

		@Override
		public ResourceLocation id() {
			return ID;
		}

		@Override
		public void write(FriendlyByteBuf buf) {
			buf.writeBoolean(open);
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
