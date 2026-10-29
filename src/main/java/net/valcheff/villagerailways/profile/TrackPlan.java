package net.valcheff.villagerailways.profile;

import java.util.List;

import net.minecraft.core.Direction;

/**
 * A line laid out block by block, from the station at its start to the station at its end.
 *
 * @param x         x of each block
 * @param z         z of each block
 * @param track     rail height (y of the rail block) at each block
 * @param ground    first free y above solid ground, as the generator makes it
 * @param water     first free y above water, or {@link HeightProfile#DRY}
 * @param corner    whether the line turns at each block
 * @param sections  what is built at each block
 * @param halts     the blocks with a halt beside them, in order
 * @param junctions where a line meets this station track from the side, each as {@code 2 * block + 1} when it comes
 *                  from the right (the clockwise side of the way back), {@code 2 * block} when from the left
 * @param seconds   the second rails of a double track, each running on the left of a stretch of this line
 * @param beside    on a second rail, for each block the block of its line it runs beside; empty on a line
 * @param western   whether this is the track of a western station, with its house on the left
 */
public record TrackPlan(int[] x, int[] z, int[] track, int[] ground, int[] water, boolean[] corner, Section[] sections,
		int[] halts, int[] junctions, List<TrackPlan> seconds, int[] beside, boolean western) {
	public int length() {
		return x.length;
	}

	public TrackPlan withHalts(int[] halts) {
		return new TrackPlan(x, z, track, ground, water, corner, sections, halts, junctions, seconds, beside, western);
	}

	public TrackPlan withJunctions(int[] junctions) {
		return new TrackPlan(x, z, track, ground, water, corner, sections, halts, junctions, seconds, beside, western);
	}

	public TrackPlan withSeconds(List<TrackPlan> seconds) {
		return new TrackPlan(x, z, track, ground, water, corner, sections, halts, junctions, seconds, beside, western);
	}

	public TrackPlan withWestern(boolean western) {
		return new TrackPlan(x, z, track, ground, water, corner, sections, halts, junctions, seconds, beside, western);
	}

	/** Whether the rail at block i slopes: the block next to it on either side lies higher or lower. */
	public boolean isRamp(int i) {
		return (i > 0 && track[i - 1] != track[i]) || (i < x.length - 1 && track[i + 1] != track[i]);
	}

	/** The direction from block {@code from} to the neighbouring block {@code to}. */
	public Direction direction(int from, int to) {
		int dx = x[to] - x[from];
		int dz = z[to] - z[from];
		return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}
}
