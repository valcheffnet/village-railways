package net.valcheff.villagerailways.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.valcheff.villagerailways.VillageRailways;
import net.valcheff.villagerailways.network.Link;
import net.valcheff.villagerailways.network.NetworkMap;
import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.network.RailwayNetwork;
import net.valcheff.villagerailways.profile.ProfileMap;
import net.valcheff.villagerailways.profile.Section;
import net.valcheff.villagerailways.profile.TrackPlan;
import net.valcheff.villagerailways.profile.TrackPlanner;
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
				.then(literal("network")
						.executes(context -> plan(context, DEFAULT_RADIUS))
						.then(argument("radius", IntegerArgumentType.integer(16, 20000))
								.executes(context -> plan(context, IntegerArgumentType.getInteger(context, "radius"))))));
	}

	private static int plan(CommandContext<CommandSourceStack> context, int radius) {
		CommandSourceStack source = context.getSource();
		ServerLevel overworld = source.getServer().overworld();
		RailwayNetwork network = VillageRailways.network(overworld);
		try {
			long started = System.nanoTime();
			RailwayNetwork.Result result = network.explore(BlockPos.containing(source.getPosition()), radius);
			long millis = (System.nanoTime() - started) / 1_000_000;
			NetworkMap.ObstacleSource obstacles = (minX, minZ, maxX, maxZ) -> network.structureFilter()
					.obstaclesIn(minX, minZ, maxX, maxZ).stream()
					.map(structure -> new NetworkMap.Obstacle(structure.structure(), structure.bounds().minX(),
							structure.bounds().minZ(), structure.bounds().maxX(), structure.bounds().maxZ()))
					.toList();
			Path map = NetworkMap.write(network.planner(), network.oceanFilter()::isOcean, obstacles,
					network.folder().resolve("network.png"));
			source.sendSuccess(() -> Component.literal(result.newVillages() + " new villages, " + result.newLinks()
					+ " new lines (" + millis + " ms). Network: " + result.villages() + " villages, " + result.links()
					+ " lines. Turned down: " + result.rejections() + ". Map: " + map.toAbsolutePath()), false);
			return result.newLinks();
		} catch (IOException e) {
			VillageRailways.LOGGER.error("Could not save the railway network", e);
			source.sendFailure(Component.literal("Could not save the railway network: " + e.getMessage()));
			return 0;
		}
	}

	private static int profile(CommandContext<CommandSourceStack> context, int n) {
		CommandSourceStack source = context.getSource();
		ServerLevel overworld = source.getServer().overworld();
		RailwayNetwork network = VillageRailways.network(overworld);
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
		TrackPlan plan = new TrackPlanner(overworld).plan(link.path());
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
