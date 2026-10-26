package net.valcheff.villagerailways.network;

/**
 * A village as the network planner sees it: its start chunk as id, its center and its footprint.
 *
 * @param id        packed start chunk position, unique per village
 * @param structure registry name of the village structure
 * @param center    center of the village meeting point
 * @param minX      west edge of the footprint
 * @param minZ      north edge of the footprint
 * @param maxX      east edge of the footprint
 * @param maxZ      south edge of the footprint
 */
public record Village(long id, String structure, Point center, int minX, int minZ, int maxX, int maxZ) {
	/** Whether the footprint, grown by {@code margin} on every side, touches the axis-aligned segment from a to b. */
	public boolean touches(Point a, Point b, int margin) {
		return Math.max(a.x(), b.x()) >= minX - margin && Math.min(a.x(), b.x()) <= maxX + margin
				&& Math.max(a.z(), b.z()) >= minZ - margin && Math.min(a.z(), b.z()) <= maxZ + margin;
	}
}
