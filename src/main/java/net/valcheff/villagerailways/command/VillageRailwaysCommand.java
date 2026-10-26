package net.valcheff.villagerailways.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.util.List;
import java.util.Optional;

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
import net.valcheff.villagerailways.village.PredictedVillage;
import net.valcheff.villagerailways.village.VillagePredictor;

/**
 * Operator tools for checking the mod in a running world.
 *
 * <ul>
 *   <li>{@code /villagerailways villages [radius]} lists the predicted villages around the caller.</li>
 *   <li>{@code /villagerailways verify [radius]} generates every candidate chunk up to structure starts and compares
 *       the real village starts with the prediction. Meant for test worlds: it creates chunks.</li>
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
								.executes(context -> verify(context, IntegerArgumentType.getInteger(context, "radius"))))));
	}

	private static int listVillages(CommandContext<CommandSourceStack> context, int radius) {
		CommandSourceStack source = context.getSource();
		BlockPos origin = BlockPos.containing(source.getPosition());
		long started = System.nanoTime();
		List<PredictedVillage> villages = VillageRailways.predictor(source.getLevel()).villagesNear(origin, radius);
		long millis = (System.nanoTime() - started) / 1_000_000;
		source.sendSuccess(() -> Component.literal(villages.size() + " villages within " + radius + " blocks (" + millis + " ms)"), false);
		for (PredictedVillage village : villages) {
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
		VillagePredictor predictor = VillageRailways.predictor(level);
		ChunkPos centerChunk = ChunkPos.containing(BlockPos.containing(source.getPosition()));
		List<ChunkPos> candidates = predictor.candidateChunks(centerChunk, (radius >> 4) + 1);
		int villages = 0;
		int mismatches = 0;
		for (ChunkPos chunk : candidates) {
			Optional<PredictedVillage> predicted = predictor.predictAt(chunk);
			ChunkAccess access = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.STRUCTURE_STARTS);
			Optional<StructureStart> actual = access.getAllStarts().values().stream()
					.filter(start -> start.isValid() && predictor.isVillage(start.getStructure()))
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

	private static String compare(Optional<PredictedVillage> predicted, Optional<StructureStart> actual) {
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
