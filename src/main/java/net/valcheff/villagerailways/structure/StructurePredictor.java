package net.valcheff.villagerailways.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * Finds structures a level will generate without generating any chunks.
 *
 * <p>Every placement cell of a structure set yields one candidate chunk. The candidate goes through the same steps as
 * {@link ChunkGenerator#createStructures}: the placement check (frequency and exclusion zones), the weighted pick
 * between the set's structures, and {@link Structure#generate}, which checks the biome at the start's surface height
 * and lays out the pieces. The result is the start the chunk will get, with its exact bounds. Only random-spread
 * placements are handled; every vanilla structure above ground uses one.
 */
public class StructurePredictor {
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final ChunkGeneratorStructureState structureState;
	private final RandomState randomState;
	private final Registry<Structure> structures;
	private final Predicate<Holder<Structure>> wanted;
	private final List<Holder<StructureSet>> sets;
	private final Map<Long, List<PredictedStructure>> cache = new ConcurrentHashMap<>();

	public StructurePredictor(ServerLevel level, Predicate<Holder<Structure>> wanted) {
		this.level = level;
		this.generator = level.getChunkSource().getGenerator();
		this.structureState = level.getChunkSource().getGeneratorState();
		this.randomState = level.getChunkSource().randomState();
		this.structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		this.wanted = wanted;
		this.sets = structureState.possibleStructureSets().stream()
				.filter(set -> set.value().placement() instanceof RandomSpreadStructurePlacement)
				.filter(set -> set.value().structures().stream().anyMatch(entry -> wanted.test(entry.structure())))
				.toList();
	}

	/** Predicts villages: structures in the {@code #minecraft:village} tag. */
	public static StructurePredictor villages(ServerLevel level) {
		return new StructurePredictor(level, structure -> structure.is(StructureTags.VILLAGE));
	}

	/** Predicts what a railway line has to go around: every structure built on the surface except villages. */
	public static StructurePredictor surfaceObstacles(ServerLevel level) {
		return new StructurePredictor(level, structure -> !structure.is(StructureTags.VILLAGE)
				&& structure.value().step() == GenerationStep.Decoration.SURFACE_STRUCTURES);
	}

	/** Structures whose start piece lies within {@code radius} blocks of {@code center}, nearest first. */
	public List<PredictedStructure> structuresNear(BlockPos center, int radius) {
		long radiusSq = (long) radius * radius;
		List<PredictedStructure> found = new ArrayList<>();
		ChunkPos centerChunk = ChunkPos.containing(center);
		int chunkRadius = (radius >> 4) + 1;
		for (ChunkPos candidate : candidateChunks(centerChunk.x() - chunkRadius, centerChunk.z() - chunkRadius,
				centerChunk.x() + chunkRadius, centerChunk.z() + chunkRadius)) {
			predictAt(candidate).stream().filter(structure -> horizontalDistanceSq(structure.center(), center) <= radiusSq)
					.forEach(found::add);
		}
		found.sort(Comparator.comparingLong(structure -> horizontalDistanceSq(structure.center(), center)));
		return found;
	}

	/** Every chunk in the given chunk range that one of the sets could pick, one per placement cell and set. */
	public List<ChunkPos> candidateChunks(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		long seed = structureState.getLevelSeed();
		for (Holder<StructureSet> set : sets) {
			RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.value().placement();
			int spacing = placement.spacing();
			for (int cellX = Math.floorDiv(minChunkX, spacing); cellX <= Math.floorDiv(maxChunkX, spacing); cellX++) {
				for (int cellZ = Math.floorDiv(minChunkZ, spacing); cellZ <= Math.floorDiv(maxChunkZ, spacing); cellZ++) {
					ChunkPos chunk = placement.getPotentialStructureChunk(seed, cellX * spacing, cellZ * spacing);
					if (chunk.x() >= minChunkX && chunk.x() <= maxChunkX && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ) {
						chunks.add(chunk);
					}
				}
			}
		}
		return new ArrayList<>(chunks);
	}

	/** The wanted structures that will start in {@code chunk}, if any. Results are cached. */
	public List<PredictedStructure> predictAt(ChunkPos chunk) {
		return cache.computeIfAbsent(ChunkPos.pack(chunk.x(), chunk.z()), key -> computeAt(chunk));
	}

	/** Whether {@code structure} is one this predictor looks for. */
	public boolean wants(Structure structure) {
		return wanted.test(structures.wrapAsHolder(structure));
	}

	private List<PredictedStructure> computeAt(ChunkPos chunk) {
		List<PredictedStructure> found = new ArrayList<>();
		for (Holder<StructureSet> set : sets) {
			if (!set.value().placement().isStructureChunk(structureState, chunk.x(), chunk.z())) {
				continue;
			}
			StructureStart start = pickStart(set.value(), chunk);
			if (start != null && wants(start.getStructure())) {
				found.add(toPrediction(start, chunk));
			}
		}
		return List.copyOf(found);
	}

	/** First wanted structure that starts in {@code chunk}. */
	public Optional<PredictedStructure> firstAt(ChunkPos chunk) {
		return predictAt(chunk).stream().findFirst();
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
				generator.getBiomeSource(), randomState, level.getServer().getStructureManager(),
				structureState.getLevelSeed(), chunk, 0, level, structure.biomes()::contains);
		return start.isValid() ? start : null;
	}

	private PredictedStructure toPrediction(StructureStart start, ChunkPos chunk) {
		String name = structures.wrapAsHolder(start.getStructure()).getRegisteredName();
		BlockPos center = start.getPieces().getFirst().getBoundingBox().getCenter();
		List<BoundingBox> buildings = start.getPieces().stream()
				.filter(piece -> !isStreet(piece))
				.map(StructurePiece::getBoundingBox)
				.toList();
		return new PredictedStructure(name, chunk, center, start.getBoundingBox(), buildings);
	}

	/**
	 * Whether a piece is open ground a line may cross: a village street or street end, or the empty piece villagers
	 * stand on. Vanilla and most village mods keep these in pools named that way.
	 */
	private static boolean isStreet(StructurePiece piece) {
		if (!(piece instanceof PoolElementStructurePiece pooled)) {
			return false;
		}
		String element = pooled.getElement().toString();
		return element.contains("/streets/") || element.contains("/terminators/") || element.contains("/villagers/");
	}

	private static long horizontalDistanceSq(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}
}
