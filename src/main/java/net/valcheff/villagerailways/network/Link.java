package net.valcheff.villagerailways.network;

import java.util.List;

/**
 * A railway line between two villages.
 *
 * @param from   id of the village the line was planned from
 * @param to     id of the other village
 * @param path   corners of the line from the station at {@code from} to the station at {@code to}; every segment
 *               runs along the x or the z axis
 * @param status how far the line has got
 * @param trunk     whether the line joins two groups of villages that the nearest-neighbour lines left apart; on a
 *                  station track, whether it is a western station, where trunk lines come in as a double track
 * @param twoTracks on a station track, whether it is a small station with a second track, laid out like a western
 *                  station's; false on a line, and on the small stations of earlier versions, which have one track
 */
public record Link(long from, long to, List<Point> path, Status status, boolean trunk, boolean twoTracks) {
	public Link(long from, long to, List<Point> path, Status status, boolean trunk) {
		this(from, to, path, status, trunk, false);
	}

	public enum Status {
		/** Planned and being built as its chunks generate. */
		PLANNED,
		/** Every chunk of the line has been built. */
		BUILT,
		/** Cannot be built: a chunk on its path existed before the line was planned, or no height profile fits. */
		BLOCKED
	}

	/** Key of a line: the two village ids in a fixed order. */
	public NetworkPlanner.VillagePair key() {
		return NetworkPlanner.VillagePair.of(from, to);
	}

	/** Whether a station track has a second track beside it: a western station, or a small one with two tracks. */
	public boolean twoTrackStation() {
		return trunk || twoTracks;
	}

	public boolean connects(long village) {
		return from == village || to == village;
	}

	public long other(long village) {
		return from == village ? to : from;
	}

	public Link withStatus(Status newStatus) {
		return new Link(from, to, path, newStatus, trunk, twoTracks);
	}

	public Link withTrunk(boolean newTrunk) {
		return new Link(from, to, path, status, newTrunk, twoTracks);
	}
}
