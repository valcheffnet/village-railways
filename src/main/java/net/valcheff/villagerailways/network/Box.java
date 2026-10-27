package net.valcheff.villagerailways.network;

/** A horizontal rectangle of blocks, edges included. */
public record Box(int minX, int minZ, int maxX, int maxZ) {
	/** Whether this box, grown by {@code margin} on every side, touches the axis-aligned segment from a to b. */
	public boolean touches(Point a, Point b, int margin) {
		return Math.max(a.x(), b.x()) >= minX - margin && Math.min(a.x(), b.x()) <= maxX + margin
				&& Math.max(a.z(), b.z()) >= minZ - margin && Math.min(a.z(), b.z()) <= maxZ + margin;
	}
}
