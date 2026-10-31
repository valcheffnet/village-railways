package net.valcheff.villagerailways.build;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A structure file of the mod, as a structure block saves it, read for placing block by block: the builder turns and
 * mirrors it itself and gives some blocks a meaning of their own. Every file is drawn with the track running along +x.
 * Cells the file leaves out, where the structure block had structure void, are null.
 */
final class StructureFile {
	private static final Map<String, StructureFile> LOADED = new HashMap<>();
	private static final Map<String, StructureTemplate> TEMPLATES = new HashMap<>();

	final int sizeX;
	final int sizeY;
	final int sizeZ;
	private final BlockState[] states;

	private StructureFile(int sizeX, int sizeY, int sizeZ, BlockState[] states) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
	}

	/** The block at a cell, or null where the file leaves the world as it is. */
	BlockState get(int x, int y, int z) {
		return states[(y * sizeZ + z) * sizeX + x];
	}

	/** The structure {@code village_railways:<path>}, read once. */
	static synchronized StructureFile load(String path) {
		return LOADED.computeIfAbsent(path, StructureFile::read);
	}

	/**
	 * The turn, after an optional mirror across the track, that brings a file's +x round to {@code along} and its +z to
	 * {@code side}, which is square to it either way.
	 */
	static BlockState transform(BlockState state, Direction along, Direction side) {
		BlockState placed = side == along.getClockWise() ? state : state.mirror(Mirror.LEFT_RIGHT);
		return placed.rotate(rotationTo(along));
	}

	/** The turn that brings +x, east, round to {@code along}. */
	static Rotation rotationTo(Direction along) {
		return switch (along) {
			case SOUTH -> Rotation.CLOCKWISE_90;
			case WEST -> Rotation.CLOCKWISE_180;
			case NORTH -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/**
	 * The structure {@code village_railways:<path>} as the game places structures, read once. Read from the jar, not
	 * through the game's structure manager: the files are saved by Minecraft 26.3, which names the palette keys
	 * {@code id} and {@code properties}, and the game here reads only {@code Name} and {@code Properties}, so its own
	 * loader would place air.
	 */
	static synchronized StructureTemplate template(String path) {
		return TEMPLATES.computeIfAbsent(path, p -> {
			CompoundTag root = readTag(p);
			for (Tag entry : root.getListOrEmpty("palette")) {
				if (entry instanceof CompoundTag state) {
					rename(state, "id", "Name");
					rename(state, "properties", "Properties");
				}
			}
			StructureTemplate template = new StructureTemplate();
			template.load(BuiltInRegistries.BLOCK, root);
			return template;
		});
	}

	private static void rename(CompoundTag tag, String from, String to) {
		Tag value = tag.remove(from);
		if (value != null) {
			tag.put(to, value);
		}
	}

	private static CompoundTag readTag(String path) {
		String resource = "/data/village_railways/structure/" + path + ".nbt";
		try (InputStream in = StructureFile.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Missing structure " + resource);
			}
			return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new IllegalStateException("Could not read structure " + resource, e);
		}
	}

	private static StructureFile read(String path) {
		CompoundTag root = readTag(path);
		ListTag size = root.getListOrEmpty("size");
		int sizeX = size.getIntOr(0, 0);
		int sizeY = size.getIntOr(1, 0);
		int sizeZ = size.getIntOr(2, 0);
		ListTag paletteTag = root.getListOrEmpty("palette");
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < palette.length; i++) {
			palette[i] = state(paletteTag.getCompoundOrEmpty(i));
		}
		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		ListTag blocks = root.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag block = blocks.getCompoundOrEmpty(i);
			ListTag pos = block.getListOrEmpty("pos");
			states[(pos.getIntOr(1, 0) * sizeZ + pos.getIntOr(2, 0)) * sizeX + pos.getIntOr(0, 0)] =
					palette[block.getIntOr("state", 0)];
		}
		return new StructureFile(sizeX, sizeY, sizeZ, states);
	}

	private static BlockState state(CompoundTag tag) {
		Identifier id = Identifier.parse(tag.getStringOr("id", tag.getStringOr("Name", "minecraft:air")));
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		if (block == Blocks.AIR && !id.getPath().equals("air")) {
			throw new IllegalStateException("Unknown block " + id + " in a structure");
		}
		BlockState state = block.defaultBlockState();
		CompoundTag properties = tag.getCompound("properties").orElse(tag.getCompoundOrEmpty("Properties"));
		for (String name : properties.keySet()) {
			Property<?> property = block.getStateDefinition().getProperty(name);
			if (property != null) {
				state = with(state, property, properties.getStringOr(name, ""));
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}
}
