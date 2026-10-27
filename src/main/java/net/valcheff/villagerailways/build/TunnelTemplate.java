package net.valcheff.villagerailways.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * A tunnel cut from a model built in the game, one slice per block along the track: an entrance, two sections that
 * take turns along the tunnel, and the entrance mirrored as the exit. The first slices of the entrance (its
 * {@code outside} ones) stand in front of the portal, in the open. The model runs north; a slice is turned to the way
 * the line runs when it is built.
 *
 * <p>Each cell of a slice is left as it is, cleared, a wall, or a block. A wall keeps stone and open cave and is lined
 * with cobblestone where it is dirt, sand, gravel or fluid, as everywhere in a tunnel. The model's rails are only
 * cleared: the line lays its own, with its powered rails.
 */
final class TunnelTemplate {
	static final TunnelTemplate SINGLE = load("single");
	static final TunnelTemplate DOUBLE = load("double");

	static final byte LEAVE = 0;
	static final byte CLEAR = 1;
	static final byte WALL = 2;
	static final byte BLOCK = 3;

	/** One slice across the tunnel, its cells by height and then across, from the left looking along the track. */
	static final class Slice {
		private final byte[][] kinds;
		private final BlockState[][][] states;

		private Slice(byte[][] kinds, BlockState[][][] states) {
			this.kinds = kinds;
			this.states = states;
		}
	}

	final int minAcross;
	final int maxAcross;
	final int minUp;
	final int maxUp;
	/** How many slices of the entrance stand in front of the portal. */
	final int outside;
	final List<Slice> entrance;
	final List<Slice> sectionA;
	final List<Slice> sectionB;
	final List<Slice> exit;
	/** Cells where a cobweb may hang, across and up from the rail, in order of preference. */
	final List<int[]> cobwebs;

	private TunnelTemplate(int minAcross, int maxAcross, int minUp, int maxUp, int outside, List<Slice> entrance,
			List<Slice> sectionA, List<Slice> sectionB, List<Slice> exit, List<int[]> cobwebs) {
		this.minAcross = minAcross;
		this.maxAcross = maxAcross;
		this.minUp = minUp;
		this.maxUp = maxUp;
		this.outside = outside;
		this.entrance = entrance;
		this.sectionA = sectionA;
		this.sectionB = sectionB;
		this.exit = exit;
		this.cobwebs = cobwebs;
	}

	/** Slices of the entrance inside the portal, and of the exit before it. */
	int inside() {
		return entrance.size() - outside;
	}

	int period() {
		return sectionA.size();
	}

	byte kind(Slice slice, int across, int up) {
		return slice.kinds[up - minUp][across - minAcross];
	}

	/** The block of a cell, turned for a tunnel running {@code forward}. */
	BlockState state(Slice slice, int across, int up, Direction forward) {
		return slice.states[forward.get2DDataValue()][up - minUp][across - minAcross];
	}

	/** Whether a cobweb may take a cell: one that stays empty, or holds a sign on the wall. */
	boolean takesCobweb(Slice slice, int across, int up) {
		byte kind = kind(slice, across, up);
		return kind == CLEAR || kind == BLOCK && state(slice, across, up, Direction.NORTH).is(BlockTags.WALL_SIGNS);
	}

	private static TunnelTemplate load(String name) {
		String path = "/data/village_railways/tunnel_templates/" + name + ".json";
		try (InputStream in = TunnelTemplate.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing tunnel template " + path);
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return parse(JsonParser.parseReader(reader).getAsJsonObject());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read tunnel template " + path, e);
		}
	}

	private static TunnelTemplate parse(JsonObject json) {
		JsonArray across = json.getAsJsonArray("across");
		JsonArray up = json.getAsJsonArray("up");
		int minAcross = across.get(0).getAsInt();
		int maxAcross = across.get(1).getAsInt();
		int minUp = up.get(0).getAsInt();
		int maxUp = up.get(1).getAsInt();
		Map<Character, BlockState> palette = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("palette").entrySet()) {
			palette.put(entry.getKey().charAt(0), parseState(entry.getValue().getAsString()));
		}
		JsonObject pieces = json.getAsJsonObject("pieces");
		List<int[]> cobwebs = new ArrayList<>();
		for (JsonElement cell : json.getAsJsonArray("cobwebs")) {
			cobwebs.add(new int[] {cell.getAsJsonArray().get(0).getAsInt(), cell.getAsJsonArray().get(1).getAsInt()});
		}
		return new TunnelTemplate(minAcross, maxAcross, minUp, maxUp, json.get("outside").getAsInt(),
				piece(pieces, "entrance", palette, minAcross, maxAcross, minUp, maxUp),
				piece(pieces, "section_a", palette, minAcross, maxAcross, minUp, maxUp),
				piece(pieces, "section_b", palette, minAcross, maxAcross, minUp, maxUp),
				piece(pieces, "exit", palette, minAcross, maxAcross, minUp, maxUp), cobwebs);
	}

	private static List<Slice> piece(JsonObject pieces, String name, Map<Character, BlockState> palette, int minAcross,
			int maxAcross, int minUp, int maxUp) {
		int width = maxAcross - minAcross + 1;
		int height = maxUp - minUp + 1;
		List<Slice> slices = new ArrayList<>();
		for (JsonElement element : pieces.getAsJsonArray(name)) {
			JsonArray rows = element.getAsJsonArray();
			byte[][] kinds = new byte[height][width];
			BlockState[][][] states = new BlockState[4][height][width];
			for (int row = 0; row < height; row++) {
				// Rows run from the top down.
				String line = rows.get(row).getAsString();
				int u = height - 1 - row;
				for (int a = 0; a < width; a++) {
					char cell = line.charAt(a);
					BlockState state = palette.get(cell);
					kinds[u][a] = switch (cell) {
						case ' ' -> LEAVE;
						case '.' -> CLEAR;
						case '#' -> WALL;
						default -> state.getBlock() instanceof BaseRailBlock ? CLEAR : BLOCK;
					};
					if (kinds[u][a] == BLOCK) {
						for (Direction forward : Direction.Plane.HORIZONTAL) {
							states[forward.get2DDataValue()][u][a] = state.rotate(rotationTo(forward));
						}
					}
				}
			}
			slices.add(new Slice(kinds, states));
		}
		return List.copyOf(slices);
	}

	private static Rotation rotationTo(Direction forward) {
		return switch (forward) {
			case EAST -> Rotation.CLOCKWISE_90;
			case SOUTH -> Rotation.CLOCKWISE_180;
			case WEST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/** A block state written as {@code namespace:id[property=value,...]}. */
	private static BlockState parseState(String text) {
		int open = text.indexOf('[');
		Identifier id = Identifier.parse(open < 0 ? text : text.substring(0, open));
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		if (block == Blocks.AIR && !id.getPath().equals("air")) {
			throw new IllegalStateException("Unknown block " + id + " in a tunnel template");
		}
		BlockState state = block.defaultBlockState();
		if (open >= 0) {
			for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
				String[] parts = pair.split("=");
				Property<?> property = block.getStateDefinition().getProperty(parts[0]);
				if (property == null) {
					throw new IllegalStateException("Block " + id + " has no property " + parts[0]);
				}
				state = with(state, property, parts[1]);
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return state.setValue(property, property.getValue(value)
				.orElseThrow(() -> new IllegalStateException("Bad value " + value + " for " + property.getName())));
	}
}
