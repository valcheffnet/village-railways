package net.valcheff.villagerailways.map;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.valcheff.villagerailways.network.Link;
import net.valcheff.villagerailways.network.NetworkPlanner;
import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.network.Village;

/**
 * A vanilla map with the railway network drawn on it and nothing else: village footprints and centres, and the lines
 * coloured by kind and by how far they are built, on plain parchment. It shows the holder's marker, and is locked, so
 * the game never draws the terrain over it. A map may cover more than the largest vanilla scale; {@link NetworkMaps}
 * keeps how much.
 */
public final class NetworkMapItem {
	/** How far a map of the network reaches from its centre by default: 8192 blocks across, 64 to a pixel. */
	public static final int DEFAULT_RADIUS = 4096;
	/** Maps along each side of the wall in a western station. */
	public static final int STATION_WALL = 3;
	private static final int SIZE = 128;
	/** Parchment, so the map is not see-through in an item frame. */
	private static final byte BACKGROUND = MapColor.SAND.getPackedId(MapColor.Brightness.NORMAL);
	private static final byte FOOTPRINT = MapColor.SAND.getPackedId(MapColor.Brightness.LOW);
	private static final byte VILLAGE = MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH);
	private static final byte PLANNED = MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.HIGH);
	private static final byte BUILT = MapColor.COLOR_GREEN.getPackedId(MapColor.Brightness.NORMAL);
	/** Trunk lines are purple whether built or not, darker once built. */
	private static final byte TRUNK_BUILT = MapColor.COLOR_PURPLE.getPackedId(MapColor.Brightness.LOW);
	private static final byte TRUNK_PLANNED = MapColor.COLOR_PURPLE.getPackedId(MapColor.Brightness.HIGH);
	private static final byte BLOCKED = MapColor.COLOR_RED.getPackedId(MapColor.Brightness.NORMAL);
	/** Pixels across a line, so it shows in an item frame; a trunk line only differs in colour. */
	private static final int LINE_WIDTH = 2;

	private NetworkMapItem() {
	}

	/** A map of the network centred on a position, reaching at least {@code radius} blocks from it each way. */
	public static ItemStack create(ServerLevel level, NetworkPlanner planner, int x, int z, int radius) throws IOException {
		return create(level, planner, x, z, Math.ceilDiv(2 * radius, SIZE), Component.literal("Railway network"));
	}

	/**
	 * Maps of the network for a wall of {@code count} by {@code count} item frames, north at the top, row by row from
	 * the north west: together they cover at least {@code radius} blocks each way round a position, each map
	 * {@code count} times finer than one map of the whole area. Each one is named after its place.
	 */
	public static List<ItemStack> wall(ServerLevel level, NetworkPlanner planner, int x, int z, int count, int radius)
			throws IOException {
		int blocksPerPixel = Math.ceilDiv(2 * radius, count * SIZE);
		int width = SIZE * blocksPerPixel;
		List<ItemStack> maps = new ArrayList<>();
		for (int row = 0; row < count; row++) {
			for (int column = 0; column < count; column++) {
				// Map centres a whole map apart, the middle of the wall on the position.
				maps.add(create(level, planner, x + (2 * column - (count - 1)) * width / 2,
						z + (2 * row - (count - 1)) * width / 2, blocksPerPixel,
						Component.literal("Railway network, row " + (row + 1) + ", column " + (column + 1))));
			}
		}
		return maps;
	}

	/** The maps for the wall of a western station, centred on the station with a red X where it stands. */
	public static List<ItemStack> stationWall(ServerLevel level, NetworkPlanner planner, BlockPos station)
			throws IOException {
		List<ItemStack> maps = wall(level, planner, station.getX(), station.getZ(), STATION_WALL, DEFAULT_RADIUS);
		for (ItemStack map : maps) {
			MapItemSavedData.addTargetDecoration(map, station, "station", MapDecorationTypes.RED_X);
		}
		return maps;
	}

	/** One map of the network round a western station, for over its door, with a red X where the station stands. */
	public static ItemStack stationMap(ServerLevel level, NetworkPlanner planner, BlockPos station) throws IOException {
		ItemStack map = create(level, planner, station.getX(), station.getZ(), DEFAULT_RADIUS);
		MapItemSavedData.addTargetDecoration(map, station, "station", MapDecorationTypes.RED_X);
		return map;
	}

	/** Draws a network map again with the network as it is now, so a map on a wall follows the lines being built. */
	public static void redraw(ServerLevel level, NetworkPlanner planner, MapId id) {
		MapItemSavedData data = level.getMapData(id);
		if (data == null) {
			return;
		}
		int blocksPerPixel = NetworkMaps.blocksPerPixel(id);
		draw(data, planner, blocksPerPixel > 0 ? blocksPerPixel : 1 << data.scale);
	}

	/**
	 * A map centred on a block, {@code blocksPerPixel} blocks to a pixel. Its vanilla scale is the largest that fits, so
	 * the game names it about right; a map whose pixels cover more is registered with its real scale.
	 */
	private static ItemStack create(ServerLevel level, NetworkPlanner planner, int x, int z, int blocksPerPixel,
			Component name) throws IOException {
		byte scale = (byte) Math.min(MapItemSavedData.MAX_SCALE, 31 - Integer.numberOfLeadingZeros(blocksPerPixel));
		MapItemSavedData data = blank(level, x, z, scale);
		MapId id = level.getFreeMapId();
		level.setMapData(id, data);
		if (blocksPerPixel != 1 << scale) {
			NetworkMaps.register(id, blocksPerPixel);
		}
		draw(data, planner, blocksPerPixel);
		ItemStack stack = new ItemStack(Items.FILLED_MAP);
		stack.set(DataComponents.MAP_ID, id);
		stack.set(DataComponents.ITEM_NAME, name);
		return stack;
	}

	private static void draw(MapItemSavedData data, NetworkPlanner planner, int blocksPerPixel) {
		int minX = data.centerX - SIZE / 2 * blocksPerPixel;
		int minZ = data.centerZ - SIZE / 2 * blocksPerPixel;
		Canvas canvas = new Canvas(data, minX, minZ, blocksPerPixel);
		for (int px = 0; px < SIZE; px++) {
			for (int pz = 0; pz < SIZE; pz++) {
				canvas.setPixel(px, pz, BACKGROUND);
			}
		}
		for (Village village : planner.villages()) {
			for (int bx = village.minX(); bx <= village.maxX(); bx += blocksPerPixel) {
				for (int bz = village.minZ(); bz <= village.maxZ(); bz += blocksPerPixel) {
					canvas.set(bx, bz, FOOTPRINT, 1);
				}
			}
		}
		for (Link link : planner.links()) {
			byte color = switch (link.status()) {
				case BUILT -> link.trunk() ? TRUNK_BUILT : BUILT;
				case BLOCKED -> BLOCKED;
				case PLANNED -> link.trunk() ? TRUNK_PLANNED : PLANNED;
			};
			List<Point> path = link.path();
			for (int i = 1; i < path.size(); i++) {
				Point from = path.get(i - 1);
				Point to = path.get(i);
				int steps = Math.max(Math.abs(to.x() - from.x()), Math.abs(to.z() - from.z()));
				for (int step = 0; step <= steps; step++) {
					canvas.set(from.x() + Integer.signum(to.x() - from.x()) * step,
							from.z() + Integer.signum(to.z() - from.z()) * step, color, LINE_WIDTH);
				}
			}
		}
		for (Village village : planner.villages()) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					canvas.setPixel(canvas.pixel(village.center().x(), canvas.minX) + dx,
							canvas.pixel(village.center().z(), canvas.minZ) + dz, VILLAGE);
				}
			}
		}
	}

	/**
	 * An empty, locked map centred exactly on a block. Read through the game's own save format, since a new map from
	 * the game is always centred on its grid of maps.
	 */
	private static MapItemSavedData blank(ServerLevel level, int x, int z, byte scale) {
		CompoundTag tag = new CompoundTag();
		tag.put("dimension", Level.RESOURCE_KEY_CODEC.encodeStart(NbtOps.INSTANCE, level.dimension()).getOrThrow());
		tag.putInt("xCenter", x);
		tag.putInt("zCenter", z);
		tag.putByte("scale", scale);
		tag.putByteArray("colors", new byte[SIZE * SIZE]);
		tag.putBoolean("trackingPosition", true);
		tag.putBoolean("unlimitedTracking", false);
		tag.putBoolean("locked", true);
		return MapItemSavedData.load(tag, level.registryAccess());
	}

	/** The map's pixels, addressed by block position. */
	private record Canvas(MapItemSavedData data, int minX, int minZ, int blocksPerPixel) {
		/** Colours the pixel of a block and those round it, {@code width} pixels across. */
		void set(int x, int z, byte color, int width) {
			int px = pixel(x, minX);
			int pz = pixel(z, minZ);
			for (int dx = -(width - 1) / 2; dx <= width / 2; dx++) {
				for (int dz = -(width - 1) / 2; dz <= width / 2; dz++) {
					setPixel(px + dx, pz + dz, color);
				}
			}
		}

		int pixel(int block, int min) {
			return Math.floorDiv(block - min, blocksPerPixel);
		}

		void setPixel(int px, int pz, byte color) {
			if (px >= 0 && px < SIZE && pz >= 0 && pz < SIZE) {
				data.setColor(px, pz, color);
			}
		}
	}
}
