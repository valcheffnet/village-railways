package net.valcheff.villagerailways.build;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.world.level.ChunkPos;
import net.valcheff.villagerailways.network.Link;
import net.valcheff.villagerailways.network.NetworkPlanner.VillagePair;
import net.valcheff.villagerailways.network.Point;

/**
 * Which chunks a line may be built into, and which lines run through which chunks.
 *
 * <p>Three kinds of chunk matter. A chunk that existed before the mod was added (found in the region files on the
 * first start) is never touched. A chunk the mod saw generate gets its lines built while it generates; if a line is
 * planned only after that, the chunk gets the line later, as long as players have spent less than two minutes in it,
 * so nobody has built there yet. A chunk players have lived in longer is closed to new lines. When the owner opens
 * the existing land, every chunk there is opened to lines for good and gets them later like the others.
 *
 * <p>Registering a line and decorating a chunk share this object's lock, which closes the gap between a planner thread
 * registering a line and a generator thread decorating one of its chunks: each chunk of a line is either still to
 * generate, and gets the line then, or queued to get it later.
 *
 * <p>Every chunk a line has been built into is remembered, so a line that is planned again, when its station turns
 * western or it moves to another end, never goes into the same chunk twice: building over what stands there already
 * would take it down and put it up again.
 *
 * <p>Chunk sets are kept as one bit per chunk in 32 x 32 region bitsets and saved with the queue and the built lines
 * in {@code decorated.bin}.
 */
public class BuildState {
	/** How far, in blocks, building reaches from a line's centre: trench slopes, tunnel walls, bridge decks. */
	public static final int REACH = 8;
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	/** Marks the current file layout; the first layout started with a region count, never negative. */
	private static final int LAYOUT = -4;
	/** The layout before the opened chunks were saved. */
	private static final int LAYOUT_BUILT = -3;
	/** The layout before the built lines were saved. */
	private static final int LAYOUT_QUEUE = -2;

	private final Path file;
	/** Chunks the mod saw generate. */
	private final Map<Long, long[]> decorated = new HashMap<>();
	/** Chunks that existed before the mod. */
	private final Map<Long, long[]> preexisting = new HashMap<>();
	/** Chunks players have spent too long in for a line to be added. */
	private final Map<Long, long[]> lived = new HashMap<>();
	/** Chunks of the existing land the owner opened to lines, whoever lived there. */
	private final Map<Long, long[]> opened = new HashMap<>();
	private final Map<Long, List<VillagePair>> linesByChunk = new HashMap<>();
	private final Map<VillagePair, Set<Long>> chunksByLine = new HashMap<>();
	/** Generated chunks still to get a line planned after they generated. */
	private final Map<Long, Set<VillagePair>> laterByChunk = new LinkedHashMap<>();
	/** The chunks each unfinished line has been built into. */
	private final Map<VillagePair, Set<Long>> builtByLine = new HashMap<>();

	private BuildState(Path file) {
		this.file = file;
	}

