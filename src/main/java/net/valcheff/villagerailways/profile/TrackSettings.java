package net.valcheff.villagerailways.profile;

/**
 * How lines are built in one world. The plans of lines are worked out again at every start, so these stay as the world
 * was created with: a line half built with one cut depth would not meet its other half planned with another.
 *
 * @param cutDepth      deepest open cut before the line goes into a tunnel
 * @param pillarSpacing blocks between the pillars of a bridge in the open
 */
public record TrackSettings(int cutDepth, int pillarSpacing) {
	public static final TrackSettings DEFAULT = new TrackSettings(Section.MAX_TRENCH, 9);
}
