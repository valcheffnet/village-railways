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
 * @param trunk  whether the line joins two groups of villages that the nearest-neighbour lines left apart
 */
public record Link(long from, long to, List<Point> path, Status status, boolean trunk) {
	public enum Status {
		PLANNED,
		BUILT
	}

	public boolean connects(long village) {
		return from == village || to == village;
	}

	public long other(long village) {
		return from == village ? to : from;
	}

	public Link withStatus(Status newStatus) {
		return new Link(from, to, path, newStatus, trunk);
	}
}
