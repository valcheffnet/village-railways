package net.valcheff.villagerailways.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * Finds the villages a level will generate without generating any chunks.
 *
 * <p>Every placement cell of a village structure set yields one candidate chunk. The candidate goes through the same
 * steps as {@link ChunkGenerator#createStructures}: the placement check (frequency and exclusion zones), the weighted
 * pick between the set's structures, and {@link Structure#generate}, which checks the biome at the start's surface
 * height and lays out the jigsaw pieces. The result is the start the chunk will get, with its exact bounds. Only
 * random-spread placements are handled; vanilla villages use one.
 */
public class VillagePredictor {
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final ChunkGeneratorStructureState structureState;
	private final RandomState randomState;
	private final Climate.Sampler climateSampler;
	private final Registry<Structure> structures;
	private final List<Holder<StructureSet>> villageSets;
	private final Map<Long, Optional<PredictedVillage>> cache = new ConcurrentHashMap<>();

	public VillagePredictor(ServerLevel level) {
		this.level = level;
		this.generator = level.getChunkSource().getGenerator();
		this.structureState = level.getChunkSource().getGeneratorState();
		this.randomState = level.getChunkSource().randomState();
		this.climateSampler = randomState.createClimateSampler(SamplerContext.builder().enableCaches().build());
		this.structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		this.villageSets = structureState.possibleStructureSets().stream()
				.filter(set -> set.value().placement() instanceof RandomSpreadStructurePlacement)
				.filter(set -> set.value().structures().stream().anyMatch(entry -> entry.structure().is(StructureTags.VILLAGE)))
				.toList();
	}

	/** Villages whose start piece lies within {@code radius} blocks of {@code center}, nearest first. */
	public List<PredictedVillage> villagesNear(BlockPos center, int radius) {
		long radiusSq = (long) radius * radius;
		List<PredictedVillage> found = new ArrayList<>();
		for (ChunkPos candidate : candidateChunks(ChunkPos.containing(center), (radius >> 4) + 1)) {
			predictAt(candidate).filter(village -> horizontalDistanceSq(village.center(), center) <= radiusSq)
					.ifPresent(found::add);
		}
		found.sort(Comparator.comparingLong(village -> horizontalDistanceSq(village.center(), center)));
		return found;
	}

	/** Every chunk within {@code chunkRadius} of {@code center} that a village set could pick, one per placement cell. */
	public List<ChunkPos> candidateChunks(ChunkPos center, int chunkRadius) {
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		long seed = structureState.getLevelSeed();
		for (Holder<StructureSet> set : villageSets) {
			RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.value().placement();
			int spacing = placement.spacing();
			int minCellX = Math.floorDiv(center.x() - chunkRadius, spacing);
			int maxCellX = Math.floorDiv(center.x() + chunkRadius, spacing);
			int minCellZ = Math.floorDiv(center.z() - chunkRadius, spacing);
			int maxCellZ = Math.floorDiv(center.z() + chunkRadius, spacing);
			for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
				for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
					ChunkPos chunk = placement.getPotentialStructureChunk(seed, cellX * spacing, cellZ * spacing);
					if (center.getChessboardDistance(chunk) <= chunkRadius) {
						chunks.add(chunk);
					}
				}
			}
		}
		return new ArrayList<>(chunks);
	}

	/** The village that will start in {@code chunk}, if any. Results are cached. */
	public Optional<PredictedVillage> predictAt(ChunkPos chunk) {
		return cache.computeIfAbsent(ChunkPos.pack(chunk.x(), chunk.z()), key -> computeAt(chunk));
	}

	/** Whether {@code structure} is in the {@code #minecraft:village} tag. */
	public boolean isVillage(Structure structure) {
		return structures.wrapAsHolder(structure).is(StructureTags.VILLAGE);
	}

	private Optional<PredictedVillage> computeAt(ChunkPos chunk) {
		for (Holder<StructureSet> set : villageSets) {
			if (!set.value().placement().isStructureChunk(structureState, chunk.x(), chunk.z())) {
				continue;
			}
			StructureStart start = pickStart(set.value(), chunk);
			if (start != null && isVillage(start.getStructure())) {
				return Optional.of(toVillage(start, chunk));
			}
		}
		return Optional.empty();
	}

	/** Same weighted pick as world generation: try entries in seeded random order until one generates. */
	private StructureStart pickStart(StructureSet set, ChunkPos chunk) {
		List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
		if (entries.size() == 1) {
			return tryGenerate(entries.getFirst(), chunk);
		}
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(structureState.getLevelSeed(), chunk.x(), chunk.z());
		int totalWeight = entries.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
		while (!entries.isEmpty()) {
			int roll = random.nextInt(totalWeight);
			int index = 0;
			for (StructureSet.StructureSelectionEntry entry : entries) {
				roll -= entry.weight();
				if (roll < 0) {
					break;
				}
				index++;
			}
			StructureSet.StructureSelectionEntry chosen = entries.get(index);
			StructureStart start = tryGenerate(chosen, chunk);
			if (start != null) {
				return start;
			}
			entries.remove(index);
			totalWeight -= chosen.weight();
		}
		return null;
	}

	private StructureStart tryGenerate(StructureSet.StructureSelectionEntry entry, ChunkPos chunk) {
		Structure structure = entry.structure().value();
		StructureStart start = structure.generate(entry.structure(), level.dimension(), level.registryAccess(), generator,
				generator.getBiomeSource(), climateSampler, randomState, level.getServer().getStructureTemplateManager(),
				structureState.getLevelSeed(), chunk, 0, level, structure.biomes()::contains);
		return start.isValid() ? start : null;
	}

	private PredictedVillage toVillage(StructureStart start, ChunkPos chunk) {
		String name = structures.wrapAsHolder(start.getStructure()).getRegisteredName();
		BlockPos center = start.getPieces().getFirst().getBoundingBox().getCenter();
		return new PredictedVillage(name, chunk, center, start.getBoundingBox());
	}

	private static long horizontalDistanceSq(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}
}
