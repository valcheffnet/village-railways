package net.valcheff.villagerailways.network;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Keeps the network in {@code network.json} in the mod's folder of the world save.
 *
 * <p>A plain file rather than vanilla saved data: saved data needs a data fixer type, and none of the vanilla ones
 * belongs to this mod. The file is small and written whole, through a temporary file so a crash never leaves half of
 * it behind.
 */
public final class NetworkStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;

	public NetworkStore(Path folder) {
		this.file = folder.resolve("network.json");
	}

	public NetworkPlanner load(PlannerSettings settings) throws IOException {
		if (!Files.exists(file)) {
			return new NetworkPlanner(settings);
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Saved saved = GSON.fromJson(reader, Saved.class);
			return new NetworkPlanner(settings, saved.villages(), saved.links(), orEmpty(saved.stations()),
					orEmpty(saved.exploredCells()), saved.partlyExplored() == null ? Map.of() : saved.partlyExplored(),
					orEmpty(saved.deferred()));
		}
	}

	public void save(NetworkPlanner planner) throws IOException {
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			GSON.toJson(new Saved(1, planner.villages(), planner.links(), planner.stations(), planner.exploredCells(),
					planner.partlyExploredCells(), planner.deferredPairs()), writer);
		}
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	private record Saved(int version, List<Village> villages, List<Link> links, List<Link> stations,
			List<Long> exploredCells, Map<Long, long[]> partlyExplored, List<NetworkPlanner.VillagePair> deferred) {
	}
}
