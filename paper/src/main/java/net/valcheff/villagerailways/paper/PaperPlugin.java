package net.valcheff.villagerailways.paper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.valcheff.villagerailways.Platform;
import net.valcheff.villagerailways.VillageRailways;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.generator.CraftLimitedRegion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper entry point. It loads before the worlds, so it backs up a world that opens its existing land before any of it
 * loads, and starts the railway network as the overworld is set up, before its spawn generates. A block populator
 * stands in for the chunk generator mixin, and Paper's events for the loader's.
 */
public final class PaperPlugin extends JavaPlugin implements Listener {
	@Override
	public void onEnable() {
		Path configDir = getDataFolder().toPath();
		Path serverDir = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				try {
					return Files.createDirectories(configDir);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}

			@Override
			public Path gameDir() {
				return serverDir;
			}
		});
		VillageRailways.serverStarting(MinecraftServer.getServer());
		getServer().getPluginManager().registerEvents(this, this);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> registerCommands(event.registrar()));
		VillageRailways.LOGGER.info("Village Railways loaded");
	}

	/**
	 * Paper disables plugins before it saves the worlds, so the railway state is saved here, while its classes can
	 * still load.
	 */
	@Override
	public void onDisable() {
		VillageRailways.serverStopping();
		VillageRailways.serverStopped();
	}

	@EventHandler
	public void onWorldInit(WorldInitEvent event) {
		ServerLevel level = ((CraftWorld) event.getWorld()).getHandle();
		if (level.dimension() != Level.OVERWORLD) {
			return;
		}
		VillageRailways.levelLoaded(level);
		event.getWorld().getPopulators().add(new BlockPopulator() {
			@Override
			public void populate(WorldInfo world, Random random, int chunkX, int chunkZ, LimitedRegion region) {
				VillageRailways.decorate(((CraftLimitedRegion) region).getHandle(), new ChunkPos(chunkX, chunkZ));
			}
		});
	}

	@EventHandler
	public void onWorldSave(WorldSaveEvent event) {
		if (((CraftWorld) event.getWorld()).getHandle().dimension() == Level.OVERWORLD) {
			VillageRailways.beforeSave();
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		VillageRailways.playerJoined(((CraftPlayer) event.getPlayer()).getHandle());
	}

	@EventHandler
	public void onTickEnd(ServerTickEndEvent event) {
		VillageRailways.serverTick(MinecraftServer.getServer());
	}

	/**
	 * The commands are built on vanilla's command source, which is what Paper's command source is underneath, so the
	 * same tree is handed to Paper's registrar.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void registerCommands(Commands registrar) {
		CommandDispatcher<CommandSourceStack> built = new CommandDispatcher<>();
		VillageRailways.registerCommands(built);
		for (CommandNode<CommandSourceStack> node : built.getRoot().getChildren()) {
			if (node instanceof LiteralCommandNode<CommandSourceStack> literal) {
				registrar.register((LiteralCommandNode) literal, "Village Railways");
			}
		}
	}
}
