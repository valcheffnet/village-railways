package net.valcheff.villagerailways.profile;

/**
 * How lines are built in one world. The plans of lines are worked out again at every start, so these stay as the world
 * was created with: a line half built with one cut depth would not meet its other half planned with another.
 *
 * @param cutDepth      deepest open cut before the line goes into a tunnel
 * @param pillarSpacing blocks between the pillars of a bridge in the open
 * @param seaTubes      whether lines cross seas in tubes under water; without them a line bridges at most
 *                      {@link HeightProfile#MAX_BRIDGE} blocks of ocean
 */
public record TrackSettings(int cutDepth, int pillarSpacing, boolean seaTubes) {
	public static final TrackSettings DEFAULT = new TrackSettings(Section.MAX_TRENCH, 9, false);
}
