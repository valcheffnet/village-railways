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
 * @param stationSpacing closest two stations of one village may be
 * @param villageMargin  gap a line keeps from villages it does not serve
 * @param structureMargin gap a line keeps from other surface structures, about as far as a cut reaches
 * @param minAngle       smallest angle between two lines leaving the same village, in degrees
 * @param maxOceanCrossing longest stretch of ocean a line may bridge, in blocks
 * @param maxGroupLinks  most trunk lines from one group of villages to other groups
 * @param maxGroupLength longest trunk line, measured between village centers, in blocks
 */
public record PlannerSettings(int maxLinks, int maxLength, int minRun, int maxRun, int clearance, int stationMargin,
		int stationSpacing, int villageMargin, int structureMargin, double minAngle, int maxOceanCrossing,
		int maxGroupLinks, int maxGroupLength) {
	public static final PlannerSettings DEFAULT = new PlannerSettings(3, 1200, 32, 64, 12, 8, 12, 16, 8, 30.0, 64, 3, 2500);
}
