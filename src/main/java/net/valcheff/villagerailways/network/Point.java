package net.valcheff.villagerailways.network;

/** A horizontal block position: x and z only. */
public record Point(int x, int z) {
	public long distanceSq(Point other) {
		long dx = (long) other.x - x;
		long dz = (long) other.z - z;
		return dx * dx + dz * dz;
	}
}
