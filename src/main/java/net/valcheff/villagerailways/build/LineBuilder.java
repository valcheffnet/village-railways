package net.valcheff.villagerailways.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.valcheff.villagerailways.map.NetworkMapItem;
import net.valcheff.villagerailways.profile.Section;
import net.valcheff.villagerailways.profile.TrackPlan;
import net.valcheff.villagerailways.profile.TrackSettings;

/**
 * Builds the part of a line that falls into one chunk, while the chunk generates.
 *
 * <p>What is built where comes from the line's height profile, which every chunk computes the same way, so the parts
 * meet at chunk borders whatever order the chunks generate in. The real terrain only decides details: how far up a
 * cut reaches, which tunnel walls need sealing, how deep a pillar goes. Earthwork and track are written only inside
 * the chunk; the neighbouring chunks build their own part. Trees are the exception: a tree the line cuts through is
 * removed whole, also where it reaches into a neighbouring chunk, since trees grow across chunk borders.
 *
 * <p>Cross section: the rail in the middle, one free block each side, clear to three blocks high. Redstone torches
 * hide under the rail on solid ground and stand beside it on bridges. In the open the rail and both sides lie on
 * cobblestone, which does not fall like gravel, and a trench widens one block per block of height. A tunnel has
 * cobblestone where its walls are dirt or hold water, lava or sand, lanterns hanging from the ceiling and fence posts
 * beside the rail; on a ramp it is a block higher, with stairs under the ceiling and beside the rail. Straight, level
 * stretches of a planned tunnel are built from a {@link TunnelTemplate} instead: timber frames, lanterns and a portal
 * at each end, the double one where a second rail runs beside the line. Where a tunnel
 * runs through a cave it becomes a plank bridge on plank pillars, like an old mine, under fence frames that reach a
 * few blocks into the rock at either end; a short one gets a railing with lanterns instead. A bridge in the open is a
 * cobblestone deck on pillars a few blocks apart, a railing with lanterns, and a double track; a
 * fill is cobblestone up to the bed. Under a sea the track runs, double where it can, through a glass tube, a
 * {@link TubeShape}, with a floor of prismarine bricks and ribs of them on columns, each with a sea lantern at the top.
 */
public final class LineBuilder {
	/**
	 * A torch with a powered rail on flat track every this many blocks; every second one has a powered rail on either
	 * side as well.
	 */
	private static final int POWER_SPACING = 20;
	/** Distance between the rails of a double track. */
	private static final int DOUBLE_TRACK = 2;
	/**
	 * In a line's pairing, the middle of a junction on the line that runs on, times the side of the other line, and the
	 * corner of the line that turns away there.
	 */
	public static final int JUNCTION_ON = 3;
	public static final int JUNCTION_OFF = 4;
	/** In the template planning, a block a junction covers, which no stretch takes. */
	private static final int IN_JUNCTION = 3;
	/** The template slice of a block of the line that turns away at a junction, which the other line's junction builds. */
	private static final TemplateSlice COVERED = new TemplateSlice(null, null, null, 0, false);
	/** In the template planning, a block a corner piece holds. */
	private static final int IN_CORNER = 5;
	/** The template slice of a block of a line that a corner piece holds, built with the piece. */
	private static final TemplateSlice CORNER_PIECE = new TemplateSlice(null, null, null, 0, false);
	/**
	 * Fence posts in tunnels and lamp posts in the open, one every this many blocks; hanging lanterns in tunnels too,
	 * half way between the posts.
	 */
	private static final int LAMP_SPACING = 24;
	/** Fence frames over a plank bridge through a cave, one every this many blocks along the axis. */
	private static final int FRAME_SPACING = 4;
	/** How far the frames of a cave bridge reach into the tunnel at either end. */
	private static final int FRAME_BLEND = 4;
	/** Plank pillars under a cave bridge, a pair under every second frame. */
	private static final int CAVE_PILLAR_SPACING = 8;
	/** How far down a pillar may reach before giving up. */
	private static final int MAX_LEG = 48;
	/** A cave bridge shorter than this gets a railing with lanterns instead of frames. */
	private static final int SHORT_BRIDGE = 10;
	/**
	 * Bridge railings repeat a pattern with a lantern in its middle: {@value #SHORT_RAILING_PATTERN} blocks long on
	 * bridges up to {@value #SHORT_RAILING_BRIDGE} blocks, {@value #LONG_RAILING_PATTERN} on longer ones.
	 */
	private static final int SHORT_RAILING_PATTERN = 3;
	private static final int LONG_RAILING_PATTERN = 5;
	private static final int SHORT_RAILING_BRIDGE = 6;
	/** How far down open track is propped up where the ground under it is hollow. */
	private static final int MAX_FILL_UNDER = 6;
	/** How high above the track trees in the way are looked for. */
	private static final int TREE_CLEARANCE = 20;
	/** Most blocks taken out for one tree; a huge one stops there rather than eating a forest. */
	private static final int MAX_TREE_BLOCKS = 512;
	/** Most blocks taken out for one post cut off from its ground, with what stands and hangs on it. */
	private static final int MAX_FITTING_BLOCKS = 32;
	/** One row of one to three cobwebs in a template tunnel every this many blocks along the axis. */
	private static final int COBWEB_SPACING = 30;
	/**
	 * The railings of a double bridge carry a lantern now and then: at most one in every this many blocks along the
	 * axis, at a place picked from the position, and none in about a quarter of them.
	 */
	private static final int SCATTERED_LANTERN_CELL = 7;
	/** Leaves this close to a removed log go with it, unless a log that stays is as close. */
	private static final int LEAF_REACH = 4;
	private static final int SCAN_UP = 24;
	private static final int SCAN_DOWN = 48;

	/**
	 * How every block is written: sent to players, with no shape or neighbour updates, no drops, no spilled containers
	 * and no reaction of the block to being placed. The builder sets each block's final state itself, so a chunk comes
	 * out the same whether it is generating or loaded and in whatever order its neighbours are built; nothing it
	 * replaces or takes away drops as an item, and a powered rail keeps the power it is placed with.
	 */
	private static final int WRITE = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState STONE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState DECK = Blocks.OAK_PLANKS.defaultBlockState();
	private static final BlockState BRICKS = Blocks.STONE_BRICKS.defaultBlockState();
	private static final BlockState CRACKED_BRICKS = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
	private static final BlockState MOSSY_BRICKS = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
	private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
	private static final BlockState RAILING = Blocks.SPRUCE_FENCE.defaultBlockState();
	private static final BlockState COBWEB = Blocks.COBWEB.defaultBlockState();
	private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
	private static final BlockState PRISMARINE = Blocks.PRISMARINE_BRICKS.defaultBlockState();
	private static final BlockState SEA_LANTERN = Blocks.SEA_LANTERN.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	/** The halt's building, a structure file drawn with the track along +x and the building towards +z. */
	private static final String HALT = "halt";
	/**
	 * The house of a western station, a structure saved with a structure block: the tracks run along x, the station
	 * track at z {@value #WESTERN_TRACK_Z} and its second track two blocks lower, the rails one block up, the middle of
	 * the station at x {@value #WESTERN_MIDDLE_X}, the house towards larger z.
	 */
	private static final String WESTERN_STATION = "western_station";
	private static final int WESTERN_MIDDLE_X = 18;
	private static final int WESTERN_TRACK_Z = 4;
	private static final int WESTERN_RAIL_Y = 1;
	/** Where along the western house its two dispensers stand, by the door. */
	private static final int WESTERN_DISPENSER_X = 22;
	/** The middle frame of the map wall in the western house, on the wall facing the tracks. */
	private static final int WALL_X = 22;
	private static final int WALL_Y = 4;
	private static final int WALL_Z = 16;
	/** The frame with a single map over the door of the western house, facing the tracks too. */
	private static final int DOOR_MAP_Z = 9;

	/**
	 * The slice of a tunnel template a block is built from: a slice of the template turned to run {@code forward},
	 * its middle {@code middle} blocks to the left of the line. A porch slice stands in front of a portal, over the
	 * ordinary cut.
	 */
	private record TemplateSlice(TunnelTemplate template, TunnelTemplate.Slice slice, Direction forward, int middle,
			boolean porch) {
	}

	/**
	 * A block built from a template slice: by its own line, or for a second rail by its line, which leaves it the
	 * cross section from {@code low} to {@code high} blocks to the left.
	 */
	private record Templated(TemplateSlice slice, boolean own, int low, int high) {
	}

	/**
	 * Where a second rail leaves or joins a line: the side it lies on and the way the line runs on single from there.
	 * The line's rail at that block is a switch.
	 */
	private record Turnout(Direction branch, Direction single) {
	}

	/** Whether blocks of a chunk may be read and changed, given by chunk coordinates. */
	@FunctionalInterface
	public interface Reachable {
		boolean test(int chunkX, int chunkZ);
	}

	/** Whether a line being built has been built into a chunk already, given by chunk coordinates. */
	@FunctionalInterface
	public interface Built {
		boolean test(int chunkX, int chunkZ, TrackPlan line);
	}

	private final WorldGenLevel level;
	private final ChunkPos chunk;
	/** The lines being built, each followed by its second rails. */
	private final List<TrackPlan> plans;
	/** The line each second rail runs beside. */
	private final Map<TrackPlan, TrackPlan> lineOf = new HashMap<>();
	/** For each block of each line, the tunnel template slice it is built from, or null. */
	private final Map<TrackPlan, TemplateSlice[]> templateSlices = new HashMap<>();
	/**
	 * For each block of each line, whether its tunnel is lined with bricks: a straight stretch too short for a
	 * template, or the corner of a double line.
	 */
	private final Map<TrackPlan, boolean[]> bricked = new HashMap<>();
	/** For each line, the blocks where it turns in a tunnel built from the corner piece. */
	private final Map<TrackPlan, List<Integer>> cornerPieces = new HashMap<>();
	/** The logs of the template tunnels round this chunk, which clearing trees leaves standing; worked out once. */
	private Set<Long> templateLogs;
	private final Reachable reachable;
	/** For each line, where it shares a double tunnel with another line, as {@code RailwayService} works it out. */
	private final Map<TrackPlan, int[]> paired;
	/** Neighbouring chunks each line was built into already, where what later features took is put back. */
	private final Built built;
	private boolean repairing;
	/** The line being worked on in the current loop, and its place in {@link #plans}. */
	private TrackPlan plan;
	private int planIndex;
	/** Rails of the lines in and around this chunk by position: which line, at what height, and its block. */
	private final Map<Long, int[]> rails = new HashMap<>();
	/** What is built at each block of each line, worked out once before anything is cleared. */
	private final Map<TrackPlan, Section[]> sections = new HashMap<>();
	/** Whether each block of each line lies on a plank deck: 0 not known yet, 1 no, 2 yes. */
	private final Map<TrackPlan, byte[]> decks = new HashMap<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	/** Blocks cleared for the lines in this chunk; sealing and beds never fill them back in. */
	private final Set<Long> carved = new HashSet<>();
	/** The tubes round this chunk of each line that has any. */
	private final Map<TrackPlan, TubeShape> tubes = new HashMap<>();
	/** The blocks cleared inside tubes, which hold water over them that must stay. */
	private final Set<Long> tubeInside = new HashSet<>();
	/** Every block placed in this chunk, checked at the end for what holds it up. */
	private final Set<Long> placed = new HashSet<>();
	private boolean lining;
	private final TrackSettings settings;

	private LineBuilder(WorldGenLevel level, ChunkPos chunk, List<TrackPlan> lines, Reachable reachable,
			Built built, Map<TrackPlan, int[]> paired, TrackSettings settings) {
		this.level = level;
		this.paired = paired;
		this.chunk = chunk;
		this.settings = settings;
		List<TrackPlan> all = new ArrayList<>();
		for (TrackPlan line : lines) {
			all.add(line);
			for (TrackPlan second : line.seconds()) {
				all.add(second);
				lineOf.put(second, line);
			}
		}
		this.plans = all;
		this.reachable = reachable;
		this.built = built;
		int reach = BuildState.REACH + DOUBLE_TRACK + 1;
		for (int p = 0; p < plans.size(); p++) {
			TrackPlan line = plans.get(p);
			for (int i = 0; i < line.length(); i++) {
				int x = line.x()[i];
				int z = line.z()[i];
				if (x >= chunk.getMinBlockX() - reach && x <= chunk.getMaxBlockX() + reach
						&& z >= chunk.getMinBlockZ() - reach && z <= chunk.getMaxBlockZ() + reach) {
					rails.put(ChunkPos.pack(x, z), new int[] {p, line.track()[i], i});
				}
			}
		}
	}

	/**
	 * Builds every line of a chunk, with its second rails. The lines go through each pass together, so where two run
	 * side by side as a double track, neither one's walls close off the other's corridor. Trees are cleared in the chunks {@code reachable}
	 * accepts around it: the neighbours a generating chunk may write to, or the loaded ones when a line is added to
	 * a chunk later. In the chunks {@code built} says have a line already, its rails that later features took are laid
	 * again. Where {@code paired} says two lines run side by side in a tunnel, one of them builds the double tunnel for
	 * both.
	 */
	public static void build(WorldGenLevel level, ChunkPos chunk, List<TrackPlan> plans, Reachable reachable,
			Built built, Map<TrackPlan, int[]> paired, TrackSettings settings) {
		new LineBuilder(level, chunk, plans, reachable, built, paired, settings).build();
	}

