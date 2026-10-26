package net.valcheff.villagerailways.profile;

/**
 * A line laid out block by block, from the station at its start to the station at its end.
 *
 * @param x        x of each block
 * @param z        z of each block
 * @param track    rail height (y of the rail block) at each block
 * @param ground   first free y above solid ground, as the generator makes it
 * @param water    first free y above water, or {@link HeightProfile#DRY}
 * @param corner   whether the line turns at the block
 * @param sections what is built at each block
 */
public record TrackPlan(int[] x, int[] z, int[] track, int[] ground, int[] water, boolean[] corner, Section[] sections) {
	public int length() {
		return x.length;
	}
}
