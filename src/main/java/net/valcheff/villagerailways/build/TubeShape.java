package net.valcheff.villagerailways.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.valcheff.villagerailways.profile.Section;
import net.valcheff.villagerailways.profile.TrackPlan;

/**
 * The tubes of one line round a chunk, worked out from the line's plan alone so every chunk agrees.
 *
 * <p>A tube runs along the middle of the track: the line's rail, or half way between the two rails where a second
 * rail runs beside it. Its cross section is nine blocks wide and six high with the corners cut off: inside, seven
 * wide for the three rows from the rail up and five wide for the row over them; on a ramp it is a block higher, so a
 * rider going down does not meet the roof ahead, as in a tunnel. Every block next to the inside is wall, so the
 * tube stays closed where it turns or climbs; at a corner it turns square, and past its end it closes round the
 * line's way on, a few blocks into the shore. Every {@value #RIB_SPACING} blocks and at its ends the wall is a rib, on
 * two columns down to the sea bed.
 */
final class TubeShape {
	static final int RIB_SPACING = 5;
	/** Farthest a block of a tube's wall lies from its middle, across it or past its end. */
	static final int RADIUS = 4;
	/** The columns of a rib stand under the edges of the floor, this far from the middle. */
	private static final int COLUMN = 3;

	/** A rib: the middle of the tube there, the way to its left and the rail height. */
	record Rib(int x, int z, Direction left, int track) {
	}

	/** The columns looked at: the chunk and one block round it, for the walls at its edge. */
	private final int minX;
	private final int minZ;
	private final int size;
	/** For each column, how far it lies from the middle of the tube, or -1 beyond the walls. */
	private final int[] distance;
	/** For each column, the rail height of the tube there, and the block of the line it belongs to. */
	private final int[] track;
	private final int[] index;
	/** For each column, whether its rail slopes, where the inside is a block higher. */
	private final boolean[] ramp;
	private final boolean[] rib;
	/** The way of the line beside its tubes, which their walls leave open. */
	private final Set<Long> passage = new HashSet<>();
	private final List<Rib> ribs = new ArrayList<>();

	private TubeShape(ChunkPos chunk, TrackPlan line) {
		minX = chunk.getMinBlockX() - 1;
		minZ = chunk.getMinBlockZ() - 1;
		size = 16 + 2;
		distance = new int[size * size];
		track = new int[size * size];
		index = new int[size * size];
		ramp = new boolean[size * size];
		int[] manhattan = new int[size * size];
		Arrays.fill(distance, -1);
		rib = ribs(line);
		boolean[] doubled = new boolean[line.length()];
		for (TrackPlan second : line.seconds()) {
			for (int p = 0; p < second.length(); p++) {
				if (second.sections()[p] == Section.TUBE) {
					doubled[second.beside()[p]] = true;
				}
			}
		}
		for (int i = 0; i < line.length(); i++) {
			if (line.sections()[i] != Section.TUBE) {
				continue;
			}
			Direction in = i > 0 ? line.direction(i - 1, i) : line.direction(i, i + 1);
			Direction out = i < line.length() - 1 ? line.direction(i, i + 1) : in;
			int shift = doubled[i] ? 1 : 0;
			int x = line.x()[i] + in.getCounterClockWise().getStepX() * shift;
			int z = line.z()[i] + in.getCounterClockWise().getStepZ() * shift;
			if (out != in) {
				x += out.getCounterClockWise().getStepX() * shift;
				z += out.getCounterClockWise().getStepZ() * shift;
			}
			if (rib[i] && near(x, z, RADIUS)) {
				ribs.add(new Rib(x, z, out.getCounterClockWise(), line.track()[i]));
			}
			// Each column belongs to the nearest middle, counted square, and of those to the one straight across.
			for (int cx = Math.max(x - RADIUS, minX); cx <= Math.min(x + RADIUS, minX + size - 1); cx++) {
				for (int cz = Math.max(z - RADIUS, minZ); cz <= Math.min(z + RADIUS, minZ + size - 1); cz++) {
					int column = (cx - minX) * size + (cz - minZ);
					int square = Math.max(Math.abs(cx - x), Math.abs(cz - z));
					int straight = Math.abs(cx - x) + Math.abs(cz - z);
					if (distance[column] < 0 || square < distance[column]
							|| square == distance[column] && straight < manhattan[column]) {
						distance[column] = square;
						manhattan[column] = straight;
						track[column] = line.track()[i];
						ramp[column] = isRamp(line, i);
						index[column] = i;
					}
				}
			}
		}
		addPassage(line);
		for (TrackPlan second : line.seconds()) {
			addPassage(second);
		}
	}

	/** The tubes of a line round a chunk, or null when none comes near it. */
	static TubeShape of(TrackPlan line, ChunkPos chunk) {
		int reach = RADIUS + 2;
		for (int i = 0; i < line.length(); i++) {
			if (line.sections()[i] == Section.TUBE && line.x()[i] >= chunk.getMinBlockX() - reach
					&& line.x()[i] <= chunk.getMaxBlockX() + reach && line.z()[i] >= chunk.getMinBlockZ() - reach
					&& line.z()[i] <= chunk.getMaxBlockZ() + reach) {
				return new TubeShape(chunk, line);
			}
		}
		return null;
	}

