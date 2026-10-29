package net.valcheff.villagerailways.map;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.world.level.saveddata.maps.MapId;
import net.valcheff.villagerailways.network.NetworkPlanner.VillagePair;

/**
 * The network maps of a world, kept in the mod's folder of its save. {@code maps.json} holds the maps that show more
 * than a vanilla map can, by map id: how many blocks one pixel of each covers. A vanilla map keeps its scale only up
 * to 4, so the real one is kept here and the markers on these maps are placed by it. {@code walls.json} holds the
 * maps hung on the wall of each western station, by station, so they can be drawn again as lines are built.
 */
public final class NetworkMaps {
	private static final Gson GSON = new Gson();
	private static final Type SCALES = new TypeToken<TreeMap<Integer, Integer>>() {
	}.getType();
	private static final Type WALLS = new TypeToken<TreeMap<String, List<Integer>>>() {
	}.getType();
	private static volatile NetworkMaps current;

	private final Path folder;
	private final Map<Integer, Integer> blocksPerPixel;
	private final Map<String, List<Integer>> walls;

	private NetworkMaps(Path folder, Map<Integer, Integer> blocksPerPixel, Map<String, List<Integer>> walls) {
		this.folder = folder;
		this.blocksPerPixel = blocksPerPixel;
		this.walls = walls;
	}

	/** Reads the maps of the world saved in {@code folder}. */
	public static void load(Path folder) throws IOException {
		Map<Integer, Integer> scales = read(folder.resolve("maps.json"), SCALES);
		Map<String, List<Integer>> walls = read(folder.resolve("walls.json"), WALLS);
		current = new NetworkMaps(folder, scales, walls);
	}

	public static void unload() {
		current = null;
	}

	/** Blocks one pixel of a map covers, or 0 for a map drawn at its vanilla scale. */
	public static int blocksPerPixel(MapId id) {
		NetworkMaps maps = current;
		if (maps == null) {
			return 0;
		}
		synchronized (maps) {
			return maps.blocksPerPixel.getOrDefault(id.id(), 0);
		}
	}

	/** Whether the wall of a western station has its maps already. */
	public static boolean hasWall(VillagePair station) {
		NetworkMaps maps = current;
		if (maps == null) {
			return true;
		}
		synchronized (maps) {
			return maps.walls.containsKey(key(station));
		}
	}

	/** The maps on the walls of all western stations. */
	public static List<MapId> wallMaps() {
		NetworkMaps maps = current;
		if (maps == null) {
			return List.of();
		}
		synchronized (maps) {
			return maps.walls.values().stream().flatMap(List::stream).map(MapId::new).toList();
		}
	}

	static void register(MapId id, int blocksPerPixel) throws IOException {
		NetworkMaps maps = loaded();
		synchronized (maps) {
			maps.blocksPerPixel.put(id.id(), blocksPerPixel);
			maps.write("maps.json", maps.blocksPerPixel, SCALES);
		}
	}

	/** Notes the maps hung on the wall of a western station. */
	public static void registerWall(VillagePair station, List<MapId> ids) throws IOException {
		NetworkMaps maps = loaded();
		synchronized (maps) {
			maps.walls.put(key(station), ids.stream().map(MapId::id).toList());
			maps.write("walls.json", maps.walls, WALLS);
		}
	}

	private static NetworkMaps loaded() {
		NetworkMaps maps = current;
		if (maps == null) {
			throw new IllegalStateException("The overworld is not loaded");
		}
		return maps;
	}

	private static String key(VillagePair station) {
		return station.first() + "_" + station.second();
	}

	private static <T> T read(Path file, Type type) throws IOException {
		if (!Files.exists(file)) {
			return GSON.fromJson("{}", type);
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, type);
		}
	}

	private void write(String name, Object value, Type type) throws IOException {
		Files.createDirectories(folder);
		try (Writer writer = Files.newBufferedWriter(folder.resolve(name), StandardCharsets.UTF_8)) {
			GSON.toJson(value, type, writer);
		}
	}
}
