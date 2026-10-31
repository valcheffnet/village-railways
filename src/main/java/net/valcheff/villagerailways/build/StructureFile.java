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
import net.minecraft.resources.ResourceLocation;
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
	 * through the game's structure manager, so the Paper plugin needs no data pack for it.
	 */
	static synchronized StructureTemplate template(String path) {
		return TEMPLATES.computeIfAbsent(path, p -> {
			StructureTemplate template = new StructureTemplate();
			template.load(BuiltInRegistries.BLOCK.asLookup(), readTag(p));
			return template;
		});
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
		ListTag size = root.getList("size", Tag.TAG_INT);
		int sizeX = size.getInt(0);
		int sizeY = size.getInt(1);
		int sizeZ = size.getInt(2);
		ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < palette.length; i++) {
			palette[i] = state(paletteTag.getCompound(i));
		}
		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag block = blocks.getCompound(i);
			ListTag pos = block.getList("pos", Tag.TAG_INT);
			states[(pos.getInt(1) * sizeZ + pos.getInt(2)) * sizeX + pos.getInt(0)] =
					palette[block.getInt("state")];
		}
		return new StructureFile(sizeX, sizeY, sizeZ, states);
	}

	private static BlockState state(CompoundTag tag) {
		ResourceLocation id = ResourceLocation.parse(tag.getString("Name"));
		Block block = BuiltInRegistries.BLOCK.get(id);
		if (block == Blocks.AIR && !id.getPath().equals("air")) {
			throw new IllegalStateException("Unknown block " + id + " in a structure");
		}
		BlockState state = block.defaultBlockState();
		CompoundTag properties = tag.getCompound("Properties");
		for (String name : properties.getAllKeys()) {
			Property<?> property = block.getStateDefinition().getProperty(name);
			if (property != null) {
				state = with(state, property, properties.getString(name));
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}
}