	/** Which blocks of a line carry a rib: every {@value #RIB_SPACING} blocks of a tube from its start, and its end. */
	private static boolean[] ribs(TrackPlan line) {
		boolean[] ribs = new boolean[line.length()];
		for (int i = 0; i < line.length(); i++) {
			if (line.sections()[i] != Section.TUBE) {
				continue;
			}
			int last = i;
			while (last + 1 < line.length() && line.sections()[last + 1] == Section.TUBE) {
				last++;
			}
			for (int j = i; j <= last; j += RIB_SPACING) {
				ribs[j] = true;
			}
			ribs[last] = true;
			i = last;
		}
		return ribs;
	}

	/**
	 * The corridor of the blocks of a track that are no tube, as high as a tunnel clears it: the walls of a tube that
	 * ends beside them leave it open.
	 */
	private void addPassage(TrackPlan line) {
		for (int j = 0; j < line.length(); j++) {
			if (line.sections()[j] == Section.TUBE || !near(line.x()[j], line.z()[j], 1)) {
				continue;
			}
			Direction back = j > 0 ? line.direction(j, j - 1) : line.direction(j, j + 1).getOpposite();
			Direction ahead = j < line.length() - 1 ? line.direction(j, j + 1) : back.getOpposite();
			int height = isRamp(line, j) ? 5 : 3;
			for (Direction across : new Direction[] {back.getClockWise(), ahead.getClockWise()}) {
				for (int offset = -1; offset <= 1; offset++) {
					int x = line.x()[j] + across.getStepX() * offset;
					int z = line.z()[j] + across.getStepZ() * offset;
					for (int y = line.track()[j]; y < line.track()[j] + height; y++) {
						passage.add(BlockPos.asLong(x, y, z));
					}
				}
			}
		}
	}

	private static boolean isRamp(TrackPlan line, int i) {
		return (i > 0 && line.track()[i - 1] != line.track()[i])
				|| (i < line.length() - 1 && line.track()[i + 1] != line.track()[i]);
	}

	private boolean near(int x, int z, int margin) {
		return x >= minX - margin && x < minX + size + margin && z >= minZ - margin && z < minZ + size + margin;
	}

	private int column(int x, int z) {
		if (x < minX || x >= minX + size || z < minZ || z >= minZ + size) {
			return -1;
		}
		int column = (x - minX) * size + (z - minZ);
		return distance[column] < 0 ? -1 : column;
	}

	/** Whether a tube's walls or inside reach a column. */
	boolean covers(int x, int z) {
		return column(x, z) >= 0;
	}

	/** The rail height of the tube at a column it {@link #covers}. */
	int track(int x, int z) {
		return track[column(x, z)];
	}

	/** Whether a block lies inside a tube. */
	boolean inside(int x, int y, int z) {
		int column = column(x, z);
		if (column < 0) {
			return false;
		}
		int d = distance[column];
		int t = track[column];
		int top = ramp[column] ? t + 1 : t;
		return y >= t && (d <= 2 ? y <= top + 3 : d == 3 && y <= top + 2);
	}

	/** Whether a block is part of a tube's wall: next to its inside, and not on the line's way out of it. */
	boolean isWall(int x, int y, int z) {
		if (inside(x, y, z) || passage.contains(BlockPos.asLong(x, y, z))) {
			return false;
		}
		return inside(x, y + 1, z) || inside(x, y - 1, z) || inside(x + 1, y, z) || inside(x - 1, y, z)
				|| inside(x, y, z + 1) || inside(x, y, z - 1);
	}

	/** Whether a block of a tube's wall is its floor, under the five middle rows of its inside. */
	boolean isFloor(int x, int y, int z) {
		int column = column(x, z);
		return column >= 0 && distance[column] <= 2 && y == track[column] - 1;
	}

	/** Whether a block of a tube's wall belongs to a rib. */
	boolean isRib(int x, int z) {
		int column = column(x, z);
		return column >= 0 && rib[index[column]];
	}

	/** Whether a block of a rib's wall is the top of its roof, over the middle of the tube. */
	boolean isRibTop(int x, int y, int z) {
		int column = column(x, z);
		return column >= 0 && rib[index[column]] && distance[column] == 0
				&& y == track[column] + (ramp[column] ? 5 : 4);
	}

	/** The ribs whose columns may stand in or near the chunk. */
	List<Rib> ribs() {
		return ribs;
	}

	/** Where the columns under a rib stand, either side of the middle. */
	static int[][] columns(Rib rib) {
		Direction left = rib.left();
		return new int[][] {
				{rib.x() + left.getStepX() * COLUMN, rib.z() + left.getStepZ() * COLUMN},
				{rib.x() - left.getStepX() * COLUMN, rib.z() - left.getStepZ() * COLUMN}};
	}
}
