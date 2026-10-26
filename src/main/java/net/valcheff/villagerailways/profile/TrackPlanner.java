package net.valcheff.villagerailways.profile;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.valcheff.villagerailways.network.Point;

/**
 * Lays a planned line out block by block and gives it a height profile, reading the terrain from the generator's
 * noise so the chunks need not exist. The noise is the terrain before caves, ravines and surface features are carved
 * in; those are handled when the line is built.
 */
public class TrackPlanner {
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final RandomState randomState;

	public TrackPlanner(ServerLevel level) {
		this.level = level;
		this.generator = level.getChunkSource().getGenerator();
		this.randomState = level.getChunkSource().randomState();
	}

	/** The block-by-block plan of a line through the given corners, or null when no height profile fits it. */
	public TrackPlan plan(List<Point> path) {
		int length = 1;
		for (int i = 1; i < path.size(); i++) {
			length += distance(path.get(i - 1), path.get(i));
		}
		int[] x = new int[length];
		int[] z = new int[length];
		boolean[] corner = new boolean[length];
		int index = 0;
		x[0] = path.getFirst().x();
		z[0] = path.getFirst().z();
		for (int i = 1; i < path.size(); i++) {
			Point from = path.get(i - 1);
			Point to = path.get(i);
			int stepX = Integer.signum(to.x() - from.x());
			int stepZ = Integer.signum(to.z() - from.z());
			for (int step = 1; step <= distance(from, to); step++) {
				index++;
				x[index] = from.x() + stepX * step;
				z[index] = from.z() + stepZ * step;
			}
			if (i < path.size() - 1) {
				corner[index] = true;
			}
		}

		int[] ground = new int[length];
		int[] water = new int[length];
		for (int i = 0; i < length; i++) {
			sampleColumn(x[i], z[i], ground, water, i);
		}
		int start = stationHeight(ground[0], water[0]);
		int end = stationHeight(ground[length - 1], water[length - 1]);
		int[] track = HeightProfile.plan(ground, water, corner, start, end);
		if (track == null) {
			return null;
		}
		Section[] sections = new Section[length];
		for (int i = 0; i < length; i++) {
			sections[i] = Section.of(track[i], ground[i], water[i]);
		}
		return new TrackPlan(x, z, track, ground, water, corner, sections);
	}

	/** Fills in the first free y above solid ground and above water (or DRY) for one column. */
	private void sampleColumn(int x, int z, int[] ground, int[] water, int index) {
		NoiseColumn column = generator.getBaseColumn(x, z, level, randomState);
		int y = generator.getMinY() + generator.getGenDepth() - 1;
		while (y > generator.getMinY() && column.getBlock(y).isAir()) {
			y--;
		}
		BlockState top = column.getBlock(y);
		if (top.getFluidState().isEmpty()) {
			water[index] = HeightProfile.DRY;
		} else {
			water[index] = y + 1;
			while (y > generator.getMinY() && !column.getBlock(y).getFluidState().isEmpty()) {
				y--;
			}
		}
		ground[index] = y + 1;
	}

	private static int stationHeight(int ground, int water) {
		return water == HeightProfile.DRY ? ground : water + 1;
	}

	private static int distance(Point a, Point b) {
		return Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
	}
}
