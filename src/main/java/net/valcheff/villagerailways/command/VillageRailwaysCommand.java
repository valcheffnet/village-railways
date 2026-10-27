package net.valcheff.villagerailways.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.network.Link;
import net.valcheff.villagerailways.network.NetworkMap;
import net.valcheff.villagerailways.network.NetworkPlanner;
import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.profile.ProfileMap;
import net.valcheff.villagerailways.profile.Section;
import net.valcheff.villagerailways.profile.TrackPlan;
import net.valcheff.villagerailways.structure.PredictedStructure;
import net.valcheff.villagerailways.structure.StructurePredictor;

/**
 * Operator tools for checking the mod in a running world.
 *
 * <ul>
 *   <li>{@code /villagerailways villages [radius]} lists the predicted villages around the caller.</li>
 *   <li>{@code /villagerailways verify [radius]} generates every candidate chunk up to structure starts and compares
 *       the real village starts with the prediction. Meant for test worlds: it creates chunks.</li>
 *   <li>{@code /villagerailways network [radius]} plans lines for the villages around the caller and draws the whole
 *       network to {@code village_railways/network.png} in the world folder.</li>
 *   <li>{@code /villagerailways profile [n]} lays out the n-th nearest planned line block by block and draws its height
 *       profile to {@code village_railways/profile.png}.</li>
 *   <li>{@code /villagerailways build [n]} generates every chunk along the n-th nearest line, which builds it, and
 *       checks that a rail lies on every block of it. Meant for test worlds.</li>
 *   <li>{@code /villagerailways speed} turns on or off a minecart speed readout above the caller's hotbar.</li>
 * </ul>
 */
public final class VillageRailwaysCommand {
	private static final int DEFAULT_RADIUS = 1000;

