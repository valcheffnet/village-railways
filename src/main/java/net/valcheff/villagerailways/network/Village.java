package net.valcheff.villagerailways.network;

import java.util.List;

/**
 * A village as the network planner sees it: its start chunk as id, its center, its footprint and its buildings.
 *
 * @param id        packed start chunk position, unique per village
 * @param structure registry name of the village structure
 * @param center    center of the village meeting point
 * @param minX      west edge of the footprint
 * @param minZ      north edge of the footprint
 * @param maxX      east edge of the footprint
 * @param maxZ      south edge of the footprint
 * @param buildings every piece of the village except its streets; a line to the village may cross streets but not these
 */
public record Village(long id, String structure, Point center, int minX, int minZ, int maxX, int maxZ,
		List<Box> buildings) {
	public Village {
		buildings = buildings == null ? List.of() : List.copyOf(buildings);
	}

	/** Whether the footprint, grown by {@code margin} on every side, touches the axis-aligned segment from a to b. */
	public boolean touches(Point a, Point b, int margin) {
		return Math.max(a.x(), b.x()) >= minX - margin && Math.min(a.x(), b.x()) <= maxX + margin
				&& Math.max(a.z(), b.z()) >= minZ - margin && Math.min(a.z(), b.z()) <= maxZ + margin;
	}

	/** Whether the segment from a to b comes within {@code margin} of any building. */
	public boolean hitsBuilding(Point a, Point b, int margin) {
		for (Box building : buildings) {
			if (building.touches(a, b, margin)) {
				return true;
			}
		}
		return false;
	}
}