	/**
	 * Loads the state of a world, or starts it: from the saved file when there is one, otherwise from the chunks
	 * present in {@code regionFolder}, which all existed before the mod.
	 */
	public static BuildState load(Path folder, Path regionFolder) throws IOException {
		BuildState state = new BuildState(folder.resolve("decorated.bin"));
		if (Files.exists(state.file)) {
			try (DataInputStream in = new DataInputStream(Files.newInputStream(state.file))) {
				int first = in.readInt();
				if (first == LAYOUT || first == LAYOUT_BUILT || first == LAYOUT_QUEUE) {
					readBits(in, in.readInt(), state.decorated);
					readBits(in, in.readInt(), state.preexisting);
					readBits(in, in.readInt(), state.lived);
					if (first == LAYOUT) {
						readBits(in, in.readInt(), state.opened);
					}
					int queued = in.readInt();
					for (int i = 0; i < queued; i++) {
						long chunk = in.readLong();
						int lines = in.readInt();
						for (int j = 0; j < lines; j++) {
							state.laterByChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>())
									.add(new VillagePair(in.readLong(), in.readLong()));
						}
					}
					if (first == LAYOUT || first == LAYOUT_BUILT) {
						int lines = in.readInt();
						for (int i = 0; i < lines; i++) {
							VillagePair line = new VillagePair(in.readLong(), in.readLong());
							int chunks = in.readInt();
							Set<Long> built = state.builtByLine.computeIfAbsent(line, key -> new HashSet<>());
							for (int j = 0; j < chunks; j++) {
								built.add(in.readLong());
							}
						}
					}
				} else {
					readBits(in, first, state.decorated);
				}
			}
		} else if (Files.isDirectory(regionFolder)) {
			state.markExistingChunks(regionFolder);
		}
		return state;
	}

	public synchronized void save() throws IOException {
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream raw = Files.newOutputStream(temporary); DataOutputStream out = new DataOutputStream(raw)) {
			out.writeInt(LAYOUT);
			writeBits(out, decorated);
			writeBits(out, preexisting);
			writeBits(out, lived);
			writeBits(out, opened);
			out.writeInt(laterByChunk.size());
			for (Map.Entry<Long, Set<VillagePair>> entry : laterByChunk.entrySet()) {
				out.writeLong(entry.getKey());
				out.writeInt(entry.getValue().size());
				for (VillagePair line : entry.getValue()) {
					out.writeLong(line.first());
					out.writeLong(line.second());
				}
			}
			out.writeInt(builtByLine.size());
			for (Map.Entry<VillagePair, Set<Long>> entry : builtByLine.entrySet()) {
				out.writeLong(entry.getKey().first());
				out.writeLong(entry.getKey().second());
				out.writeInt(entry.getValue().size());
				for (long chunk : entry.getValue()) {
					out.writeLong(chunk);
				}
			}
		}
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Registers a new line for building. Refused when one of its chunks existed before the mod or has been lived in;
	 * its chunks that have generated already are queued to get the line later, unless it was built into them before.
	 * {@code restored} registers a line saved before a restart as it was: its queue was saved with it.
	 */
	public synchronized boolean register(Link link, boolean restored, int reach) {
		Set<Long> chunks = chunksOf(link.path(), reach);
		if (!restored && chunks.stream().anyMatch(this::isClosed)) {
			return false;
		}
		chunksByLine.put(link.key(), chunks);
		for (long chunk : chunks) {
			linesByChunk.computeIfAbsent(chunk, key -> new ArrayList<>()).add(link.key());
			if (!restored && isSet(decorated, chunk) && !isBuilt(chunk, link.key())) {
				laterByChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(link.key());
			}
		}
		return true;
	}

	/** Whether any chunk a line along this path would touch is closed to new lines. */
	public synchronized boolean touchesClosed(List<Point> path) {
		return chunksOf(path, REACH).stream().anyMatch(this::isClosed);
	}

	/** Whether no chunk of a registered line has been built yet; true for a line not registered at all. */
	public synchronized boolean isUnbuilt(VillagePair line) {
		Set<Long> chunks = chunksByLine.get(line);
		return chunks == null || chunks.stream().noneMatch(chunk -> isSet(decorated, chunk));
	}

	/** How many chunks of a line have generated with it so far, and how many it has; null for a line not registered. */
	public synchronized int[] progress(VillagePair line) {
		Set<Long> chunks = chunksByLine.get(line);
		if (chunks == null) {
			return null;
		}
		return new int[] {(int) chunks.stream().filter(chunk -> isSet(decorated, chunk)).count(), chunks.size()};
	}

	/** Whether a line still has chunks queued to get it after they generated. */
	public synchronized boolean hasQueued(VillagePair line) {
		Set<Long> chunks = chunksByLine.get(line);
		return chunks != null && chunks.stream().anyMatch(chunk -> {
			Set<VillagePair> lines = laterByChunk.get(chunk);
			return lines != null && lines.contains(line);
		});
	}

	/** Marks a chunk decorated and returns the lines to build into it. */
	public synchronized List<VillagePair> decorate(ChunkPos chunk) {
		long key = ChunkPos.asLong(chunk.x, chunk.z);
		setBit(decorated, key);
		return List.copyOf(linesByChunk.getOrDefault(key, List.of()));
	}

	/** Records that a line has been built into a chunk. */
	public synchronized void markBuilt(long chunk, VillagePair line) {
		builtByLine.computeIfAbsent(line, key -> new HashSet<>()).add(chunk);
	}

	/** Whether a line has been built into a chunk. */
	public synchronized boolean isBuilt(long chunk, VillagePair line) {
		Set<Long> chunks = builtByLine.get(line);
		return chunks != null && chunks.contains(chunk);
	}

	/** Whether the lines a chunk was to get were built into it as it generated. */
	public synchronized boolean isDecorated(int chunkX, int chunkZ) {
		return isSet(decorated, ChunkPos.asLong(chunkX, chunkZ));
	}

	/**
	 * Opens every chunk that exists, from before the mod or since, to lines: from now on they count as generated, so a
	 * line planned through them is queued to be built there later, however long players have lived there.
	 */
	public synchronized void openExisting() {
		for (Map<Long, long[]> existing : List.of(preexisting, decorated)) {
			existing.forEach((region, words) -> {
				long[] open = opened.computeIfAbsent(region, key -> new long[16]);
				long[] generated = decorated.computeIfAbsent(region, key -> new long[16]);
				for (int i = 0; i < 16; i++) {
					open[i] |= words[i];
					generated[i] |= words[i];
				}
			});
		}
	}

	/**
	 * Queues a registered line again for the generated chunks it has not been built into, those it lost to players
	 * living there among them.
	 */
	public synchronized void requeueUnbuilt(VillagePair line) {
		for (long chunk : chunksByLine.getOrDefault(line, Set.of())) {
			if (isSet(decorated, chunk) && !isBuilt(chunk, line)) {
				laterByChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(line);
			}
		}
	}

	/** Records that players have spent too long in a chunk for new lines. */
	public synchronized void markLived(long chunk) {
		setBit(lived, chunk);
	}

	/**
	 * Takes up to {@code limit} queued chunks that {@code ready} accepts, each with the lines it is to get. A chunk
	 * lived in by now loses its queued lines, unless it was opened.
	 */
	public synchronized Map<Long, List<VillagePair>> takeQueued(LongPredicate ready, int limit) {
		Map<Long, List<VillagePair>> taken = new LinkedHashMap<>();
		Iterator<Map.Entry<Long, Set<VillagePair>>> entries = laterByChunk.entrySet().iterator();
		while (entries.hasNext() && taken.size() < limit) {
			Map.Entry<Long, Set<VillagePair>> entry = entries.next();
			if (isClosed(entry.getKey())) {
				entries.remove();
			} else if (ready.test(entry.getKey())) {
				taken.put(entry.getKey(), List.copyOf(entry.getValue()));
				entries.remove();
			}
		}
		return taken;
	}

	/** Puts a line back in a chunk's queue, when it could not be built yet. */
	public synchronized void requeue(long chunk, VillagePair line) {
		laterByChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(line);
	}

	/** Whether every chunk of a registered line has it: generated with it, or given it later. */
	public synchronized boolean isComplete(VillagePair line) {
		Set<Long> chunks = chunksByLine.get(line);
		return chunks != null && chunks.stream().allMatch(chunk -> isSet(decorated, chunk)) && !hasQueued(line);
	}

	/** Forgets a finished or abandoned line, with the chunks it was built into. */
	public synchronized void finish(VillagePair line) {
		forget(line);
		builtByLine.remove(line);
	}

	/** Forgets a line that is about to be registered again, keeping the chunks it was built into. */
	public synchronized void forget(VillagePair line) {
		Set<Long> chunks = chunksByLine.remove(line);
		if (chunks != null) {
			for (long chunk : chunks) {
				List<VillagePair> lines = linesByChunk.get(chunk);
				if (lines != null) {
					lines.remove(line);
					if (lines.isEmpty()) {
						linesByChunk.remove(chunk);
					}
				}
				Set<VillagePair> queued = laterByChunk.get(chunk);
				if (queued != null) {
					queued.remove(line);
					if (queued.isEmpty()) {
						laterByChunk.remove(chunk);
					}
				}
			}
		}
	}

	private boolean isClosed(long chunk) {
		return (isSet(preexisting, chunk) || isSet(lived, chunk)) && !isSet(opened, chunk);
	}

	/** Every chunk within {@code reach} blocks of the path. */
	private static Set<Long> chunksOf(List<Point> path, int reach) {
		Set<Long> chunks = new HashSet<>();
		for (int i = 1; i < path.size(); i++) {
			Point a = path.get(i - 1);
			Point b = path.get(i);
			int minChunkX = (Math.min(a.x(), b.x()) - reach) >> 4;
			int maxChunkX = (Math.max(a.x(), b.x()) + reach) >> 4;
			int minChunkZ = (Math.min(a.z(), b.z()) - reach) >> 4;
			int maxChunkZ = (Math.max(a.z(), b.z()) + reach) >> 4;
			for (int x = minChunkX; x <= maxChunkX; x++) {
				for (int z = minChunkZ; z <= maxChunkZ; z++) {
					chunks.add(ChunkPos.asLong(x, z));
				}
			}
		}
		return chunks;
	}

	/** Reads the location table at the start of every region file: a chunk exists where its entry is not zero. */
	private void markExistingChunks(Path regionFolder) throws IOException {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(regionFolder, "r.*.mca")) {
			for (Path region : files) {
				Matcher name = REGION_FILE.matcher(region.getFileName().toString());
				if (!name.matches() || Files.size(region) < 4096) {
					continue;
				}
				int regionX = Integer.parseInt(name.group(1));
				int regionZ = Integer.parseInt(name.group(2));
				byte[] header = new byte[4096];
				try (InputStream in = Files.newInputStream(region)) {
					in.readNBytes(header, 0, header.length);
				}
				for (int entry = 0; entry < 1024; entry++) {
					int offset = entry * 4;
					if (header[offset] != 0 || header[offset + 1] != 0 || header[offset + 2] != 0) {
						// Entries run x fastest: entry = localX + localZ * 32.
						setBit(preexisting, ChunkPos.asLong(regionX * 32 + (entry & 31), regionZ * 32 + (entry >> 5)));
					}
				}
			}
		}
	}

	private static boolean isSet(Map<Long, long[]> bits, long chunk) {
		int chunkX = ChunkPos.getX(chunk);
		int chunkZ = ChunkPos.getZ(chunk);
		long[] words = bits.get(regionKey(chunkX >> 5, chunkZ >> 5));
		int bit = bitIndex(chunkX, chunkZ);
		return words != null && (words[bit >> 6] & (1L << (bit & 63))) != 0;
	}

	private static void setBit(Map<Long, long[]> bits, long chunk) {
		int chunkX = ChunkPos.getX(chunk);
		int chunkZ = ChunkPos.getZ(chunk);
		long[] words = bits.computeIfAbsent(regionKey(chunkX >> 5, chunkZ >> 5), key -> new long[16]);
		int bit = bitIndex(chunkX, chunkZ);
		words[bit >> 6] |= 1L << (bit & 63);
	}

	private static void readBits(DataInputStream in, int regions, Map<Long, long[]> bits) throws IOException {
		for (int i = 0; i < regions; i++) {
			long key = in.readLong();
			long[] words = new long[16];
			for (int j = 0; j < 16; j++) {
				words[j] = in.readLong();
			}
			bits.put(key, words);
		}
	}

	private static void writeBits(DataOutputStream out, Map<Long, long[]> bits) throws IOException {
		out.writeInt(bits.size());
		for (Map.Entry<Long, long[]> entry : bits.entrySet()) {
			out.writeLong(entry.getKey());
			for (long word : entry.getValue()) {
				out.writeLong(word);
			}
		}
	}

	private static long regionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	private static int bitIndex(int chunkX, int chunkZ) {
		return (chunkX & 31) + ((chunkZ & 31) << 5);
	}
}