	private void build() {
		int minX = chunk.getMinBlockX() - BuildState.REACH;
		int maxX = chunk.getMaxBlockX() + BuildState.REACH;
		int minZ = chunk.getMinBlockZ() - BuildState.REACH;
		int maxZ = chunk.getMaxBlockZ() + BuildState.REACH;
		// Clearing changes the ground that decides between a cut and a tunnel, so that is settled first, also for the
		// blocks just beyond the chunk that cave bridge frames look at.
		int around = BuildState.REACH + FRAME_BLEND + 1;
		for (planIndex = 0; planIndex < plans.size(); planIndex++) {
			plan = plans.get(planIndex);
			for (int i = 0; i < plan.length(); i++) {
				if (plan.x()[i] >= chunk.getMinBlockX() - around && plan.x()[i] <= chunk.getMaxBlockX() + around
						&& plan.z()[i] >= chunk.getMinBlockZ() - around && plan.z()[i] <= chunk.getMaxBlockZ() + around) {
					section(i);
				}
			}
		}
		// Clearing first, then walls and beds, then track. At a corner the cross section of one direction reaches into
		// the other direction's corridor: a wall laid there before the corridor is known would close it off, and
		// clearing after the rails would take them away.
		for (int pass = 0; pass < 3; pass++) {
			lining = pass == 1;
			if (pass == 1) {
				removeUnsupported();
			}
			for (planIndex = 0; planIndex < plans.size(); planIndex++) {
				plan = plans.get(planIndex);
				buildPass(pass, minX, maxX, minZ, maxZ);
				int haltReach = HaltPlanner.HALF_LENGTH + HaltPlanner.DEPTH;
				for (int halt : plan.halts()) {
					if (nearChunk(halt, haltReach)) {
						buildHalt(halt, pass, HaltPlanner.platformSide(plan, halt));
					}
				}
				for (int junction : plan.junctions()) {
					int i = junction / 2;
					if (nearChunk(i, haltReach)) {
						buildHalt(i, pass, branchSide(i).getOpposite());
					}
				}
				if (plan.western() && pass == 1) {
					placeWesternHouse();
				}
			}
		}
		for (planIndex = 0; planIndex < plans.size(); planIndex++) {
			plan = plans.get(planIndex);
			if (plan.western()) {
				placeWesternDispensers();
			}
		}
		secureAttachments();
		// A feature of a chunk that generates later may spill into the edge of a built neighbour, a dungeon among them,
		// and take rails there, or leave earth, rock or the roots and leaves of a tree in the line's way: the rails are
		// laid again and the way cleared.
		repairing = true;
		for (planIndex = 0; planIndex < plans.size(); planIndex++) {
			plan = plans.get(planIndex);
			for (int i = 0; i < plan.length(); i++) {
				int x = plan.x()[i];
				int z = plan.z()[i];
				if (x < minX || x > maxX || z < minZ || z > maxZ) {
					continue;
				}
				if (!inChunk(x, z) && writable(x, z)
						&& !(level.getBlockState(pos.set(x, plan.track()[i], z)).getBlock() instanceof BaseRailBlock)) {
					layRailAgain(i);
				}
				clearSpill(i);
			}
		}
		repairing = false;
	}

	private boolean nearChunk(int i, int reach) {
		return plan.x()[i] >= chunk.getMinBlockX() - reach && plan.x()[i] <= chunk.getMaxBlockX() + reach
				&& plan.z()[i] >= chunk.getMinBlockZ() - reach && plan.z()[i] <= chunk.getMaxBlockZ() + reach;
	}

	private void buildPass(int pass, int minX, int maxX, int minZ, int maxZ) {
		if (pass < 2 && !lineOf.containsKey(plan)) {
			buildTubes();
		}
		for (int i = 0; i < plan.length(); i++) {
			int x = plan.x()[i];
			int z = plan.z()[i];
			if (x < minX || x > maxX || z < minZ || z > maxZ) {
				continue;
			}
			Direction back = i > 0 ? plan.direction(i, i - 1) : plan.direction(i, i + 1).getOpposite();
			Direction ahead = i < plan.length() - 1 ? plan.direction(i, i + 1) : back.getOpposite();
			if (pass == 2) {
				placeTrack(i, back.getClockWise());
				continue;
			}
			if (cornerPieces.getOrDefault(plan, List.of()).contains(i)) {
				buildCorner(i);
			}
			buildCrossSection(i, back.getClockWise());
			if (back.getAxis() != ahead.getAxis()) {
				// A corner: the line runs along both axes here, so the cross section is laid both ways.
				buildCrossSection(i, ahead.getClockWise());
			}
		}
	}

	private void buildCrossSection(int i, Direction side) {
		int track = plan.track()[i];
		Section section = section(i);
		if (section == Section.TUBE) {
			return;
		}
		boolean framed = (section == Section.TUNNEL || section == Section.BRIDGE) && hasFrame(i);
		Templated templated = templated(i);
		for (int offset = -BuildState.REACH + 1; offset < BuildState.REACH; offset++) {
			int x = plan.x()[i] + side.getStepX() * offset;
			int z = plan.z()[i] + side.getStepZ() * offset;
			int distance = Math.abs(offset);
			if (distance <= 2) {
				clearTrees(x, z, track);
			}
			if (templated != null && !templated.slice().porch() && offset >= templated.low() && offset <= templated.high()) {
				continue;
			}
			if (!inChunk(x, z)) {
				if (lining && section == Section.TUNNEL && distance <= 2 && canWrite(x, z)) {
					resealNeighbour(i, x, z, track, distance);
				}
				continue;
			}
			switch (section) {
				case TUNNEL -> buildTunnel(i, x, z, track, distance);
				case BRIDGE -> buildBridge(i, x, z, track, offset);
				default -> buildOpen(i, x, z, track, distance, section);
			}
			if (framed) {
				clearOverFrame(x, z, track, distance);
			}
		}
		if (templated != null && templated.own()) {
			buildSlice(i, templated.slice());
		}
	}

