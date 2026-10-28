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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A tunnel cut from a model built in the game, one slice per block along the track: an entrance, two sections that
 * take turns along the tunnel, and the entrance mirrored as the exit. The first slices of the entrance (its
 * {@code outside} ones) stand in front of the portal, in the open. Each piece is a structure file with the track along
 * +x, listed with its other details in {@code tunnel_templates/<name>.json}; a slice is turned to the way the line runs
 * when it is built.
 *
 * <p>Each cell of a slice is left as it is (structure void), cleared (air), a wall (barrier), or a block. A wall keeps
 * stone and open cave and is lined with cobblestone where it is dirt, sand, gravel or fluid, as everywhere in a tunnel.
 * The model's rails are only cleared: the line lays its own, with its powered rails.
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
		JsonObject pieces = json.getAsJsonObject("pieces");
		Map<String, StructureFile> files = new HashMap<>();
		for (String piece : List.of("entrance", "section_a", "section_b", "exit")) {
			files.put(piece, StructureFile.load(pieces.get(piece).getAsString().replace("village_railways:", "")));
		}
		StructureFile first = files.get("entrance");
		int middle = json.get("middle").getAsInt();
		int railLevel = json.get("rail_level").getAsInt();
		List<int[]> cobwebs = new ArrayList<>();
		for (JsonElement cell : json.getAsJsonArray("cobwebs")) {
			cobwebs.add(new int[] {cell.getAsJsonArray().get(0).getAsInt(), cell.getAsJsonArray().get(1).getAsInt()});
		}
		return new TunnelTemplate(-middle, first.sizeZ - 1 - middle, -railLevel, first.sizeY - 1 - railLevel,
				json.get("outside").getAsInt(), slices(files.get("entrance")), slices(files.get("section_a")),
				slices(files.get("section_b")), slices(files.get("exit")), cobwebs);
	}

	/** The slices of a piece: one per block along x, its cells by height (y) and across (z). */
	private static List<Slice> slices(StructureFile file) {
		List<Slice> slices = new ArrayList<>();
		for (int x = 0; x < file.sizeX; x++) {
			byte[][] kinds = new byte[file.sizeY][file.sizeZ];
			BlockState[][][] states = new BlockState[4][file.sizeY][file.sizeZ];
			for (int y = 0; y < file.sizeY; y++) {
				for (int z = 0; z < file.sizeZ; z++) {
					BlockState state = file.get(x, y, z);
					kinds[y][z] = state == null ? LEAVE
							: state.isAir() || state.getBlock() instanceof BaseRailBlock ? CLEAR
							: state.is(Blocks.BARRIER) ? WALL
							: BLOCK;
					if (kinds[y][z] == BLOCK) {
						for (Direction forward : Direction.Plane.HORIZONTAL) {
							states[forward.get2DDataValue()][y][z] = state.rotate(StructureFile.rotationTo(forward));
						}
					}
				}
			}
			slices.add(new Slice(kinds, states));
		}
		return List.copyOf(slices);
	}
}
