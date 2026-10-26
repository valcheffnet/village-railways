package net.valcheff.villagerailways.network;

/**
 * Rules for the railway network.
 *
 * @param maxLinks       most lines one village may have
 * @param maxLength      longest line, measured between village centers, in blocks
 * @param minRun         shortest straight a diagonal is built from
 * @param maxRun         longest side step a diagonal is built from
 * @param clearance      closest two lines may come to each other, away from a shared village
 * @param stationMargin  gap between a village's footprint and its station
 * @param villageMargin  gap a line keeps from villages it does not serve
 * @param minAngle       smallest angle between two lines leaving the same village, in degrees
 * @param maxOceanCrossing longest stretch of ocean a line may bridge, in blocks
 */
public record PlannerSettings(int maxLinks, int maxLength, int minRun, int maxRun, int clearance, int stationMargin,
		int villageMargin, double minAngle, int maxOceanCrossing) {
	public static final PlannerSettings DEFAULT = new PlannerSettings(3, 1200, 32, 64, 12, 8, 16, 30.0, 64);
}