	/**
	 * The current line's tubes in this chunk: in the clearing pass their inside, in the next their walls, the columns
	 * under their ribs and the kelp and sea grass left standing in the water over them, cut off from the bed.
	 */
	private void buildTubes() {
		TubeShape tube = tubes.computeIfAbsent(plan, line -> TubeShape.of(line, chunk));
		if (tube == null) {
			return;
		}
		for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
			for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
				if (!tube.covers(x, z)) {
					continue;
				}
				int track = tube.track(x, z);
				int top = track - 2;
				for (int y = track - 2; y <= track + 6; y++) {
					if (tube.inside(x, y, z)) {
						if (!lining) {
							clear(x, y, z);
							tubeInside.add(BlockPos.asLong(x, y, z));
						}
						top = y;
					} else if (tube.isWall(x, y, z)) {
						if (lining && !carved.contains(BlockPos.asLong(x, y, z))) {
							set(x, y, z, tube.isRibTop(x, y, z) ? SEA_LANTERN
									: tube.isRib(x, z) || tube.isFloor(x, y, z) ? PRISMARINE : GLASS);
						}
						top = y;
					}
				}
				if (lining) {
					clearSeaPlants(x, top + 1, z);
				}
			}
		}
		if (lining) {
			for (TubeShape.Rib rib : tube.ribs()) {
				for (int[] column : TubeShape.columns(rib)) {
					if (inChunk(column[0], column[1])) {
						tubeColumn(column[0], rib.track() - 2, column[1]);
					}
				}
			}
		}
	}

	/** Turns the kelp and sea grass from a block up to water, where a tube cut them off from the sea bed. */
	private void clearSeaPlants(int x, int fromY, int z) {
		for (int y = fromY; y < level.getMaxY(); y++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (!state.is(Blocks.KELP) && !state.is(Blocks.KELP_PLANT) && !state.is(Blocks.SEAGRASS)
					&& !state.is(Blocks.TALL_SEAGRASS)) {
				return;
			}
			set(x, y, z, WATER);
		}
	}

	/** A column of prismarine bricks under a rib of a tube, down to the sea bed. */
	private void tubeColumn(int x, int fromY, int z) {
		for (int y = fromY; y > fromY - MAX_LEG; y--) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isSolid() && state.getFluidState().isEmpty()) {
				return;
			}
			set(x, y, z, PRISMARINE);
		}
	}

	/**
	 * Block i's slice of a tunnel template: its open cells cleared in the clearing pass, then its walls sealed and its
	 * blocks placed. Walls in a neighbouring chunk are sealed again there, as an ordinary tunnel's are.
	 */
	private void buildSlice(int i, TemplateSlice slice) {
		TunnelTemplate template = slice.template();
		int[] cobweb = slice.porch() ? null : cobweb(i, slice);
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int up = template.minUp; up <= template.maxUp; up++) {
			for (int across = template.minAcross; across <= template.maxAcross; across++) {
				cell(plan, i, slice, across, up, cell);
				int x = cell.getX();
				int y = cell.getY();
				int z = cell.getZ();
				byte kind = template.kind(slice.slice(), across, up);
				if (!inChunk(x, z)) {
					if (lining && kind == TunnelTemplate.WALL && canWrite(x, z)
							&& needsLining(level.getBlockState(pos.set(x, y, z)))) {
						level.setBlock(pos, STONE, WRITE);
					}
					continue;
				}
				if (!lining) {
					if (kind == TunnelTemplate.CLEAR) {
						clear(x, y, z);
					}
				} else if (cobweb != null && cobweb[0] == across && cobweb[1] == up) {
					set(x, y, z, COBWEB);
				} else if (kind == TunnelTemplate.WALL) {
					seal(x, y, z);
				} else if (kind == TunnelTemplate.BLOCK) {
					set(x, y, z, template.state(slice.slice(), across, up, slice.forward()));
				}
			}
		}
	}

	/**
	 * The corner piece where the current line turns at block c: its open cells cleared in the clearing pass, then its
	 * walls sealed and its blocks placed, as a template slice's.
	 */
	private void buildCorner(int c) {
		TunnelTemplate.Corner corner = TunnelTemplate.CORNER;
		Direction along = cornerAlong(plan, c);
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int cx = 0; cx < corner.sizeX; cx++) {
			for (int cy = 0; cy < corner.sizeY; cy++) {
				for (int cz = 0; cz < corner.sizeZ; cz++) {
					byte kind = corner.kind(cx, cy, cz);
					if (kind == TunnelTemplate.LEAVE) {
						continue;
					}
					cornerCell(plan, c, cx, cy, cz, cell);
					int x = cell.getX();
					int y = cell.getY();
					int z = cell.getZ();
					if (!inChunk(x, z)) {
						if (lining && kind == TunnelTemplate.WALL && canWrite(x, z)
								&& needsLining(level.getBlockState(pos.set(x, y, z)))) {
							level.setBlock(pos, STONE, WRITE);
						}
						continue;
					}
					if (!lining) {
						if (kind == TunnelTemplate.CLEAR) {
							clear(x, y, z);
						}
					} else if (kind == TunnelTemplate.WALL) {
						seal(x, y, z);
					} else if (kind == TunnelTemplate.BLOCK) {
						set(x, y, z, corner.state(cx, cy, cz, along));
					}
				}
			}
		}
	}

	/** The position of a cell of the template slice at block i of a line. */
	private static void cell(TrackPlan line, int i, TemplateSlice slice, int across, int up, BlockPos.MutableBlockPos out) {
		Direction right = slice.forward().getClockWise();
		int offset = across - slice.middle();
		out.set(line.x()[i] + right.getStepX() * offset, line.track()[i] + up, line.z()[i] + right.getStepZ() * offset);
	}

	/**
	 * Whether a block is a log of a template tunnel or a western station's house, built here or in a neighbouring chunk,
	 * and not a tree's.
	 */
	private boolean isTemplateLog(BlockPos block) {
		if (templateLogs == null) {
			templateLogs = new HashSet<>();
			// As far as clearing trees reaches: the chunks around this one.
			int minX = chunk.getMinBlockX() - 16;
			int maxX = chunk.getMaxBlockX() + 16;
			int minZ = chunk.getMinBlockZ() - 16;
			int maxZ = chunk.getMaxBlockZ() + 16;
			BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
			StructureFile western = StructureFile.load(WESTERN_STATION);
			for (TrackPlan line : plans) {
				if (line.western()) {
					int middle = line.length() / 2;
					Rotation rotation = westernRotation(line.direction(middle, middle + 1).getCounterClockWise());
					BlockPos origin = westernOrigin(line, middle);
					for (int x = 0; x < western.sizeX; x++) {
						for (int y = 0; y < western.sizeY; y++) {
							for (int z = 0; z < western.sizeZ; z++) {
								BlockState state = western.get(x, y, z);
								if (state != null && state.is(BlockTags.LOGS)) {
									templateLogs.add(westernBlock(origin, rotation, x, y, z).asLong());
								}
							}
						}
					}
				}
				TemplateSlice[] slices = templateSlices(line);
				TunnelTemplate.Corner corner = TunnelTemplate.CORNER;
				for (int c : cornerPieces.getOrDefault(line, List.of())) {
					if (line.x()[c] < minX - 8 || line.x()[c] > maxX + 8 || line.z()[c] < minZ - 8
							|| line.z()[c] > maxZ + 8) {
						continue;
					}
					Direction along = cornerAlong(line, c);
					for (int x = 0; x < corner.sizeX; x++) {
						for (int y = 0; y < corner.sizeY; y++) {
							for (int z = 0; z < corner.sizeZ; z++) {
								if (corner.kind(x, y, z) == TunnelTemplate.BLOCK
										&& corner.state(x, y, z, along).is(BlockTags.LOGS)) {
									templateLogs.add(cornerCell(line, c, x, y, z, cell).asLong());
								}
							}
						}
					}
				}
				for (int i = 0; i < line.length(); i++) {
					TemplateSlice slice = slices[i];
					if (slice == null || slice == COVERED || slice == CORNER_PIECE || line.x()[i] < minX - 8
							|| line.x()[i] > maxX + 8 || line.z()[i] < minZ - 8 || line.z()[i] > maxZ + 8) {
						continue;
					}
					TunnelTemplate template = slice.template();
					for (int up = template.minUp; up <= template.maxUp; up++) {
						for (int across = template.minAcross; across <= template.maxAcross; across++) {
							if (template.kind(slice.slice(), across, up) == TunnelTemplate.BLOCK
									&& template.state(slice.slice(), across, up, slice.forward()).is(BlockTags.LOGS)) {
								cell(line, i, slice, across, up, cell);
								templateLogs.add(cell.asLong());
							}
						}
					}
				}
			}
		}
		return templateLogs.contains(block.asLong());
	}

	/**
	 * Where block i of a template tunnel holds a cobweb, as across and up in its slice, or null. In every
	 * {@value #COBWEB_SPACING} blocks along the axis one to three cobwebs hang in a row on one side, in a corner off
	 * the way of carts and walkers; the place, count and side come from the position, so every chunk agrees.
	 */
	private int[] cobweb(int i, TemplateSlice slice) {
		int at = along(i);
		int crossing = alongX(i) ? plan.z()[i] : plan.x()[i];
		int hash = mix(Math.floorDiv(at, COBWEB_SPACING), crossing);
		int count = 1 + Math.floorMod(hash, 3);
		int first = Math.floorMod(hash >>> 2, COBWEB_SPACING - count + 1);
		int k = Math.floorMod(at, COBWEB_SPACING) - first;
		if (k < 0 || k >= count) {
			return null;
		}
		boolean left = (hash >>> 16 & 1) == 0;
		for (int[] cell : slice.template().cobwebs) {
			if ((cell[0] < 0) == left && slice.template().takesCobweb(slice.slice(), cell[0], cell[1])) {
				return cell;
			}
		}
		return null;
	}

	/** A well mixed hash of two numbers, for choices that every chunk must make the same way. */
	private static int mix(int a, int b) {
		int h = a * 0x9E3779B1 + b * 0x85EBCA77;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		h *= 0x297A2D39;
		return h ^ h >>> 15;
	}

	/**
	 * What block i of the current line builds from a tunnel template, or null. A second rail leaves its line's double
	 * tunnel to the line, and a line paired with another leaves their double tunnel to the one that builds it. A cave
	 * bridge's deck and frames go before the template.
	 */
	private Templated templated(int i) {
		TrackPlan line = lineOf.get(plan);
		TemplateSlice slice = line == null ? templateSlices(plan)[i] : templateSlices(line)[plan.beside()[i]];
		if (line == null && slice == null) {
			int[] pairing = paired.get(plan);
			if (pairing != null && Math.abs(pairing[i]) == 2) {
				return pairedTemplate(i, pairing[i] / 2);
			}
		}
		if (slice == null) {
			return null;
		}
		if (slice == COVERED || slice == CORNER_PIECE) {
			// The other line's junction builds this line's blocks there, or the corner piece does.
			return line == null ? new Templated(slice, false, -BuildState.REACH, BuildState.REACH) : null;
		}
		// The template's cells run to the right, the cross section's offsets to the left; a junction may run the other
		// way from the line's blocks, which turns them round.
		int low = slice.middle() - slice.template().maxAcross;
		int high = slice.middle() - slice.template().minAcross;
		if (line == null) {
			if (crossSide(plan, i) != slice.forward().getCounterClockWise()) {
				low = slice.template().minAcross - slice.middle();
				high = slice.template().maxAcross - slice.middle();
			}
			return slice.porch() || !isDeck(i) && !hasFrame(i) ? new Templated(slice, true, low, high) : null;
		}
		int at = plan.beside()[i];
		if (slice.porch() || onLine(line, j -> isDeck(j) || hasFrame(j), at)) {
			return null;
		}
		return new Templated(slice, false, low - DOUBLE_TRACK, high - DOUBLE_TRACK);
	}

	/**
	 * The double tunnel at block i of the current line where the line beside it, on {@code side} (1 left, -1 right),
	 * builds it: this line leaves the template's cells alone, as a second rail does. Null where that line builds no
	 * template there.
	 */
	private Templated pairedTemplate(int i, int side) {
		Direction toward = crossSide(plan, i);
		int across = DOUBLE_TRACK * side;
		int[] other = rails.get(ChunkPos.pack(plan.x()[i] + toward.getStepX() * across,
				plan.z()[i] + toward.getStepZ() * across));
		if (other == null) {
			return null;
		}
		TrackPlan builder = plans.get(other[0]);
		int at = other[2];
		TemplateSlice slice = templateSlices(builder)[at];
		if (slice == null || slice == COVERED || slice == CORNER_PIECE || slice.porch()
				|| onLine(builder, j -> isDeck(j) || hasFrame(j), at)) {
			return null;
		}
		// The template's cells run to the right of the line that builds it; in this line's cross section that line
		// lies {@code across} to the left, and its left is this line's left, or its right where the two run opposite.
		int low = slice.middle() - slice.template().maxAcross;
		int high = slice.middle() - slice.template().minAcross;
		return crossSide(builder, at) == toward ? new Templated(slice, false, across + low, across + high)
				: new Templated(slice, false, across - high, across - low);
	}

	/** Whether block i of the current line lies in a junction, its own or the other line's. */
	private boolean inJunction(int i) {
		if (lineOf.containsKey(plan) || section(i) != Section.TUNNEL) {
			return false;
		}
		TemplateSlice slice = templateSlices(plan)[i];
		return slice == COVERED || slice != null && (slice.template() == TunnelTemplate.JUNCTION_LEFT
				|| slice.template() == TunnelTemplate.JUNCTION_RIGHT);
	}

	/** The left of a line at block i, the way the offsets of its cross section count. */
	private static Direction crossSide(TrackPlan line, int i) {
		Direction back = i > 0 ? line.direction(i, i - 1) : line.direction(i, i + 1).getOpposite();
		return back.getClockWise();
	}

	/** Runs a test on block i of another line being built, as the current line. */
	private boolean onLine(TrackPlan line, IntPredicate test, int i) {
		TrackPlan current = plan;
		int currentIndex = planIndex;
		plan = line;
		planIndex = plans.indexOf(line);
		try {
			return test.test(i);
		} finally {
			plan = current;
			planIndex = currentIndex;
		}
	}

	private TemplateSlice[] templateSlices(TrackPlan line) {
		return templateSlices.computeIfAbsent(line, this::planTemplates);
	}

	/** Whether the tunnel at block i of the current line is lined with bricks. */
	private boolean isBricked(int i) {
		TrackPlan line = lineOf.getOrDefault(plan, plan);
		int at = line == plan ? i : plan.beside()[i];
		templateSlices(line);
		boolean[] lined = bricked.get(line);
		return lined != null && lined[at];
	}

	/**
	 * The template slices of a line, worked out from its plan and its pairing alone so every chunk agrees. A planned
	 * tunnel is built from templates in stretches between its portals, corners and ramps: from the double template where
	 * a second rail or a paired line runs beside the line, the single one elsewhere, and none where the line beside
	 * builds the double one. A stretch opens with the entrance, at a portal with its porch in
	 * front, then the two sections take turns, the first slice of the next one closes them, and the exit ends the
	 * stretch. Whole sections seldom fit: at a portal the stretch reaches a few blocks out into the cut to make them
	 * fit, else it ends early and the blocks left over are built as an ordinary tunnel.
	 */
	private TemplateSlice[] planTemplates(TrackPlan line) {
		TemplateSlice[] slices = new TemplateSlice[line.length()];
		if (lineOf.containsKey(line)) {
			return slices;
		}
		// The side of the other rail of a double tunnel: 1 on the left, -1 on the right, 0 single, 2 none of this line's.
		int[] doubled = new int[line.length()];
		// Where a second rail of the line's own runs beside it, whose corners lie two blocks off the line's.
		boolean[] secondBeside = new boolean[line.length()];
		int[] pairing = paired.get(line);
		if (pairing != null) {
			for (int i = 0; i < line.length(); i++) {
				doubled[i] = Math.abs(pairing[i]) == 1 ? pairing[i] : Math.abs(pairing[i]) == 2 ? 2 : 0;
			}
		}
		for (TrackPlan second : line.seconds()) {
			// Not where it branches off and joins.
			for (int p = 2; p < second.length() - 2; p++) {
				doubled[second.beside()[p]] = 1;
				secondBeside[second.beside()[p]] = true;
			}
		}
		if (pairing != null) {
			for (int i = 0; i < line.length(); i++) {
				if (Math.abs(pairing[i]) == JUNCTION_ON) {
					layJunction(line, slices, doubled, pairing, i, pairing[i] / JUNCTION_ON);
				} else if (pairing[i] == JUNCTION_OFF) {
					// Before its corner the line runs beside the other one, after it away from it.
					int away = i > 0 && pairing[i - 1] != 0 ? 1 : -1;
					for (int t = -TunnelTemplate.JUNCTION_CORNER; t <= TunnelTemplate.JUNCTION_TURNED_OUT; t++) {
						int j = i + t * away;
						if (j >= 0 && j < line.length()) {
							slices[j] = COVERED;
							doubled[j] = IN_JUNCTION;
						}
					}
					layExitAfter(line, slices, doubled, i + TunnelTemplate.JUNCTION_TURNED_OUT * away, away);
				}
			}
		}
		List<Integer> corners = new ArrayList<>();
		for (int c = 0; c < line.length(); c++) {
			if (!line.corner()[c] || !fitsCorner(line, c, slices, doubled, secondBeside)) {
				continue;
			}
			int arm = TunnelTemplate.CORNER.arm;
			for (int j = c - arm; j <= c + arm; j++) {
				slices[j] = CORNER_PIECE;
				doubled[j] = IN_CORNER;
			}
			corners.add(c);
			layExitAfter(line, slices, doubled, c - arm, -1);
			layExitAfter(line, slices, doubled, c + arm, 1);
		}
		cornerPieces.put(line, corners);
		for (int i = 0; i < line.length(); i++) {
			if (!inTemplate(line, i, doubled, secondBeside)) {
				continue;
			}
			int last = i;
			while (last + 1 < line.length() && inTemplate(line, last + 1, doubled, secondBeside)
					&& doubled[last + 1] == doubled[i]) {
				last++;
			}
			layStretch(line, slices, i, last, doubled, secondBeside);
			i = last;
		}
		boolean[] doubleCorner = new boolean[line.length()];
		int reach = DOUBLE_TRACK + 2;
		for (int c = 0; c < line.length(); c++) {
			if (!line.corner()[c] || isPiece(doubled[c])) {
				continue;
			}
			boolean twin = false;
			for (int j = Math.max(0, c - reach); j <= Math.min(line.length() - 1, c + reach); j++) {
				twin |= doubled[j] != 0 && !isPiece(doubled[j]);
			}
			for (int j = Math.max(0, c - reach); twin && j <= Math.min(line.length() - 1, c + reach); j++) {
				doubleCorner[j] = true;
			}
		}
		boolean[] lined = new boolean[line.length()];
		for (int i = 0; i < line.length(); i++) {
			lined[i] = slices[i] == null && (inTemplate(line, i, doubled, secondBeside)
					|| doubleCorner[i] && !isPiece(doubled[i]) && plainTunnel(line, i));
		}
		bricked.put(line, lined);
		return slices;
	}

	/**
	 * Whether block i of a line can be built from a template: in a planned tunnel, clear of corners by as far as the
	 * cross section of a corner reaches (and a second rail's corners, two blocks off), off ramps and the blocks
	 * beside them, which keep the ramp's high tunnel and its stepped ceiling, and out of the end of a tube; not where
	 * the line beside builds the double tunnel, nor in a junction, whose corner the tunnels on either side meet.
	 */
	private static boolean inTemplate(TrackPlan line, int i, int[] doubled, boolean[] secondBeside) {
		if (doubled[i] == 2 || isPiece(doubled[i]) || !plainTunnel(line, i)) {
			return false;
		}
		int reach = secondBeside[i] ? DOUBLE_TRACK + 2 : 2;
		for (int j = Math.max(0, i - reach); j <= Math.min(line.length() - 1, i + reach); j++) {
			if (line.corner()[j] && !isPiece(doubled[j])) {
				return false;
			}
		}
		return true;
	}

	/** Whether a block is held by a junction or a corner piece in the template planning. */
	private static boolean isPiece(int doubled) {
		return doubled == IN_JUNCTION || doubled == IN_CORNER;
	}

	/**
	 * Whether the corner piece fits where a line turns at block c: a single track in a planned tunnel, level and
	 * straight along the piece's arms and a block past each, with nothing else laid there.
	 */
	private static boolean fitsCorner(TrackPlan line, int c, TemplateSlice[] slices, int[] doubled,
			boolean[] secondBeside) {
		int arm = TunnelTemplate.CORNER.arm;
		if (c - arm - 1 < 1 || c + arm + 1 > line.length() - 2) {
			return false;
		}
		for (int j = c - arm - 1; j <= c + arm + 1; j++) {
			if (j != c && line.corner()[j] || slices[j] != null || doubled[j] != 0 || secondBeside[j]
					|| line.track()[j] != line.track()[c]) {
				return false;
			}
		}
		for (int j = c - arm; j <= c + arm; j++) {
			if (!plainTunnel(line, j)) {
				return false;
			}
		}
		return true;
	}

	/** The way the corner piece's way out along +x runs where a line turns at block c. */
	private static Direction cornerAlong(TrackPlan line, int c) {
		Direction back = line.direction(c, c - 1);
		Direction ahead = line.direction(c, c + 1);
		return back.getClockWise() == ahead ? back : ahead;
	}

	/** The position of a cell of the corner piece where a line turns at block c. */
	private static BlockPos.MutableBlockPos cornerCell(TrackPlan line, int c, int x, int y, int z,
			BlockPos.MutableBlockPos out) {
		TunnelTemplate.Corner corner = TunnelTemplate.CORNER;
		Direction along = cornerAlong(line, c);
		Direction right = along.getClockWise();
		int ahead = x - corner.railX;
		int across = z - corner.railZ;
		return out.set(line.x()[c] + along.getStepX() * ahead + right.getStepX() * across,
				line.track()[c] + y - corner.railLevel, line.z()[c] + along.getStepZ() * ahead + right.getStepZ() * across);
	}

	/** Whether block i of a line lies in a planned tunnel, off ramps and the blocks beside them, out of a tube's end. */
	private static boolean plainTunnel(TrackPlan line, int i) {
		if (i < 1 || i > line.length() - 2 || line.sections()[i] != Section.TUNNEL) {
			return false;
		}
		for (int j = Math.max(0, i - TubeShape.RADIUS - 1); j <= Math.min(line.length() - 1, i + TubeShape.RADIUS + 1);
				j++) {
			if (line.sections()[j] == Section.TUBE) {
				return false;
			}
		}
		for (int j = i - 1; j <= i + 1; j++) {
			if (line.isRamp(j)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The junction at block k of a line that runs on, beside the corner where the line beside it turns away on
	 * {@code side} (1 left, -1 right of the way the blocks count): its slices from the double tunnel's side to the single
	 * one's.
	 */
	private static void layJunction(TrackPlan line, TemplateSlice[] slices, int[] doubled, int[] pairing, int k,
			int side) {
		// The double tunnel lies where the other line still runs beside this one.
		int on = k > 0 && pairing[k - 1] != 0 ? 1 : -1;
		int size = TunnelTemplate.JUNCTION_RIGHT.entrance.size();
		int first = k - TunnelTemplate.JUNCTION_CORNER * on;
		int last = first + (size - 1) * on;
		if (Math.min(first, last) < 1 || Math.max(first, last) > line.length() - 2) {
			return;
		}
		Direction forward = line.direction(k, k + on);
		TunnelTemplate template = side * on > 0 ? TunnelTemplate.JUNCTION_LEFT : TunnelTemplate.JUNCTION_RIGHT;
		for (int t = 0; t < size; t++) {
			int j = first + t * on;
			slices[j] = new TemplateSlice(template, template.entrance.get(t), forward, 0, false);
			doubled[j] = IN_JUNCTION;
		}
		layExitAfter(line, slices, doubled, last, on);
	}

	/**
	 * Where a way out of a junction, its last block {@code end}, leaves the tunnel at once, the single tunnel's exit
	 * stands right against the junction, reaching out over the track in front.
	 */
	private static void layExitAfter(TrackPlan line, TemplateSlice[] slices, int[] doubled, int end, int step) {
		int next = end + step;
		TunnelTemplate template = TunnelTemplate.SINGLE;
		int count = template.exit.size();
		if (next < 1 || next > line.length() - 2 || line.sections()[next] == Section.TUNNEL
				|| !straightOutside(line, slices, end, step, count)) {
			return;
		}
		Direction forward = line.direction(end, next);
		for (int k = 0; k < count; k++) {
			int j = end + step * (k + 1);
			slices[j] = new TemplateSlice(template, template.exit.get(k), forward, 0, k >= template.inside());
			doubled[j] = IN_JUNCTION;
		}
	}

	private static void layStretch(TrackPlan line, TemplateSlice[] slices, int first, int last, int[] doubled,
			boolean[] secondBeside) {
		TunnelTemplate template = doubled[first] != 0 ? TunnelTemplate.DOUBLE : TunnelTemplate.SINGLE;
		// The double template's middle lies between the rails, on the side of the other rail.
		int middle = doubled[first] * DOUBLE_TRACK / 2;
		Direction forward = line.direction(first, first + 1);
		boolean jointIn = isPiece(doubled[first - 1]);
		boolean jointOut = isPiece(doubled[last + 1]);
		if (jointIn || jointOut) {
			// The ways out of a corner piece end like the last slice of a section, a junction's like the first.
			int leadIn = jointIn && doubled[first - 1] == IN_JUNCTION ? 1 : 0;
			int leadOut = jointOut && doubled[last + 1] == IN_JUNCTION ? 1 : 0;
			layJoined(line, slices, first, last, jointIn, jointOut, template, middle, leadIn, leadOut);
			return;
		}
		int inside = template.inside();
		int period = template.period();
		if (last - first + 1 < 2 * inside + 1) {
			return;
		}
		boolean portalIn = line.sections()[first - 1] != Section.TUNNEL;
		boolean portalOut = line.sections()[last + 1] != Section.TUNNEL;
		int spare = Math.floorMod(last - first + 1 - 2 * inside - 1, period);
		if (spare != 0) {
			int more = period - spare;
			if (portalOut && fitsOutside(line, slices, last, 1, more + template.outside, doubled, secondBeside)) {
				last += more;
			} else if (portalIn && fitsOutside(line, slices, first, -1, more + template.outside, doubled, secondBeside)) {
				first -= more;
			} else {
				last -= spare;
				portalOut = false;
			}
		}
		int at = first;
		for (int k = template.outside; k < template.entrance.size(); k++) {
			slices[at++] = new TemplateSlice(template, template.entrance.get(k), forward, middle, false);
		}
		int sections = (last - first + 1 - 2 * inside - 1) / period;
		for (int n = 0; n <= sections; n++) {
			List<TunnelTemplate.Slice> section = n % 2 == 0 ? template.sectionA : template.sectionB;
			for (int k = 0; k < (n < sections ? period : 1); k++) {
				slices[at++] = new TemplateSlice(template, section.get(k), forward, middle, false);
			}
		}
		for (int k = 0; k < inside; k++) {
			slices[at++] = new TemplateSlice(template, template.exit.get(k), forward, middle, false);
		}
		if (portalIn && fitsOutside(line, slices, first, -1, template.outside, doubled, secondBeside)) {
			for (int k = 0; k < template.outside; k++) {
				slices[first - template.outside + k] = new TemplateSlice(template, template.entrance.get(k), forward, middle,
						true);
			}
		}
		if (portalOut && fitsOutside(line, slices, last, 1, template.outside, doubled, secondBeside)) {
			for (int k = 0; k < template.outside; k++) {
				slices[last + 1 + k] = new TemplateSlice(template, template.exit.get(inside + k), forward, middle, true);
			}
		}
	}

	/**
	 * A stretch that meets a junction or a corner piece at one end. On the double tunnel's side of a junction it is laid
	 * from the portal towards the junction, ending in a whole section against it; on a single tunnel's side it is laid
	 * from the piece out, after a junction from the second slice of the first section, whose first one the junction's
	 * last slice stands in for (a lead of 1), after a corner piece from the first (a lead of 0). At the portal end the
	 * portal reaches out over the track in front to take what is left over, or where it cannot the section there is cut
	 * short; with no room for sections the portal stands right against the piece. Between two pieces the sections run
	 * from one to the other, from a junction's end where one is a junction.
	 */
	private static void layJoined(TrackPlan line, TemplateSlice[] slices, int first, int last, boolean jointIn,
			boolean jointOut, TunnelTemplate template, int middle, int leadIn, int leadOut) {
		int inside = template.inside();
		int period = template.period();
		if (jointIn && jointOut) {
			int step = leadOut > leadIn ? -1 : 1;
			int from = step > 0 ? first : last;
			Direction forward = line.direction(from, from + step);
			int start = step > 0 ? leadIn : leadOut;
			for (int k = 0; k <= last - first; k++) {
				slices[from + step * k] = sectionSlice(template, start + k, forward, step * middle);
			}
			return;
		}
		boolean intoJunction = template == TunnelTemplate.DOUBLE;
		// The slices are laid from {@code from} to {@code to}, facing the way they run; the portal stands before
		// {@code from} on the way into the junction, after {@code to} on the way out of it.
		int step = intoJunction == jointOut ? 1 : -1;
		int from = step > 0 ? first : last;
		int to = step > 0 ? last : first;
		Direction forward = line.direction(from, from + step);
		int facing = step * middle;
		int length = (to - from) * step + 1;
		if (length < inside) {
			int lacking = inside - length;
			if (intoJunction && straightOutside(line, slices, from, -step, lacking)) {
				from -= step * lacking;
			} else if (!intoJunction && straightOutside(line, slices, to, step, lacking)) {
				to += step * lacking;
			} else {
				return;
			}
			length = inside;
		}
		int count = length - inside;
		int start = 0;
		if (intoJunction) {
			// Whole sections against the junction; the one at the portal is cut short unless the portal reaches out.
			int more = Math.floorMod(-count, period);
			if (more > 0 && straightOutside(line, slices, from, -step, more + template.outside)) {
				from -= step * more;
				count += more;
			} else {
				start = more;
			}
		} else if (count > 0) {
			// After a junction its last slice stands in for the first slice of the first section, so the sections run on
			// from the second one; after a corner piece they start whole. The last is closed by the first slice of the
			// next before the exit.
			start = jointIn ? leadIn : leadOut;
			int more = Math.floorMod(1 - start - count, period);
			if (more > 0 && straightOutside(line, slices, to, step, more + template.outside)) {
				to += step * more;
				count += more;
			}
		}
		int at = from;
		if (intoJunction) {
			for (int k = template.outside; k < template.entrance.size(); k++, at += step) {
				slices[at] = new TemplateSlice(template, template.entrance.get(k), forward, facing, false);
			}
		}
		for (int n = start; n < start + count; n++, at += step) {
			slices[at] = sectionSlice(template, n, forward, facing);
		}
		if (!intoJunction) {
			for (int k = 0; k < inside; k++, at += step) {
				slices[at] = new TemplateSlice(template, template.exit.get(k), forward, facing, false);
			}
		}
		if (intoJunction && straightOutside(line, slices, from, -step, template.outside)) {
			for (int k = 0; k < template.outside; k++) {
				slices[from - step * (template.outside - k)] = new TemplateSlice(template, template.entrance.get(k), forward,
						facing, true);
			}
		}
		if (!intoJunction && straightOutside(line, slices, to, step, template.outside)) {
			for (int k = 0; k < template.outside; k++) {
				slices[to + step * (k + 1)] = new TemplateSlice(template, template.exit.get(inside + k), forward, facing,
						true);
			}
		}
	}

	/** Slice n of the sections taking turns along a tunnel, counted from the first slice of a section A. */
	private static TemplateSlice sectionSlice(TunnelTemplate template, int n, Direction forward, int middle) {
		int period = template.period();
		List<TunnelTemplate.Slice> section = n / period % 2 == 0 ? template.sectionA : template.sectionB;
		return new TemplateSlice(template, section.get(n % period), forward, middle, false);
	}

	/**
	 * Whether the {@code count} blocks beyond block {@code from} of a line, in direction {@code step}, run on straight and
	 * level with it, so a portal may stand there: clear of corners but for one two blocks off, of halts and of other
	 * slices.
	 */
	private static boolean straightOutside(TrackPlan line, TemplateSlice[] slices, int from, int step, int count) {
		for (int k = 1; k <= count; k++) {
			int j = from + step * k;
			if (j < 1 || j > line.length() - 2 || slices[j] != null || line.track()[j] != line.track()[from]
					|| line.corner()[j - 1] || line.corner()[j] || line.corner()[j + 1]) {
				return false;
			}
			for (int halt : line.halts()) {
				if (Math.abs(halt - j) <= HaltPlanner.HALF_LENGTH + 1) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Whether the {@code count} blocks beyond block {@code from} of a line, in direction {@code step}, are open ground
	 * a portal can stand on or reach out over: level and straight like the stretch, clear of halts and of other slices.
	 */
	private static boolean fitsOutside(TrackPlan line, TemplateSlice[] slices, int from, int step, int count,
			int[] doubled, boolean[] secondBeside) {
		int reach = secondBeside[from] ? DOUBLE_TRACK + 2 : 2;
		for (int k = 1; k <= count; k++) {
			int j = from + step * k;
			if (j < 1 || j > line.length() - 2 || slices[j] != null || line.track()[j] != line.track()[from]
					|| doubled[j] != doubled[from]) {
				return false;
			}
			Section section = line.sections()[j];
			if (section != Section.FLAT && section != Section.FILL && section != Section.TRENCH) {
				return false;
			}
			for (int c = Math.max(0, j - reach); c <= Math.min(line.length() - 1, j + reach); c++) {
				if (line.corner()[c]) {
					return false;
				}
			}
			for (int halt : line.halts()) {
				if (Math.abs(halt - j) <= HaltPlanner.HALF_LENGTH + 1) {
					return false;
				}
			}
		}
		return true;
	}

	/** Flat track, fill or trench: clear the cut, lay the cobblestone bed, prop it up where the ground is hollow. */
	private void buildOpen(int i, int x, int z, int track, int distance, Section section) {
		int ground = surface(x, z, track);
		// A cut never goes deeper than planned, even where the real ground stands higher than the noise.
		int top = Math.min(ground, cutTop(i));
		if (distance <= 1) {
			for (int y = track; y <= Math.max(track + 2, top - 1); y++) {
				clear(x, y, z);
			}
			if (section == Section.FILL) {
				for (int y = ground; y < track; y++) {
					build(x, y, z, STONE);
				}
			}
			if (lining) {
				fillUnder(x, track - 2, z);
				set(x, track - 1, z, STONE);
			}
			return;
		}
		// The sides of a cut slope back one block per block of height.
		for (int y = track + distance - 1; y < top; y++) {
			clear(x, y, z);
		}
	}

	private void buildTunnel(int i, int x, int z, int track, int distance) {
		// On a ramp the tunnel is a block higher, so a rider going down does not meet the ceiling ahead.
		boolean ramp = isRamp(i);
		int height = ramp ? 5 : 3;
		// A straight stretch too short for a template, and the corner of a double line, are lined with stone bricks.
		boolean bricks = lining && isBricked(i);
		if (distance <= 1) {
			for (int y = track; y < track + height; y++) {
				clear(x, y, z);
			}
			if (!lining) {
				return;
			}
			BlockState floor = level.getBlockState(pos.set(x, track - 1, z));
			if (floor.isAir()) {
				// A cave: a plank deck on pillars instead of a tube of stone.
				set(x, track - 1, z, DECK);
				support(i, x, z, track, distance);
			} else {
				seal(x, track - 1, z);
			}
			if (bricks) {
				brick(x, track + height, z);
			} else {
				seal(x, track + height, z);
			}
			if (ramp && level.getBlockState(pos.set(x, track + height, z)).isSolid()) {
				// The top block becomes an upside-down stair that steps down towards the lower end of the ramp.
				set(x, track + height - 1, z, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
						.setValue(StairBlock.HALF, Half.TOP)
						.setValue(StairBlock.FACING, uphill(i).getOpposite()));
			}
			Direction fromRamp = ramp ? null : fromTunnelRamp(i);
			if (fromRamp != null && level.getBlockState(pos.set(x, track + height, z)).isSolid()) {
				// Where the high ceiling over a ramp drops to the ordinary one, the first low block steps down too.
				set(x, track + height, z, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
						.setValue(StairBlock.HALF, Half.TOP)
						.setValue(StairBlock.FACING, fromRamp));
			}
		} else if (distance == 2 && lining) {
			for (int y = track - 1; y <= track + height; y++) {
				if (bricks && y >= track) {
					brick(x, y, z);
				} else {
					seal(x, y, z);
				}
			}
		}
	}

	/**
	 * A wall or ceiling block of a tunnel lined with bricks: stone bricks over the rock, some cracked and some mossy as
	 * in an old stronghold, leaving caves open.
	 */
	private void brick(int x, int y, int z) {
		if (!carved.contains(BlockPos.asLong(x, y, z)) && !level.getBlockState(pos.set(x, y, z)).isAir()) {
			int roll = Math.floorMod(mix(x, mix(y, z)), 100);
			set(x, y, z, roll < 15 ? CRACKED_BRICKS : roll < 27 ? MOSSY_BRICKS : BRICKS);
		}
	}

	/**
	 * Over a cave bridge with frames the rock is cleared two blocks above the frames, so their top rails stand free,
	 * and sealed above that.
	 */
	private void clearOverFrame(int x, int z, int track, int distance) {
		if (distance <= 1) {
			clear(x, track + 3, z);
			clear(x, track + 4, z);
			if (lining) {
				seal(x, track + 5, z);
			}
		} else if (distance == 2 && lining) {
			for (int y = track + 3; y <= track + 5; y++) {
				seal(x, y, z);
			}
		}
	}

	/**
	 * A bridge: in the open a cobblestone deck on cobblestone pillars; under ground, across a cave, a plank deck on
	 * plank pillars like the tunnel's. {@code offset} counts across the track towards the torch side. The two rails of
	 * a double track each lay their deck, which meet between them.
	 */
	private void buildBridge(int i, int x, int z, int track, int offset) {
		int distance = Math.abs(offset);
		if (distance > 1) {
			return;
		}
		for (int y = track; y <= track + 2; y++) {
			clear(x, y, z);
		}
		boolean underground = isUnderground(x, z, track);
		if (!underground) {
			// A bank higher than the bridge, where the planned ground dips but the real one does not, is cut back as in
			// a cut, so none of it hangs over the track.
			for (int y = track + 3; y < Math.min(surface(x, z, track), cutTop(i)); y++) {
				clear(x, y, z);
			}
		}
		if (!lining) {
			return;
		}
		if (underground) {
			set(x, track - 1, z, DECK);
			support(i, x, z, track, distance);
			return;
		}
		set(x, track - 1, z, STONE);
		if (distance == 0 && i % settings.pillarSpacing() == 0) {
			for (int y = track - 2; y > level.getMinY(); y--) {
				BlockState state = level.getBlockState(pos.set(x, y, z));
				if (state.isSolid() && state.getFluidState().isEmpty()) {
					break;
				}
				set(x, y, z, STONE);
			}
		}
	}

	/** Whether rock stands somewhere above a column: the line is in a cave, not under the sky. */
	private boolean isUnderground(int x, int z, int track) {
		for (int y = track + 4; y < track + SCAN_UP * 2; y++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isSolid() && !isPlant(state)) {
				return true;
			}
		}
		return false;
	}

	private void placeTrack(int i, Direction side) {
		if (inJunction(i)) {
			// The junction's own rails and levers make its switches.
			return;
		}
		int x = plan.x()[i];
		int z = plan.z()[i];
		int track = plan.track()[i];
		boolean end = i == 0 || i == plan.length() - 1;
		if (end) {
			placeBuffer(i);
		}
		// Where a line meets its station track the track's rail stays, a switch where the line meets its middle.
		if (end && level.getBlockState(pos.set(x, track, z)).getBlock() instanceof BaseRailBlock) {
			return;
		}
		RailShape shape = railShape(plan, i);
		boolean slope = shape.isSlope();
		Direction branch = branchSide(i);
		if (branch != null && !isCurve(shape)) {
			// A switch starts the way the game shapes a rail with three neighbours and no power, towards the south or the
			// east, so its lever, off, leaves it so and turns it the other way when on.
			shape = curve(alongX(i) ? Direction.EAST : Direction.SOUTH, branch);
		}
		Turnout turnout = turnout(i);
		if (turnout != null) {
			// Set from the single line into the second rail: a cart from the single line takes the second rail, one from
			// either rail of the double track runs on into the single line, so none is turned back.
			shape = curve(turnout.single(), turnout.branch());
			placeSwitchLever(i, turnout);
		}
		boolean torch = hasTorch(i);
		// A junction is a plain rail, which its lever switches.
		boolean besideTorch = branch == null && !isCurve(shape) && (isLongGroup(i - 1) || isLongGroup(i + 1));
		if (slope || torch || besideTorch) {
			set(x, track, z, Blocks.POWERED_RAIL.defaultBlockState()
					.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, shape)
					.setValue(BlockStateProperties.POWERED, true));
			if (besideTorch && !isDeck(i) && !isOpenBridge(i) && section(i) != Section.TUBE) {
				// Each powered rail of a group on the ground has its own torch, so none waits on its neighbour's power.
				placeHiddenTorch(x, track, z);
			}
		} else {
			set(x, track, z, Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape));
		}
		clearPostAbove(x, track, z);
		if (branch != null) {
			if (!plan.corner()[i]) {
				// A station bending into its only line at the middle has nothing to switch.
				placeJunctionLever(i, branch.getOpposite());
			}
		}
		Section section = section(i);
		if (slope) {
			if (section != Section.BRIDGE) {
				// Steps beside the rail, so the sides of a ramp are walkable.
				placeStep(x + side.getStepX(), track, z + side.getStepZ(), section, uphill(i));
				placeStep(x - side.getStepX(), track, z - side.getStepZ(), section, uphill(i));
			}
			return;
		}
		if (isCurve(shape)) {
			return;
		}
		// On a double track torches and lamps stand between the two rails, where one serves both.
		Direction pair = pairSide(i, side);
		Direction lit = pair != null ? pair : side;
		if (pair != null) {
			clearFarSide(i, pair, torch);
		}
		if (torch && (isDeck(i) || isOpenBridge(i) || section == Section.TUBE)) {
			placeTorch(x + lit.getStepX(), track, z + lit.getStepZ());
		} else if (torch) {
			placeHiddenTorch(x, track, z);
		}
		if (hasFrame(i)) {
			placeFrame(i, side);
		} else if (isShortDeck(i) || isOpenBridge(i)) {
			placeBridgeSides(i, side, pair);
		} else if (section == Section.TUNNEL && templated(i) == null) {
			if (isLampSpot(i, 0)) {
				placePost(x + side.getStepX(), track, z + side.getStepZ(), FENCE);
				placePost(x - side.getStepX(), track, z - side.getStepZ(), FENCE);
			}
			if (Math.floorMod(along(i), LAMP_SPACING) == LAMP_SPACING / 2) {
				hangLantern(x + side.getStepX(), track, z + side.getStepZ());
				hangLantern(x - side.getStepX(), track, z - side.getStepZ());
			}
		} else if (section != Section.BRIDGE && section != Section.TUNNEL && section != Section.TUBE
				&& templated(i) == null && !isWesternStation() && isLampSpot(i, LAMP_SPACING / 2)) {
			placePost(x + lit.getStepX(), track, z + lit.getStepZ(),
					Blocks.COPPER_LANTERN.unaffected().defaultBlockState());
		}
	}

	/**
	 * The western house at the middle of the current station track, the part of it in this chunk: turned so the
	 * house stands on the left of the track, away from the village, with ground filled in under it. The structure's
	 * own rails are left out; the station lays its rails, with their powered rails, itself.
	 */
	private void placeWesternHouse() {
		StructureTemplate house = StructureFile.template(WESTERN_STATION);
		int middle = plan.length() / 2;
		Direction out = plan.direction(middle, middle + 1).getCounterClockWise();
		StructurePlaceSettings settings = westernSettings(out)
				.setBoundingBox(new BoundingBox(chunk.getMinBlockX(), level.getMinY(), chunk.getMinBlockZ(), chunk.getMaxBlockX(),
						level.getMaxY(), chunk.getMaxBlockZ()));
		BlockPos origin = westernOrigin(plan, middle);
		BoundingBox box = house.getBoundingBox(settings, origin);
		if (box.maxX() < chunk.getMinBlockX() || box.minX() > chunk.getMaxBlockX() || box.maxZ() < chunk.getMinBlockZ()
				|| box.minZ() > chunk.getMaxBlockZ()) {
			return;
		}
		house.placeInWorld(level, origin, origin, settings, RandomSource.create(origin.asLong()), WRITE);
		for (int x = Math.max(box.minX(), chunk.getMinBlockX()); x <= Math.min(box.maxX(), chunk.getMaxBlockX()); x++) {
			for (int z = Math.max(box.minZ(), chunk.getMinBlockZ()); z <= Math.min(box.maxZ(), chunk.getMaxBlockZ()); z++) {
				if (level.getBlockState(pos.set(x, box.minY(), z)).isSolid()) {
					fillUnder(x, box.minY() - 1, z);
				}
			}
		}
	}

	/**
	 * Two dispensers with minecarts by the door of a western house, one facing each track, a button on each and a
	 * powered rail over a hidden torch in front.
	 */
	private void placeWesternDispensers() {
		int middle = plan.length() / 2;
		Direction out = plan.direction(middle, middle + 1).getCounterClockWise();
		Rotation rotation = westernRotation(out);
		Direction towardsTracks = rotation.rotate(Direction.NORTH);
		Direction along = plan.direction(middle, middle + 1);
		BlockPos origin = westernOrigin(plan, middle);
		for (int trackZ : new int[] {WESTERN_TRACK_Z, WESTERN_TRACK_Z - DOUBLE_TRACK}) {
			BlockPos dispenser = westernBlock(origin, rotation, WESTERN_DISPENSER_X, WESTERN_RAIL_Y, trackZ + 1);
			BlockPos rail = westernBlock(origin, rotation, WESTERN_DISPENSER_X, WESTERN_RAIL_Y, trackZ);
			if (!inChunk(dispenser.getX(), dispenser.getZ())) {
				continue;
			}
			set(dispenser.getX(), dispenser.getY(), dispenser.getZ(), Blocks.DISPENSER.defaultBlockState()
					.setValue(BlockStateProperties.FACING, towardsTracks));
			if (level.getBlockEntity(dispenser) instanceof DispenserBlockEntity carts) {
				carts.setItem(0, new ItemStack(Items.MINECART));
				carts.setItem(1, new ItemStack(Items.MINECART));
			}
			set(dispenser.getX(), dispenser.getY() + 1, dispenser.getZ(), Blocks.STONE_BUTTON.defaultBlockState()
					.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
					.setValue(BlockStateProperties.HORIZONTAL_FACING, along));
			if (inChunk(rail.getX(), rail.getZ())
					&& level.getBlockState(rail).getBlock() instanceof BaseRailBlock) {
				set(rail.getX(), rail.getY(), rail.getZ(), Blocks.POWERED_RAIL.defaultBlockState()
						.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT,
								along.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
						.setValue(BlockStateProperties.POWERED, true));
				placeHiddenTorch(rail.getX(), rail.getY(), rail.getZ());
			}
		}
	}

	/** Where the western structure's corner goes for a station whose middle is block {@code middle} of its track. */
	private static BlockPos westernOrigin(TrackPlan line, int middle) {
		return new BlockPos(line.x()[middle] - WESTERN_MIDDLE_X, line.track()[middle] - WESTERN_RAIL_Y,
				line.z()[middle] - WESTERN_TRACK_Z);
	}

	private static StructurePlaceSettings westernSettings(Direction out) {
		return new StructurePlaceSettings()
				.setRotation(westernRotation(out))
				.setRotationPivot(new BlockPos(WESTERN_MIDDLE_X, WESTERN_RAIL_Y, WESTERN_TRACK_Z))
				// The cushions on the benches; each goes in with the chunk it stands in.
				.setIgnoreEntities(false)
				// Placed a chunk at a time: the blocks at a chunk's edge keep their shape instead of being updated against
				// a neighbour that is not built yet, which would drop a lantern or a button hanging there.
				.setKnownShape(true)
				.addProcessor(new BlockIgnoreProcessor(List.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.STRUCTURE_VOID)));
	}

	/** The turn that brings the structure's house side, its +z, round to {@code out}. */
	private static Rotation westernRotation(Direction out) {
		return switch (out) {
			case WEST -> Rotation.CLOCKWISE_90;
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/** The item frames of the map wall in a western house, the one over its door, and the way they face. */
	public record MapWall(List<BlockPos> frames, BlockPos door, Direction facing) {
	}

	/**
	 * Where the map wall hangs in the western house of a station track: rows of item frames on the wall facing the
	 * tracks, in the order {@link NetworkMapItem#wall} gives its maps, north row first and west column first. The
	 * columns run the way the maps join up seen from in front, whichever way the house is turned.
	 */
	public static MapWall mapWall(TrackPlan station) {
		int middle = station.length() / 2;
		Rotation rotation = westernRotation(station.direction(middle, middle + 1).getCounterClockWise());
		BlockPos center = westernBlock(westernOrigin(station, middle), rotation, WALL_X, WALL_Y, WALL_Z);
		Direction facing = rotation.rotate(Direction.NORTH);
		int count = NetworkMapItem.STATION_WALL;
		List<BlockPos> frames = new ArrayList<>();
		for (int row = 0; row < count; row++) {
			for (int column = 0; column < count; column++) {
				// Seen from in front, the left is the frames' clockwise side, where the west column goes.
				frames.add(center.above((count - 1) / 2 - row)
						.relative(facing.getCounterClockWise(), column - (count - 1) / 2));
			}
		}
		BlockPos door = westernBlock(westernOrigin(station, middle), rotation, WALL_X, WALL_Y, DOOR_MAP_Z);
		return new MapWall(frames, door, facing);
	}

	/** A block of the western structure in the world, given by its place in the structure. */
	private static BlockPos westernBlock(BlockPos origin, Rotation rotation, int x, int y, int z) {
		BlockPos pivot = new BlockPos(WESTERN_MIDDLE_X, WESTERN_RAIL_Y, WESTERN_TRACK_Z);
		return StructureTemplate.transform(new BlockPos(x, y, z), Mirror.NONE, rotation, pivot)
				.offset(origin);
	}

	/**
	 * A buffer stop, a hay bale across the track, beyond the end of a track where no rail goes on: the end of a station
	 * no line uses. Not a log, which clearing trees would take.
	 */
	private void placeBuffer(int i) {
		if (meetsOtherTrack(plan.x()[i], plan.z()[i])) {
			// A line ending on a station's rail, coming in from the side.
			return;
		}
		Direction out = i == 0 ? plan.direction(1, 0) : plan.direction(i - 1, i);
		int x = plan.x()[i] + out.getStepX();
		int z = plan.z()[i] + out.getStepZ();
		int track = plan.track()[i];
		if (!inChunk(x, z) || rails.containsKey(ChunkPos.pack(x, z))
				|| level.getBlockState(pos.set(x, track, z)).getBlock() instanceof BaseRailBlock) {
			return;
		}
		set(x, track, z, Blocks.HAY_BLOCK.defaultBlockState()
				.setValue(BlockStateProperties.AXIS, out.getClockWise().getAxis()));
		fillUnder(x, track - 1, z);
	}

	/** Whether another track being built has a block at a position. */
	private boolean meetsOtherTrack(int x, int z) {
		for (TrackPlan other : plans) {
			if (other == plan) {
				continue;
			}
			for (int j = 0; j < other.length(); j++) {
				if (other.x()[j] == x && other.z()[j] == z) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether the current track is a western station's, or its second track, which have the house's own lamps. */
	private boolean isWesternStation() {
		TrackPlan line = lineOf.get(plan);
		return plan.western() || line != null && line.western();
	}

	/** The side a line meets block i from, when this station track has its switch there, or null. */
	private Direction branchSide(int i) {
		for (int junction : plan.junctions()) {
			if (junction / 2 == i) {
				Direction right = plan.direction(i, i - 1).getClockWise();
				return junction % 2 == 1 ? right : right.getOpposite();
			}
		}
		return null;
	}

	/**
	 * The lever that sets a junction, on the station platform on the side away from its branch, where it powers the
	 * block it sits on and so the rail.
	 */
	private void placeJunctionLever(int i, Direction away) {
		int x = plan.x()[i] + away.getStepX();
		int z = plan.z()[i] + away.getStepZ();
		int track = plan.track()[i];
		BlockState lever = Blocks.LEVER.defaultBlockState()
				.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, away);
		placeIfFree(x, track + 1, z, lever);
	}

	/**
	 * The switch where a second rail leaves or joins the current line at block i, or null: where the second rail steps
	 * out beside the line or back in. Not at a western station, where the second rail runs on into the station's second
	 * track.
	 */
	private Turnout turnout(int i) {
		if (lineOf.containsKey(plan)) {
			return null;
		}
		Direction back = i > 0 ? plan.direction(i, i - 1) : plan.direction(i, i + 1).getOpposite();
		for (TrackPlan second : plan.seconds()) {
			for (int end : new int[] {0, second.length() - 1}) {
				int dx = second.x()[end] - plan.x()[i];
				int dz = second.z()[end] - plan.z()[i];
				if (second.beside()[end] == i && Math.abs(dx) + Math.abs(dz) == 1) {
					return new Turnout(Direction.getNearest(dx, 0, dz, back), end == 0 ? back : back.getOpposite());
				}
			}
		}
		return null;
	}

	/**
	 * The lever of a switch, beside the rail on the side away from the second rail. A switch flips between its two
	 * curves, the south or east one without power and the north or west one with it, so the lever is on where the curve
	 * into the second rail is the north or west one.
	 */
	private void placeSwitchLever(int i, Turnout turnout) {
		Direction away = turnout.branch().getOpposite();
		boolean on = turnout.single() == Direction.NORTH || turnout.single() == Direction.WEST;
		placeIfFree(plan.x()[i] + away.getStepX(), plan.track()[i], plan.z()[i] + away.getStepZ(),
				Blocks.LEVER.defaultBlockState()
						.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
						.setValue(BlockStateProperties.HORIZONTAL_FACING, turnout.single())
						.setValue(BlockStateProperties.POWERED, on));
	}

	/**
	 * Torches go beside flat, straight rail: on every {@value #POWER_SPACING}th block along the axis, and at the foot
	 * of every ramp, which powers its sloping rails. Counting by position in the world rather than along the line puts
	 * the torches of the two rails of a double track side by side.
	 */
	private boolean hasTorch(int i) {
		RailShape shape = railShape(plan, i);
		// Never at the end of a line, where it meets its station track, nor on a switch.
		if (i == 0 || i == plan.length() - 1 || isCurve(shape) || shape.isSlope() || branchSide(i) != null
				|| turnout(i) != null) {
			return false;
		}
		return Math.floorMod(along(i), POWER_SPACING) == 0 || isRampFoot(i);
	}

	/** Whether block i has a torch with three powered rails: every second torch on flat track. */
	private boolean isLongGroup(int i) {
		return i >= 0 && i < plan.length() && Math.floorMod(along(i), POWER_SPACING * 2) == 0 && hasTorch(i);
	}

	/** Whether block i is the flat block at the lower end of a ramp, beside its first sloping rail. */
	private boolean isRampFoot(int i) {
		for (int j : new int[] {i - 1, i + 1}) {
			if (j >= 0 && j < plan.length() && plan.track()[j] == plan.track()[i] && railShape(plan, j).isSlope()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a post or lamp goes at block i. They are due every {@value #LAMP_SPACING} blocks along the axis from
	 * {@code offset}; when the due block has a torch, or is a slope or a curve, the post moves one block towards +x or
	 * +z, or else one block back.
	 */
	private boolean isLampSpot(int i, int offset) {
		int at = along(i);
		for (int shift = -1; shift <= 1; shift++) {
			if (Math.floorMod(at + shift - offset, LAMP_SPACING) != 0) {
				continue;
			}
			for (int candidate : new int[] {shift, shift + 1, shift - 1}) {
				int j = blockAt(i, candidate);
				if (j >= 0 && fitsPost(j)) {
					return j == i;
				}
			}
		}
		return false;
	}

	private boolean fitsPost(int i) {
		RailShape shape = railShape(plan, i);
		return !shape.isSlope() && !isCurve(shape) && !hasTorch(i);
	}

	/** Position of block i along the axis it runs on. */
	private int along(int i) {
		return alongX(i) ? plan.x()[i] : plan.z()[i];
	}

	private boolean alongX(int i) {
		int neighbour = i + 1 < plan.length() ? i + 1 : i - 1;
		return plan.x()[neighbour] != plan.x()[i];
	}

	/** The block of the line {@code k} blocks from block i along its axis, towards +x or +z, or -1 if there is none. */
	private int blockAt(int i, int k) {
		int x = plan.x()[i] + (alongX(i) ? k : 0);
		int z = plan.z()[i] + (alongX(i) ? 0 : k);
		for (int j : new int[] {i + k, i - k}) {
			if (j >= 0 && j < plan.length() && plan.x()[j] == x && plan.z()[j] == z) {
				return j;
			}
		}
		return -1;
	}

	/** The side of block i where another line's rail runs level with it, two blocks over, or null. */
	private Direction pairSide(int i, Direction side) {
		for (Direction towards : new Direction[] {side, side.getOpposite()}) {
			int[] other = rails.get(ChunkPos.pack(plan.x()[i] + towards.getStepX() * DOUBLE_TRACK,
					plan.z()[i] + towards.getStepZ() * DOUBLE_TRACK));
			if (other != null && other[0] != planIndex && other[1] == plan.track()[i]) {
				return towards;
			}
		}
		return null;
	}

	/**
	 * Takes away the torch or lamp post that the other rail of a double track, built before this one was planned, put
	 * on its far side: the ones between the rails take their place.
	 */
	private void clearFarSide(int i, Direction pair, boolean torch) {
		int x = plan.x()[i] + pair.getStepX() * (DOUBLE_TRACK + 1);
		int z = plan.z()[i] + pair.getStepZ() * (DOUBLE_TRACK + 1);
		int track = plan.track()[i];
		// A third track beyond, another line running beside the double track, needs what stands there.
		int beyondX = x + pair.getStepX();
		int beyondZ = z + pair.getStepZ();
		if (rails.containsKey(ChunkPos.pack(beyondX, beyondZ))
				|| level.getBlockState(pos.set(beyondX, track, beyondZ)).getBlock() instanceof BaseRailBlock) {
			return;
		}
		if (torch && level.getBlockState(pos.set(x, track, z)).is(Blocks.REDSTONE_TORCH)) {
			set(x, track, z, AIR);
		}
		if (section(i) != Section.TUNNEL && isLampSpot(i, LAMP_SPACING / 2)
				&& level.getBlockState(pos.set(x, track, z)).is(Blocks.OAK_FENCE)
				&& level.getBlockState(pos.set(x, track + 1, z)).is(Blocks.OAK_FENCE)
				&& level.getBlockState(pos.set(x, track + 2, z)).getBlock() instanceof LanternBlock) {
			for (int y = track; y <= track + 2; y++) {
				set(x, y, z, AIR);
			}
		}
	}

	/**
	 * On solid ground the torch hides under the rail: a cobblestone block under the powered rail, and the torch under
	 * that, which powers the block and so the rail. Its pocket is closed on every side, so no water reaches it.
	 */
	private void placeHiddenTorch(int x, int track, int z) {
		set(x, track - 1, z, STONE);
		set(x, track - 2, z, Blocks.REDSTONE_TORCH.defaultBlockState());
		BlockState below = level.getBlockState(pos.set(x, track - 3, z));
		if (!below.isSolid() || !below.getFluidState().isEmpty()) {
			set(x, track - 3, z, STONE);
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockState beside = level.getBlockState(pos.set(x + side.getStepX(), track - 2, z + side.getStepZ()));
			// A neighbouring rail's own hidden torch stays.
			if (!beside.is(Blocks.REDSTONE_TORCH) && (!beside.isSolid() || !beside.getFluidState().isEmpty())) {
				set(x + side.getStepX(), track - 2, z + side.getStepZ(), STONE);
			}
		}
	}

	/**
	 * A copper lantern for a railing. Over a bridge in the open each one has weathered on its own, so they stand at
	 * every stage from bright copper to green; the stage comes from the position, so every chunk agrees.
	 */
	private static BlockState railingLantern(int x, int z, boolean weathered) {
		WeatheringCopper.WeatherState[] stages = WeatheringCopper.WeatherState.values();
		int stage = weathered ? Math.floorMod(x * 73856093 ^ z * 19349663, stages.length) : 0;
		return (switch (stages[stage]) {
			case UNAFFECTED -> Blocks.COPPER_LANTERN.unaffected();
			case EXPOSED -> Blocks.COPPER_LANTERN.exposed();
			case WEATHERED -> Blocks.COPPER_LANTERN.weathered();
			case OXIDIZED -> Blocks.COPPER_LANTERN.oxidized();
		}).defaultBlockState();
	}

	/** A torch beside the rail. A post that stood there gives way, so nothing stands on a torch. */
	private void placeTorch(int x, int y, int z) {
		set(x, y, z, Blocks.REDSTONE_TORCH.defaultBlockState());
		clearPostAbove(x, y, z);
	}

	/**
	 * Takes away what is left of a post above a block that a torch or rail has taken: the fences and the lantern of
	 * another line's lamp post where this line runs.
	 */
	private void clearPostAbove(int x, int y, int z) {
		for (int above = y + 1; above <= y + 2; above++) {
			BlockState state = level.getBlockState(pos.set(x, above, z));
			if (!state.is(Blocks.OAK_FENCE) && !(state.getBlock() instanceof LanternBlock)) {
				return;
			}
			set(x, above, z, AIR);
		}
	}

	/** Two fences with {@code top} on them, only where the foot is free, so a post never stands on a torch. */
	private void placePost(int x, int y, int z, BlockState top) {
		if (!inChunk(x, z) || !level.getBlockState(pos.set(x, y, z)).isAir()) {
			return;
		}
		set(x, y, z, FENCE);
		placeIfFree(x, y + 1, z, FENCE);
		placeIfFree(x, y + 2, z, top);
	}

	/**
	 * What is built at block i. The plan's section comes from the generator's noise; where the real ground still
	 * covers the line higher than a cut may reach, under an overhang or a hill the noise did not show, it becomes a
	 * tunnel, so no cut is left with a roof of dirt.
	 */
	private Section section(int i) {
		Section[] known = sections.computeIfAbsent(plan, line -> new Section[line.length()]);
		if (known[i] == null) {
			Section planned = plan.sections()[i];
			int x = plan.x()[i];
			int z = plan.z()[i];
			int track = plan.track()[i];
			boolean open = planned == Section.FLAT || planned == Section.FILL || planned == Section.TRENCH;
			boolean covered = open && reachable.test(x >> 4, z >> 4) && surface(x, z, track) > cutTop(i);
			known[i] = covered ? Section.TUNNEL : planned;
		}
		return known[i];
	}

	/**
	 * The first y an open cut at block i leaves standing: as deep as a trench may be, or as the planned ground where the
	 * planner cut open a tunnel not worth boring.
	 */
	private int cutTop(int i) {
		int track = plan.track()[i];
		return track + Math.max(settings.cutDepth(), plan.ground()[i] - track) + 1;
	}

	/**
	 * Whether block i lies on a plank deck, or will once its chunk is built: a tunnel over air, or a bridge under
	 * ground.
	 */
	private boolean isDeck(int i) {
		byte[] known = decks.computeIfAbsent(plan, line -> new byte[line.length()]);
		if (known[i] == 0) {
			known[i] = (byte) (hasDeckBelow(i) ? 2 : 1);
		}
		return known[i] == 2;
	}

	private boolean hasDeckBelow(int i) {
		int x = plan.x()[i];
		int z = plan.z()[i];
		int track = plan.track()[i];
		Section section = section(i);
		if ((section != Section.TUNNEL && section != Section.BRIDGE) || !reachable.test(x >> 4, z >> 4)) {
			return false;
		}
		if (section == Section.BRIDGE) {
			return isUnderground(x, z, track);
		}
		BlockState floor = level.getBlockState(pos.set(x, track - 1, z));
		return floor.isAir() || floor.is(Blocks.OAK_PLANKS);
	}

	/**
	 * Whether block i carries a frame of a cave bridge: on the plank deck, and up to {@value #FRAME_BLEND} blocks
	 * into the tunnel at either end, so the bridge grows out of the rock.
	 */
	private boolean hasFrame(int i) {
		if (i < 0 || i >= plan.length() || isRamp(i) || isCurve(railShape(plan, i))) {
			return false;
		}
		Section section = section(i);
		if (section != Section.TUNNEL && (section != Section.BRIDGE || !isDeck(i))) {
			return false;
		}
		for (int j = Math.max(0, i - FRAME_BLEND); j <= Math.min(plan.length() - 1, i + FRAME_BLEND); j++) {
			if (isDeck(j) && !isShortDeck(j)) {
				return true;
			}
		}
		return false;
	}

	/** Whether block i lies on the deck of a cave bridge shorter than {@value #SHORT_BRIDGE} blocks. */
	private boolean isShortDeck(int i) {
		if (!isDeck(i)) {
			return false;
		}
		int run = 1;
		for (int j = i - 1; j >= 0 && run < SHORT_BRIDGE && isDeck(j); j--) {
			run++;
		}
		for (int j = i + 1; j < plan.length() && run < SHORT_BRIDGE && isDeck(j); j++) {
			run++;
		}
		return run < SHORT_BRIDGE;
	}

	private boolean isOpenBridge(int i) {
		return section(i) == Section.BRIDGE && !isDeck(i);
	}

	/**
	 * The sides of a bridge in the open, or of a short one through a cave: a spruce railing along the outer edges with
	 * lanterns on the posts, scattered over a double track, whose two rails each put up the railing on their outer side.
	 */
	private void placeBridgeSides(int i, Direction side, Direction pair) {
		int x = plan.x()[i];
		int z = plan.z()[i];
		int track = plan.track()[i];
		boolean scattered = isOpenBridge(i) && pair != null;
		int[] offsets = pair != null ? new int[] {pair == side ? -1 : 1} : new int[] {-1, 1};
		Direction forward = alongX(i) ? Direction.EAST : Direction.SOUTH;
		boolean back = hasRailing(blockAt(i, -1));
		boolean ahead = hasRailing(blockAt(i, 1));
		for (int offset : offsets) {
			int railingX = x + side.getStepX() * offset;
			int railingZ = z + side.getStepZ() * offset;
			if (!inChunk(railingX, railingZ) || !level.getBlockState(pos.set(railingX, track, railingZ)).isAir()) {
				continue;
			}
			set(railingX, track, railingZ, fence(RAILING, back ? forward.getOpposite() : null, ahead ? forward : null));
			if (scattered ? isScatteredLantern(i, railingX, railingZ) : isRailingLantern(i)) {
				placeIfFree(railingX, track + 1, railingZ, railingLantern(railingX, railingZ, isOpenBridge(i)));
			}
		}
	}

	/**
	 * Whether the railing at block i carries a lantern: the middle of each repeat of its pattern, counted from the end
	 * of the bridge with the smaller coordinate.
	 */
	private boolean isRailingLantern(int i) {
		int first = i;
		int last = i;
		boolean cave = isShortDeck(i);
		while (first > 0 && (cave ? isDeck(first - 1) : isOpenBridge(first - 1))) {
			first--;
		}
		while (last < plan.length() - 1 && (cave ? isDeck(last + 1) : isOpenBridge(last + 1))) {
			last++;
		}
		int pattern = last - first + 1 <= SHORT_RAILING_BRIDGE ? SHORT_RAILING_PATTERN : LONG_RAILING_PATTERN;
		int start = Math.min(along(first), along(last));
		return Math.floorMod(along(i) - start, pattern) == pattern / 2;
	}

	/**
	 * Whether the railing of a double bridge carries a lantern at a block: in each {@value #SCATTERED_LANTERN_CELL}
	 * blocks along the axis one at a place picked from the position, or none, never two side by side.
	 */
	private boolean isScatteredLantern(int i, int railingX, int railingZ) {
		int at = along(i);
		int crossing = alongX(i) ? railingZ : railingX;
		int cell = Math.floorDiv(at, SCATTERED_LANTERN_CELL);
		int pick = scatteredLantern(cell, crossing);
		if (pick < 0 || Math.floorMod(at, SCATTERED_LANTERN_CELL) != pick) {
			return false;
		}
		return pick != 0 || scatteredLantern(cell - 1, crossing) != SCATTERED_LANTERN_CELL - 1;
	}

	/** The place of the lantern in a cell of a double bridge's railing, or -1 when it has none. */
	private static int scatteredLantern(int cell, int crossing) {
		int hash = mix(cell, crossing);
		return Math.floorMod(hash, 4) == 0 ? -1 : Math.floorMod(hash >>> 2, SCATTERED_LANTERN_CELL);
	}

	private boolean hasRailing(int i) {
		if (i < 0 || i >= plan.length()) {
			return false;
		}
		RailShape shape = railShape(plan, i);
		return !isCurve(shape) && !shape.isSlope() && !hasFrame(i) && (isShortDeck(i) || isOpenBridge(i));
	}

	/**
	 * One block of the fence frames over a cave bridge. Every {@value #FRAME_SPACING} blocks a frame stands: a post
	 * each side and a beam across over the track. Rails of fence run along the top, short arms reach out from the
	 * posts under them, and lanterns hang half way between some of the frames.
	 */
	private void placeFrame(int i, Direction side) {
		int x = plan.x()[i];
		int z = plan.z()[i];
		int track = plan.track()[i];
		Direction forward = alongX(i) ? Direction.EAST : Direction.SOUTH;
		int at = Math.floorMod(along(i), FRAME_SPACING);
		boolean post = at == FRAME_SPACING / 2;
		boolean back = hasFrame(blockAt(i, -1));
		boolean ahead = hasFrame(blockAt(i, 1));
		for (Direction towards : new Direction[] {side, side.getOpposite()}) {
			int fx = x + towards.getStepX();
			int fz = z + towards.getStepZ();
			if (post) {
				if (level.getBlockState(pos.set(fx, track, fz)).isAir()) {
					placeIfFree(fx, track, fz, FENCE);
					placeIfFree(fx, track + 1, fz, FENCE);
				}
				placeIfFree(fx, track + 2, fz, fence(FENCE, back ? forward.getOpposite() : null, ahead ? forward : null));
			} else if (at == 0) {
				if (isFrameLantern(i)) {
					placeIfFree(fx, track + 2, fz, Blocks.COPPER_LANTERN.unaffected().defaultBlockState()
							.setValue(BlockStateProperties.HANGING, true));
				}
			} else {
				placeIfFree(fx, track + 2, fz, fence(FENCE, at < FRAME_SPACING / 2 ? forward : forward.getOpposite()));
			}
			set(fx, track + 3, fz, fence(FENCE, back ? forward.getOpposite() : null, ahead ? forward : null,
					post ? towards.getOpposite() : null));
		}
		if (post) {
			set(x, track + 3, z, fence(FENCE, side, side.getOpposite()));
		}
	}

	/**
	 * Whether lanterns hang between two frames at block i. The spans between frames come in groups of five along the
	 * axis, lit one, dark one, lit one, dark two, or lit one, dark two, lit one, dark one, picked from the position: a
	 * lit span is always followed by one or two dark ones, and the two rails of a double track agree.
	 */
	private boolean isFrameLantern(int i) {
		int span = Math.floorDiv(along(i), FRAME_SPACING);
		int k = Math.floorMod(span, 5);
		return k == 0 || k == ((mix(Math.floorDiv(span, 5), 0) & 1) == 0 ? 2 : 3);
	}

	/** A fence joined to its neighbours on the given sides; the chunk is built without block updates. */
	private static BlockState fence(BlockState unjoined, Direction... joined) {
		BlockState state = unjoined;
		for (Direction direction : joined) {
			if (direction != null) {
				state = state.setValue(CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(direction), true);
			}
		}
		return state;
	}

	/**
	 * What holds up the sides of a cave bridge: an upside-down stair against the rock under each end of the deck, and
	 * under every second frame a plank pillar down to the cave floor that widens at its top like a step pyramid: three
	 * planks with a stair at each end under the deck, a stair either side of the pillar below them.
	 */
	private void support(int i, int x, int z, int track, int distance) {
		if (distance != 1 || !isDeck(i) || isShortDeck(i)) {
			return;
		}
		Direction forward = alongX(i) ? Direction.EAST : Direction.SOUTH;
		for (Direction towards : new Direction[] {forward, forward.getOpposite()}) {
			BlockState beyond = level.getBlockState(pos.set(x + towards.getStepX(), track - 1, z + towards.getStepZ()));
			if (beyond.isSolid() && !beyond.is(Blocks.OAK_PLANKS)
					&& level.getBlockState(pos.set(x, track - 2, z)).isAir()) {
				set(x, track - 2, z, Blocks.OAK_STAIRS.defaultBlockState()
						.setValue(StairBlock.HALF, Half.TOP)
						.setValue(StairBlock.FACING, towards));
			}
		}
		if (isRamp(i) || Math.floorMod(along(i), CAVE_PILLAR_SPACING) != FRAME_SPACING / 2) {
			return;
		}
		pillar(x, track - 2, z);
		for (Direction towards : new Direction[] {forward, forward.getOpposite()}) {
			int braceX = x + towards.getStepX();
			int braceZ = z + towards.getStepZ();
			BlockState brace = Blocks.OAK_STAIRS.defaultBlockState()
					.setValue(StairBlock.HALF, Half.TOP)
					.setValue(StairBlock.FACING, towards.getOpposite());
			if (level.getBlockState(pos.set(braceX, track - 2, braceZ)).isAir()) {
				set(braceX, track - 2, braceZ, DECK);
			}
			int cornerX = braceX + towards.getStepX();
			int cornerZ = braceZ + towards.getStepZ();
			if (level.getBlockState(pos.set(cornerX, track - 2, cornerZ)).isAir()) {
				set(cornerX, track - 2, cornerZ, brace);
			}
			if (level.getBlockState(pos.set(braceX, track - 3, braceZ)).isAir()) {
				set(braceX, track - 3, braceZ, brace);
			}
		}
	}

	/**
	 * A halt beside the track at block i, on side {@code away}: a stone platform along the rail, a small
	 * brick hut with glass windows behind it under an oak roof that shelters the platform, lanterns under the eaves,
	 * and a dispenser with two minecarts that a button puts on the rail. Around the hut the ground is levelled with
	 * grass. The building is the structure file {@value #HALT}, turned, or mirrored, to the side it stands on.
	 */
	private void buildHalt(int i, int pass, Direction away) {
		int track = plan.track()[i];
		// Along the track towards the side it goes on: a station bending into a line at its middle has only one half.
		Direction along = i < plan.length() - 1 && !plan.corner()[i] ? plan.direction(i, i + 1) : plan.direction(i, i - 1);
		if (pass == 0) {
			for (int u = -HaltPlanner.HALF_LENGTH; u <= HaltPlanner.HALF_LENGTH; u++) {
				for (int v = 1; v <= HaltPlanner.DEPTH; v++) {
					int x = plan.x()[i] + along.getStepX() * u + away.getStepX() * v;
					int z = plan.z()[i] + along.getStepZ() * u + away.getStepZ() * v;
					clearTrees(x, z, track);
				}
			}
		}
		StructureFile house = StructureFile.load(HALT);
		for (int fx = 0; fx < house.sizeX; fx++) {
			for (int fz = 0; fz < house.sizeZ; fz++) {
				// The file runs along +x from u = -HALF_LENGTH, away from the track along +z from the platform's edge.
				int u = fx - HaltPlanner.HALF_LENGTH;
				int v = fz + 1;
				int x = plan.x()[i] + along.getStepX() * u + away.getStepX() * v;
				int z = plan.z()[i] + along.getStepZ() * u + away.getStepZ() * v;
				for (int fy = 0; fy < house.sizeY; fy++) {
					BlockState state = house.get(fx, fy, fz);
					if (state == null) {
						continue;
					}
					if (pass == 0 && state.isAir()) {
						clear(x, track + fy, z);
					} else if (pass == 1 && !state.isAir()
							&& !(state.is(Blocks.GRASS_BLOCK) && level.getBlockState(pos.set(x, track + fy, z)).isSolid())) {
						set(x, track + fy, z, StructureFile.transform(state, along, away));
					}
				}
				if (pass == 1) {
					fillUnder(x, track - 1, z);
				}
			}
		}
		if (pass == 2) {
			placeHaltDetails(i, track, along, away);
		}
	}

	/** Fills the halt's dispenser with minecarts and makes the rail it puts them on a powered one, over a hidden torch. */
	private void placeHaltDetails(int i, int track, Direction along, Direction away) {
		int x = plan.x()[i] + along.getStepX() * 3 + away.getStepX();
		int z = plan.z()[i] + along.getStepZ() * 3 + away.getStepZ();
		if (inChunk(x, z) && level.getBlockEntity(pos.set(x, track, z)) instanceof DispenserBlockEntity dispenser) {
			dispenser.setItem(0, new ItemStack(Items.MINECART));
			dispenser.setItem(1, new ItemStack(Items.MINECART));
		}
		int railX = plan.x()[i] + along.getStepX() * 3;
		int railZ = plan.z()[i] + along.getStepZ() * 3;
		if (inChunk(railX, railZ) && level.getBlockState(pos.set(railX, track, railZ)).getBlock() instanceof BaseRailBlock) {
			set(railX, track, railZ, Blocks.POWERED_RAIL.defaultBlockState()
					.setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT,
							along.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH)
					.setValue(BlockStateProperties.POWERED, true));
			placeHiddenTorch(railX, track, railZ);
		}
	}


	/** A step beside a ramp rail: wood on a plank deck, cobblestone in a tunnel, stone in the open. */
	private void placeStep(int x, int y, int z, Section section, Direction up) {
		BlockState below = level.getBlockState(pos.set(x, y - 1, z));
		Block stairs = below.is(Blocks.OAK_PLANKS) ? Blocks.OAK_STAIRS
				: section == Section.TUNNEL ? Blocks.COBBLESTONE_STAIRS : Blocks.STONE_STAIRS;
		set(x, y, z, stairs.defaultBlockState().setValue(StairBlock.FACING, up));
	}

	/** A lantern hanging from the ceiling, or standing on the floor where a cave leaves no ceiling. */
	private void hangLantern(int x, int track, int z) {
		BlockState lantern = Blocks.COPPER_LANTERN.unaffected().defaultBlockState();
		boolean ceiling = level.getBlockState(pos.set(x, track + 3, z)).isSolid();
		if (ceiling) {
			placeIfFree(x, track + 2, z, lantern.setValue(BlockStateProperties.HANGING, true));
		} else {
			placeIfFree(x, track, z, lantern);
		}
	}

	/** The way from a neighbouring ramp block in a tunnel to block i, or null when neither neighbour is one. */
	private Direction fromTunnelRamp(int i) {
		for (int j : new int[] {i - 1, i + 1}) {
			if (j >= 0 && j < plan.length() && isRamp(j) && section(j) == Section.TUNNEL) {
				return plan.direction(j, i);
			}
		}
		return null;
	}

	private boolean isRamp(int i) {
		return plan.isRamp(i);
	}

	/** Which way the track rises at a ramp block. */
	private Direction uphill(int i) {
		if (i < plan.length() - 1 && plan.track()[i + 1] > plan.track()[i]) {
			return plan.direction(i, i + 1);
		}
		if (i > 0 && plan.track()[i - 1] > plan.track()[i]) {
			return plan.direction(i, i - 1);
		}
		return i < plan.length() - 1 ? plan.direction(i + 1, i) : plan.direction(i - 1, i);
	}

	/** Rail shape at block i: sloping up towards a higher neighbour, curving at a corner, straight otherwise. */
	static RailShape railShape(TrackPlan plan, int i) {
		int track = plan.track()[i];
		Direction back = i > 0 ? plan.direction(i, i - 1) : null;
		Direction ahead = i < plan.length() - 1 ? plan.direction(i, i + 1) : null;
		if (ahead != null && plan.track()[i + 1] > track) {
			return ascending(ahead);
		}
		if (back != null && plan.track()[i - 1] > track) {
			return ascending(back);
		}
		Direction one = back != null ? back : ahead.getOpposite();
		Direction other = ahead != null ? ahead : back.getOpposite();
		if (one.getAxis() != other.getAxis()) {
			return curve(one, other);
		}
		return one.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
	}

	private static RailShape ascending(Direction towards) {
		return switch (towards) {
			case EAST -> RailShape.ASCENDING_EAST;
			case WEST -> RailShape.ASCENDING_WEST;
			case NORTH -> RailShape.ASCENDING_NORTH;
			default -> RailShape.ASCENDING_SOUTH;
		};
	}

	private static RailShape curve(Direction a, Direction b) {
		boolean north = a == Direction.NORTH || b == Direction.NORTH;
		boolean east = a == Direction.EAST || b == Direction.EAST;
		if (north) {
			return east ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
		}
		return east ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST;
	}

	private static boolean isCurve(RailShape shape) {
		return shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST || shape == RailShape.SOUTH_EAST
				|| shape == RailShape.SOUTH_WEST;
	}

	/** First free y above the solid ground of a column, ignoring trees, posts and fluids. */
	private int surface(int x, int z, int track) {
		for (int y = track + SCAN_UP; y > track - SCAN_DOWN; y--) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isSolid() && !isPlant(state) && !isFitting(state)) {
				return y + 1;
			}
		}
		return track - SCAN_DOWN;
	}

	/**
	 * In the clearing pass, takes out every tree, huge mushroom and bamboo stalk in the line's way above a column, and
	 * the vines.
	 */
	private void clearTrees(int x, int z, int track) {
		if (lining || !canWrite(x, z)) {
			return;
		}
		for (int y = track; y < track + TREE_CLEARANCE; y++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (isTree(state) && !isTemplateLog(pos)) {
				removeTree(new BlockPos(x, y, z));
			} else if (isStalk(state)) {
				removeStalk(x, y, z);
			} else if (isLeafy(state)) {
				level.setBlock(pos, AIR, WRITE);
			}
		}
	}

	/** Trunks and the stems and caps of huge mushrooms: what a tree is taken out by. */
	private static boolean isTree(BlockState state) {
		return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.RED_MUSHROOM_BLOCK)
				|| state.is(Blocks.BROWN_MUSHROOM_BLOCK);
	}

	/** What hangs off or grows on trees: leaves, vines, cocoa, shelf mushrooms, lichen and hanging moss. */
	private static boolean isLeafy(BlockState state) {
		return state.is(BlockTags.LEAVES) || state.is(Blocks.VINE) || state.is(Blocks.COCOA)
				|| state.is(Blocks.GLOW_LICHEN) || state.is(Blocks.PALE_HANGING_MOSS);
	}

	private static boolean isStalk(BlockState state) {
		return state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING);
	}

	private static boolean isPlant(BlockState state) {
		return isTree(state) || isLeafy(state) || isStalk(state);
	}

	/** Removes a bamboo stalk from its foot to its top. */
	private void removeStalk(int x, int y, int z) {
		int foot = y;
		while (isStalk(level.getBlockState(pos.set(x, foot - 1, z)))) {
			foot--;
		}
		for (int up = foot; isStalk(level.getBlockState(pos.set(x, up, z))); up++) {
			level.setBlock(pos, AIR, WRITE);
		}
	}

	/**
	 * Removes a tree whole: every trunk block joined to {@code start}, then the leaves and vines around them that no
	 * remaining trunk holds. Leaves a neighbouring tree still holds stay, so the next tree of a forest keeps its crown.
	 */
	private void removeTree(BlockPos start) {
		List<BlockPos> trunk = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start.asLong());
		while (!queue.isEmpty() && trunk.size() < MAX_TREE_BLOCKS) {
			BlockPos block = queue.poll();
			trunk.add(block);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos next = block.offset(dx, dy, dz);
						if (canWrite(next.getX(), next.getZ()) && seen.add(next.asLong())
								&& isTree(level.getBlockState(next)) && !isTemplateLog(next)) {
							queue.add(next);
						}
					}
				}
			}
		}
		trunk.forEach(block -> level.setBlock(block, AIR, WRITE));
		// Whatever grew on the trunk, a shelf mushroom or a vine, cannot stay on air.
		for (BlockPos block : trunk) {
			for (Direction direction : Direction.values()) {
				BlockPos next = block.relative(direction);
				if (canWrite(next.getX(), next.getZ())) {
					BlockState state = level.getBlockState(next);
					if (!state.isAir() && !isTree(state) && !state.canSurvive(level, next)) {
						level.setBlock(next, AIR, WRITE);
					}
				}
			}
		}
		Set<Long> leavesSeen = new HashSet<>();
		for (BlockPos block : trunk) {
			for (BlockPos leaf : BlockPos.betweenClosed(block.offset(-LEAF_REACH, -LEAF_REACH, -LEAF_REACH),
					block.offset(LEAF_REACH, LEAF_REACH, LEAF_REACH))) {
				if (!canWrite(leaf.getX(), leaf.getZ()) || !leavesSeen.add(leaf.asLong())) {
					continue;
				}
				BlockState state = level.getBlockState(leaf);
				if ((isLeafy(state) && !state.is(BlockTags.LEAVES)) || (state.is(BlockTags.LEAVES) && !heldByTrunk(leaf))) {
					level.setBlock(leaf, AIR, WRITE);
					dropUnsupportedAbove(leaf.getX(), leaf.getY(), leaf.getZ());
				}
			}
		}
	}

	private boolean heldByTrunk(BlockPos leaf) {
		for (BlockPos near : BlockPos.betweenClosed(leaf.offset(-LEAF_REACH, -LEAF_REACH, -LEAF_REACH),
				leaf.offset(LEAF_REACH, LEAF_REACH, LEAF_REACH))) {
			if (canWrite(near.getX(), near.getZ()) && level.getBlockState(near).is(BlockTags.LOGS)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * After clearing: what stood on a removed block and cannot stay up on its own goes too (grass, flowers, snow,
	 * fallen leaves), and a tree whose trunk lost its ground is removed whole. Over the inside of a tube the water
	 * stays, and its kelp waits for the tube's roof.
	 */
	private void removeUnsupported() {
		for (long key : carved) {
			if (tubeInside.contains(key)) {
				continue;
			}
			dropUnsupportedAbove(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
		}
	}

	private void dropUnsupportedAbove(int x, int y, int z) {
		for (int above = y + 1; above < y + 4; above++) {
			if (!canWrite(x, z)) {
				return;
			}
			BlockPos at = new BlockPos(x, above, z);
			BlockState state = level.getBlockState(at);
			if (state.isAir()) {
				return;
			}
			if (isTree(state) && !isTemplateLog(at)) {
				removeTree(at);
				return;
			}
			if (isStalk(state)) {
				removeStalk(x, above, z);
				return;
			}
			if (isFitting(state) && !isHanging(state)) {
				removeFitting(at);
				return;
			}
			if (state.canSurvive(level, at)) {
				return;
			}
			level.setBlock(at, AIR, WRITE);
		}
	}

	/**
	 * Posts, railings and what goes on them, such as the lamp posts and signs of road mods: no ground for a line, and
	 * the game leaves them up in the air when the ground under them is cut away.
	 */
	private static boolean isFitting(BlockState state) {
		return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.BARS)
				|| state.is(BlockTags.CHAINS) || state.is(BlockTags.LANTERNS) || state.is(Blocks.REDSTONE_LAMP)
				|| state.is(Blocks.DAYLIGHT_DETECTOR);
	}

	/** A lantern or chain hanging from above, which what it hangs from holds up. */
	private static boolean isHanging(BlockState state) {
		return state.is(BlockTags.CHAINS) || state.is(BlockTags.LANTERNS) && state.getValue(LanternBlock.HANGING);
	}

	/** Takes out a post whose ground was cut away, with everything on it and hanging from it. */
	private void removeFitting(BlockPos start) {
		Deque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty() && seen.size() <= MAX_FITTING_BLOCKS) {
			BlockPos at = queue.poll();
			level.setBlock(at, AIR, WRITE);
			for (Direction direction : Direction.values()) {
				BlockPos next = at.relative(direction);
				if (canWrite(next.getX(), next.getZ()) && !seen.contains(next) && isFitting(level.getBlockState(next))) {
					seen.add(next);
					queue.add(next);
				}
			}
		}
	}

	/** Fills a hollow under open track with cobblestone, down to the ground if it is close, so no rail floats. */
	private void fillUnder(int x, int fromY, int z) {
		for (int y = fromY; y > fromY - MAX_FILL_UNDER; y--) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isSolid() && state.getFluidState().isEmpty()) {
				return;
			}
			set(x, y, z, STONE);
		}
	}

	/** A plank pillar from under a deck down to the cave floor. */
	private void pillar(int x, int fromY, int z) {
		for (int y = fromY; y > fromY - MAX_LEG; y--) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isSolid() && state.getFluidState().isEmpty()) {
				return;
			}
			set(x, y, z, DECK);
		}
	}

	/**
	 * Seals a tunnel wall against water, lava and falling sand or gravel, and lines it where it is dirt; open cave air
	 * is left open.
	 */
	private void seal(int x, int y, int z) {
		if (!carved.contains(BlockPos.asLong(x, y, z)) && needsLining(level.getBlockState(pos.set(x, y, z)))) {
			set(x, y, z, STONE);
		}
	}

	private static boolean needsLining(BlockState state) {
		return !state.getFluidState().isEmpty() || state.getBlock() instanceof FallingBlock || state.is(BlockTags.DIRT);
	}

	/**
	 * Seals the walls, floor and ceiling of a tunnel in a neighbouring chunk again. The features of a chunk that
	 * generates later may spill into the edge of its neighbours, blobs of dirt or gravel among them, after the line
	 * there was built.
	 */
	private void resealNeighbour(int i, int x, int z, int track, int distance) {
		int height = isRamp(i) ? 5 : 3;
		for (int y = track - 1; y <= track + height; y++) {
			if (distance == 2 || y == track - 1 || y == track + height) {
				if (needsLining(level.getBlockState(pos.set(x, y, z)))) {
					level.setBlock(pos, STONE, WRITE);
				}
			}
		}
	}

	private void placeIfFree(int x, int y, int z, BlockState state) {
		if (writable(x, z) && level.getBlockState(pos.set(x, y, z)).isAir()) {
			set(x, y, z, state);
		}
	}

	/** Writes one block, only inside the chunk being built, or while repairing in a built neighbour. */
	private void set(int x, int y, int z, BlockState state) {
		if (writable(x, z)) {
			pos.set(x, y, z);
			if (state.is(BlockTags.WOODEN_FENCES)) {
				state = joinFences(x, y, z, state);
			}
			level.setBlock(pos.set(x, y, z), state, WRITE);
			if (!state.isAir()) {
				placed.add(pos.asLong());
			}
		}
	}

	/**
	 * A wooden fence joined to the wooden fences beside it, and they to it, as the game joins them, but to nothing
	 * else: not to rock, rails or the deck. A fence written where one stands, a post two tracks share, keeps the sides
	 * the first one joined.
	 */
	private BlockState joinFences(int x, int y, int z, BlockState fence) {
		BlockState there = level.getBlockState(pos.set(x, y, z));
		BlockState joined = fence;
		BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BooleanProperty property = CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(side);
			if (there.is(BlockTags.WOODEN_FENCES) && there.getValue(property)) {
				joined = joined.setValue(property, true);
			}
			beside.set(x + side.getStepX(), y, z + side.getStepZ());
			BlockState neighbour = level.getBlockState(beside);
			if (!neighbour.is(BlockTags.WOODEN_FENCES)) {
				continue;
			}
			joined = joined.setValue(property, true);
			BooleanProperty back = CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(side.getOpposite());
			if (!neighbour.getValue(back) && canWrite(beside.getX(), beside.getZ())) {
				level.setBlock(beside, neighbour.setValue(back, true), WRITE);
			}
		}
		return joined;
	}

	/**
	 * Gives every block placed here that hangs on a wall or a ceiling or stands on a floor what it needs: a sign,
	 * lantern, torch, button, lever, door or rail whose wall, ceiling or floor is open cave, fluid or nothing gets
	 * cobblestone there, unless that block is part of a line's corridor; one that cannot be held up that way goes.
	 * Left as it is, it would hang in the air until the first update next to it, flowing water or a player, and then
	 * drop as an item.
	 */
	private void secureAttachments() {
		BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
		for (long key : placed) {
			BlockPos at = BlockPos.of(key);
			BlockState state = level.getBlockState(at);
			if (state.isAir() || state.canSurvive(level, at)) {
				continue;
			}
			support.setWithOffset(at, supportSide(state));
			BlockState there = level.getBlockState(support);
			boolean open = there.isAir() || !there.getFluidState().isEmpty() || there.canBeReplaced();
			if (open && canWrite(support.getX(), support.getZ()) && !carved.contains(support.asLong())) {
				level.setBlock(support, STONE, WRITE);
				if (state.canSurvive(level, at)) {
					continue;
				}
				level.setBlock(support, there, WRITE);
			}
			level.setBlock(at, AIR, WRITE);
		}
	}

	/** The side of a block that holds it up: the wall behind it, the ceiling it hangs from or the floor. */
	private static Direction supportSide(BlockState state) {
		if (state.getBlock() instanceof LanternBlock) {
			return state.getValue(BlockStateProperties.HANGING) ? Direction.UP : Direction.DOWN;
		}
		if (state.getBlock() instanceof WallSignBlock || state.getBlock() instanceof WallTorchBlock
				|| state.getBlock() instanceof RedstoneWallTorchBlock) {
			return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
		}
		if (state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock) {
			return switch (state.getValue(BlockStateProperties.ATTACH_FACE)) {
				case CEILING -> Direction.UP;
				case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
				default -> Direction.DOWN;
			};
		}
		return Direction.DOWN;
	}

	private boolean writable(int x, int z) {
		return inChunk(x, z) || repairing && canWrite(x, z) && built.test(x >> 4, z >> 4, lineOf.getOrDefault(plan, plan));
	}

	/**
	 * Takes what a later feature spilled into the way of block i in a built neighbour out again: earth, rock, sand,
	 * gravel, logs and leaves on the rail and the free block each side, up to the height a rider needs.
	 */
	private void clearSpill(int i) {
		if (section(i) == Section.TUBE) {
			return;
		}
		Direction back = i > 0 ? plan.direction(i, i - 1) : plan.direction(i, i + 1).getOpposite();
		Direction side = back.getClockWise();
		int track = plan.track()[i];
		for (int offset = -1; offset <= 1; offset++) {
			int x = plan.x()[i] + side.getStepX() * offset;
			int z = plan.z()[i] + side.getStepZ() * offset;
			if (inChunk(x, z) || !writable(x, z)) {
				continue;
			}
			for (int y = offset == 0 ? track + 1 : track; y <= track + 2; y++) {
				if (isSpill(level.getBlockState(pos.set(x, y, z)))) {
					set(x, y, z, AIR);
				}
			}
		}
	}

	private static boolean isSpill(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(BlockTags.LOGS)
				|| state.is(BlockTags.LEAVES) || state.is(Blocks.MANGROVE_ROOTS);
	}

	/** Lays the rail of block i again where it went missing: room above it, ground under it, the rail and its torch. */
	private void layRailAgain(int i) {
		int x = plan.x()[i];
		int z = plan.z()[i];
		int track = plan.track()[i];
		for (int y = track; y <= track + 2; y++) {
			if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
				set(x, y, z, AIR);
			}
		}
		if (!level.getBlockState(pos.set(x, track - 1, z)).isSolid()) {
			set(x, track - 1, z, STONE);
		}
		Direction back = i > 0 ? plan.direction(i, i - 1) : plan.direction(i, i + 1).getOpposite();
		placeTrack(i, back.getClockWise());
	}

	/** Clears a block for the line, in the clearing pass only, and remembers it. */
	private void clear(int x, int y, int z) {
		if (!lining && inChunk(x, z)) {
			carved.add(BlockPos.asLong(x, y, z));
			set(x, y, z, AIR);
		}
	}

	/** Places a block of the line's structure (deck, fill), in the second pass only. */
	private void build(int x, int y, int z, BlockState state) {
		if (lining) {
			set(x, y, z, state);
		}
	}

	private boolean inChunk(int x, int z) {
		return x >> 4 == chunk.x() && z >> 4 == chunk.z();
	}

	/** Trees may be cleared in this chunk and the reachable chunks around it. */
	private boolean canWrite(int x, int z) {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		return Math.abs(chunkX - chunk.x()) <= 1 && Math.abs(chunkZ - chunk.z()) <= 1 && reachable.test(chunkX, chunkZ);
	}
}