	private VillageRailwaysCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("villagerailways")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(literal("villages")
						.executes(context -> listVillages(context, DEFAULT_RADIUS))
						.then(argument("radius", IntegerArgumentType.integer(16, 20000))
								.executes(context -> listVillages(context, IntegerArgumentType.getInteger(context, "radius")))))
				.then(literal("verify")
						.executes(context -> verify(context, DEFAULT_RADIUS))
						.then(argument("radius", IntegerArgumentType.integer(16, 5000))
								.executes(context -> verify(context, IntegerArgumentType.getInteger(context, "radius")))))
				.then(literal("profile")
						.executes(context -> profile(context, 1))
						.then(argument("n", IntegerArgumentType.integer(1, 100))
								.executes(context -> profile(context, IntegerArgumentType.getInteger(context, "n")))))
				.then(literal("speed")
						.executes(context -> {
							boolean on = SpeedDisplay.toggle(context.getSource().getPlayerOrException());
							context.getSource().sendSuccess(() -> Component.literal("Minecart speed display " + (on ? "on" : "off")),
									false);
							return on ? 1 : 0;
						}))
				.then(literal("build")
						.executes(context -> build(context, 1, false))
						.then(argument("n", IntegerArgumentType.integer(1, 100))
								.executes(context -> build(context, IntegerArgumentType.getInteger(context, "n"), false))))
				.then(literal("station")
						.executes(context -> build(context, 1, true))
						.then(argument("n", IntegerArgumentType.integer(1, 100))
								.executes(context -> build(context, IntegerArgumentType.getInteger(context, "n"), true))))
				.then(literal("network")
						.executes(context -> plan(context, DEFAULT_RADIUS))
						.then(argument("radius", IntegerArgumentType.integer(16, 20000))
								.executes(context -> plan(context, IntegerArgumentType.getInteger(context, "radius"))))));
	}

	/** Plans the area on the planner thread, so the server keeps ticking, then draws the map and reports. */
	private static int plan(CommandContext<CommandSourceStack> context, int radius) {
		CommandSourceStack source = context.getSource();
		RailwayNetwork network = VillageRailways.network();
		long started = System.nanoTime();
		source.sendSuccess(() -> Component.literal("Planning " + radius + " blocks around you..."), false);
		BlockPos origin = BlockPos.containing(source.getPosition());
		VillageRailways.service().planArea(origin, radius, () -> {
			long millis = (System.nanoTime() - started) / 1_000_000;
			NetworkMap.ObstacleSource obstacles = (minX, minZ, maxX, maxZ) -> network.structureFilter()
					.obstaclesIn(minX, minZ, maxX, maxZ).stream()
					.map(structure -> new NetworkMap.Obstacle(structure.structure(), structure.bounds().minX(),
							structure.bounds().minZ(), structure.bounds().maxX(), structure.bounds().maxZ()))
					.toList();
			Component message;
			try {
				Path map = NetworkMap.write(network.planner(), new Point(origin.getX(), origin.getZ()), radius,
						network.oceanFilter()::isOcean, obstacles, network.folder().resolve("network.png"));
				long lines = network.planner().links().size();
				message = Component.literal("Planned in " + millis + " ms. Network: " + network.planner().villages().size()
						+ " villages, " + lines + " lines. Map: " + map.toAbsolutePath());
			} catch (IOException e) {
				VillageRailways.LOGGER.error("Could not draw the railway network", e);
				message = Component.literal("Could not draw the map: " + e.getMessage());
			}
			Component reply = message;
			source.getServer().executeIfPossible(() -> source.sendSuccess(() -> reply, false));
		});
		return 1;
	}

	private static int profile(CommandContext<CommandSourceStack> context, int n) {
		CommandSourceStack source = context.getSource();
		ServerLevel overworld = source.getServer().overworld();
		RailwayNetwork network = VillageRailways.network();
		Point origin = new Point((int) source.getPosition().x, (int) source.getPosition().z);
		List<Link> nearest = network.planner().links().stream()
				.sorted(Comparator.comparingLong(link -> distanceSq(link, origin)))
				.limit(n)
				.toList();
		if (nearest.size() < n) {
			source.sendFailure(Component.literal("Only " + nearest.size() + " lines planned; run /villagerailways network first"));
			return 0;
		}
		Link link = nearest.getLast();
		long started = System.nanoTime();
		TrackPlan plan = VillageRailways.service().planOf(link).orElse(null);
		long millis = (System.nanoTime() - started) / 1_000_000;
		if (plan == null) {
			source.sendFailure(Component.literal("No height profile fits the line " + describe(link)));
			return 0;
		}
		Map<Section, Long> counts = Arrays.stream(plan.sections())
				.collect(Collectors.groupingBy(section -> section, () -> new EnumMap<>(Section.class), Collectors.counting()));
		int ramps = 0;
		for (int i = 1; i < plan.length(); i++) {
			ramps += plan.track()[i] != plan.track()[i - 1] ? 1 : 0;
		}
		int rampBlocks = ramps;
		try {
			Path file = ProfileMap.write(plan, describe(link), network.folder().resolve("profile.png"));
			source.sendSuccess(() -> Component.literal(describe(link) + ": " + plan.length() + " blocks, y " + plan.track()[0]
					+ " to " + plan.track()[plan.length() - 1] + ", " + rampBlocks + " ramp blocks, " + counts + " (" + millis
					+ " ms). Profile: " + file.toAbsolutePath()), false);
			return plan.length();
		} catch (IOException e) {
			source.sendFailure(Component.literal("Could not write the profile: " + e.getMessage()));
			return 0;
		}
	}

	/** Builds the {@code n}th nearest line, or station track, and reports how it came out. */
	private static int build(CommandContext<CommandSourceStack> context, int n, boolean station) {
		CommandSourceStack source = context.getSource();
		ServerLevel overworld = source.getServer().overworld();
		Link link = nthNearest(source, n, station);
		if (link == null) {
			source.sendFailure(Component.literal("Fewer than " + n + (station ? " stations" : " lines") + " planned"));
			return 0;
		}
		TrackPlan plan = VillageRailways.service().planOf(link).orElse(null);
		if (plan == null) {
			source.sendFailure(Component.literal("No height profile fits the line " + describe(link)));
			return 0;
		}
		long started = System.nanoTime();
		Set<Long> chunks = new LinkedHashSet<>();
		for (int i = 0; i < plan.length(); i++) {
			chunks.add(ChunkPos.pack(plan.x()[i] >> 4, plan.z()[i] >> 4));
		}
		chunks.forEach(chunk -> overworld.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
		long millis = (System.nanoTime() - started) / 1_000_000;
		int missing = 0;
		int firstMissing = -1;
		// A torch powers the powered rail beside it, and that one the next ones along, up to this many.
		int powerReach = 8;
		boolean[] powered = new boolean[plan.length()];
		boolean[] torch = new boolean[plan.length()];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < plan.length(); i++) {
			BlockState rail = overworld.getBlockState(pos.set(plan.x()[i], plan.track()[i], plan.z()[i]));
			if (!(rail.getBlock() instanceof BaseRailBlock)) {
				missing++;
				firstMissing = firstMissing < 0 ? i : firstMissing;
				continue;
			}
			powered[i] = rail.is(Blocks.POWERED_RAIL);
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				torch[i] |= powered[i] && overworld.getBlockState(pos.relative(direction)).is(Blocks.REDSTONE_TORCH);
			}
			// Or hidden under the block the rail stands on.
			torch[i] |= powered[i] && overworld.getBlockState(pos.below(2)).is(Blocks.REDSTONE_TORCH);
		}
		int[] fromTorch = new int[plan.length()];
		Arrays.fill(fromTorch, Integer.MAX_VALUE);
		for (int step : new int[] {1, -1}) {
			int distance = Integer.MAX_VALUE;
			for (int i = step > 0 ? 0 : plan.length() - 1; i >= 0 && i < plan.length(); i += step) {
				distance = !powered[i] ? Integer.MAX_VALUE : torch[i] ? 0 : distance == Integer.MAX_VALUE ? distance : distance + 1;
				fromTorch[i] = Math.min(fromTorch[i], distance);
			}
		}
		int unpowered = 0;
		int firstUnpowered = -1;
		int poweredCount = 0;
		for (int i = 0; i < plan.length(); i++) {
			if (powered[i]) {
				poweredCount++;
				// A line's end blocks are a station track's rails, which the station's own torches power.
				boolean stationRail = !station && (i == 0 || i == plan.length() - 1);
				if (fromTorch[i] > powerReach && !stationRail) {
					unpowered++;
					firstUnpowered = firstUnpowered < 0 ? i : firstUnpowered;
				}
			}
		}
		NetworkPlanner planner = VillageRailways.network().planner();
		Link.Status status = (station ? planner.stations() : planner.links()).stream()
				.filter(other -> other.key().equals(link.key())).findFirst().map(Link::status).orElse(null);
		int last = plan.length() - 1;
		List<String> report = new ArrayList<>();
		report.add((station ? "Station " : "Line ") + at(plan, 0) + " to " + at(plan, last) + " (" + String.valueOf(status).toLowerCase(Locale.ROOT)
				+ "): " + plan.length() + " blocks, " + chunks.size() + " chunks loaded in " + millis + " ms");
		report.add("  Rails: " + (missing == 0 ? "all in place" : missing + " missing, first at " + at(plan, firstMissing)));
		report.add("  Power: " + poweredCount + " powered rails, " + (unpowered == 0 ? "all within a torch's reach"
				: unpowered + " out of a torch's reach, first at " + at(plan, firstUnpowered)));
		if (!plan.seconds().isEmpty()) {
			int secondBlocks = 0;
			int secondMissing = 0;
			String firstSecondMissing = null;
			for (TrackPlan second : plan.seconds()) {
				secondBlocks += second.length();
				for (int i = 0; i < second.length(); i++) {
					if (!(overworld.getBlockState(pos.set(second.x()[i], second.track()[i], second.z()[i])).getBlock()
							instanceof BaseRailBlock)) {
						secondMissing++;
						firstSecondMissing = firstSecondMissing == null ? at(second, i) : firstSecondMissing;
					}
				}
			}
			report.add("  Second rail: " + plan.seconds().size() + " stretches, " + secondBlocks + " blocks, "
					+ (secondMissing == 0 ? "all in place" : secondMissing + " missing, first at " + firstSecondMissing));
		}
		List<String> tunnels = new ArrayList<>();
		List<String> decks = new ArrayList<>();
		int tunnelStart = -1;
		int deckStart = -1;
		for (int i = 0; i <= plan.length(); i++) {
			boolean tunnel = i < plan.length() && plan.sections()[i] == Section.TUNNEL;
			if (tunnel && tunnelStart < 0) {
				tunnelStart = i;
			} else if (!tunnel && tunnelStart >= 0) {
				if (i - tunnelStart >= 50) {
					tunnels.add((i - tunnelStart) + " from " + at(plan, tunnelStart) + " to " + at(plan, i - 1));
				}
				tunnelStart = -1;
			}
			// Plank decks across caves, for looking at the bridges.
			boolean deck = i < plan.length() && overworld.getBlockState(pos.set(plan.x()[i], plan.track()[i] - 1,
					plan.z()[i])).is(Blocks.OAK_PLANKS);
			if (deck && deckStart < 0) {
				deckStart = i;
			} else if (!deck && deckStart >= 0) {
				decks.add((i - deckStart) + " at " + at(plan, deckStart));
				deckStart = -1;
			}
		}
		report.add("  Tunnels of 50 or more: " + (tunnels.isEmpty() ? "none" : String.join("; ", tunnels)));
		report.add("  Cave bridges: " + (decks.isEmpty() ? "none" : String.join("; ", decks)));
		report.add("  Halts: " + (plan.halts().length == 0 ? "none" : Arrays.stream(plan.halts())
				.mapToObj(i -> at(plan, i)).collect(Collectors.joining("; "))));
		int dirt = 0;
		BlockPos firstDirt = null;
		for (int i = 0; i < plan.length(); i++) {
			if (plan.sections()[i] != Section.TUNNEL) {
				continue;
			}
			Direction across = (i < last ? plan.direction(i, i + 1) : plan.direction(i - 1, i)).getClockWise();
			int track = plan.track()[i];
			if (!overworld.getBlockState(pos.set(plan.x()[i], track + 3, plan.z()[i])).isSolid()) {
				// No roof over the rail: the open end of a tunnel, where dirt is just the hillside.
				continue;
			}
			for (int offset = -2; offset <= 2; offset++) {
				for (int y = track - 1; y <= track + 3; y++) {
					boolean shell = Math.abs(offset) == 2 ? y >= track && y <= track + 2 : y == track - 1 || y == track + 3;
					BlockPos block = new BlockPos(plan.x()[i] + across.getStepX() * offset, y,
							plan.z()[i] + across.getStepZ() * offset);
					if (shell && overworld.getBlockState(block).is(BlockTags.DIRT)) {
						dirt++;
						firstDirt = firstDirt == null ? block : firstDirt;
					}
				}
			}
		}
		report.add("  Dirt showing in tunnels: " + (dirt == 0 ? "none"
				: dirt + ", first at " + firstDirt.getX() + " " + firstDirt.getY() + " " + firstDirt.getZ()));
		report.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
		return plan.length() - missing;
	}

	private static String at(TrackPlan plan, int i) {
		return plan.x()[i] + " " + plan.track()[i] + " " + plan.z()[i];
	}

	private static boolean touchesAir(ServerLevel level, BlockPos block) {
		for (Direction direction : Direction.values()) {
			if (level.getBlockState(block.relative(direction)).isAir()) {
				return true;
			}
		}
		return false;
	}

	private static Link nthNearest(CommandSourceStack source, int n, boolean station) {
		Point origin = new Point((int) source.getPosition().x, (int) source.getPosition().z);
		NetworkPlanner planner = VillageRailways.network().planner();
		List<Link> nearest = (station ? planner.stations() : planner.links()).stream()
				.sorted(Comparator.comparingLong(link -> distanceSq(link, origin)))
				.limit(n)
				.toList();
		return nearest.size() < n ? null : nearest.getLast();
	}

	private static String describe(Link link) {
		Point from = link.path().getFirst();
		Point to = link.path().getLast();
		return "line " + from.x() + ", " + from.z() + " to " + to.x() + ", " + to.z();
	}

	private static long distanceSq(Link link, Point origin) {
		long best = Long.MAX_VALUE;
		for (Point corner : link.path()) {
			best = Math.min(best, corner.distanceSq(origin));
		}
		return best;
	}

	private static int listVillages(CommandContext<CommandSourceStack> context, int radius) {
		CommandSourceStack source = context.getSource();
		BlockPos origin = BlockPos.containing(source.getPosition());
		long started = System.nanoTime();
		List<PredictedStructure> villages = VillageRailways.villagePredictor(source.getLevel()).structuresNear(origin, radius);
		long millis = (System.nanoTime() - started) / 1_000_000;
		source.sendSuccess(() -> Component.literal(villages.size() + " villages within " + radius + " blocks (" + millis + " ms)"), false);
		for (PredictedStructure village : villages) {
			BlockPos center = village.center();
			int distance = (int) Math.sqrt(origin.distSqr(new BlockPos(center.getX(), origin.getY(), center.getZ())));
			source.sendSuccess(() -> Component.literal("  " + village.structure() + " at " + center.getX() + ", " + center.getY()
					+ ", " + center.getZ() + " (" + distance + " blocks), bounds " + village.bounds()), false);
		}
		return villages.size();
	}

	private static int verify(CommandContext<CommandSourceStack> context, int radius) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		StructurePredictor predictor = VillageRailways.villagePredictor(level);
		ChunkPos centerChunk = ChunkPos.containing(BlockPos.containing(source.getPosition()));
		int chunkRadius = (radius >> 4) + 1;
		List<ChunkPos> candidates = predictor.candidateChunks(centerChunk.x() - chunkRadius, centerChunk.z() - chunkRadius,
				centerChunk.x() + chunkRadius, centerChunk.z() + chunkRadius);
		int villages = 0;
		int mismatches = 0;
		for (ChunkPos chunk : candidates) {
			Optional<PredictedStructure> predicted = predictor.firstAt(chunk);
			ChunkAccess access = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.STRUCTURE_STARTS);
			Optional<StructureStart> actual = access.getAllStarts().values().stream()
					.filter(start -> start.isValid() && predictor.wants(start.getStructure()))
					.findFirst();
			if (actual.isPresent()) {
				villages++;
			}
			String problem = compare(predicted, actual);
			if (problem != null) {
				mismatches++;
				source.sendFailure(Component.literal("chunk " + chunk.x() + ", " + chunk.z() + ": " + problem));
			}
		}
		int found = villages;
		int wrong = mismatches;
		source.sendSuccess(() -> Component.literal("Checked " + candidates.size() + " candidate chunks within " + radius
				+ " blocks: " + found + " real villages, " + wrong + " mismatches"), false);
		return wrong;
	}

	private static String compare(Optional<PredictedStructure> predicted, Optional<StructureStart> actual) {
		if (predicted.isEmpty() && actual.isEmpty()) {
			return null;
		}
		if (predicted.isEmpty()) {
			return "missed a village with bounds " + actual.get().getBoundingBox();
		}
		if (actual.isEmpty()) {
			return "predicted " + predicted.get().structure() + " but none generated";
		}
		// Only the footprint and floor are compared: a fully generated village can grow taller while its features are
		// placed, which moves maxY but nothing a railway route depends on.
		BoundingBox expected = predicted.get().bounds();
		BoundingBox real = actual.get().getBoundingBox();
		if (expected.minX() != real.minX() || expected.maxX() != real.maxX() || expected.minZ() != real.minZ()
				|| expected.maxZ() != real.maxZ() || expected.minY() != real.minY()) {
			return "bounds differ: predicted " + expected + ", real " + real;
		}
		return null;
	}
}
