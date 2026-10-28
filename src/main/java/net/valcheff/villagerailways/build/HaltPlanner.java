package net.valcheff.villagerailways.build;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.valcheff.villagerailways.network.Point;
import net.valcheff.villagerailways.profile.HeightProfile;
import net.valcheff.villagerailways.profile.Section;
import net.valcheff.villagerailways.profile.TerrainSampler;
import net.valcheff.villagerailways.profile.TrackPlan;
import net.valcheff.villagerailways.profile.TrackPlanner;

/**
 * Picks where a line gets a halt: a small hut on a platform beside the track, where riders can take a minecart.
 *
 * <p>Halts are rare and stand only on open, level, straight track: about one every {@value #SPACING} blocks of open
 * line, none within {@value #STATION_DISTANCE} blocks of a station or of another halt. A tunnel longer than
 * {@value #LONG_TUNNEL} blocks always gets one just outside each end. Everything is decided from the line's plan, so
 * every chunk finds the same halts.
 */
public final class HaltPlanner {
	static final int SPACING = 800;
	static final int STATION_DISTANCE = 300;
	static final int LONG_TUNNEL = 200;
	/** How far outside the end of a long tunnel its halt is looked for. */
	private static final int PORTAL_NEAREST = 10;
	private static final int PORTAL_FARTHEST = 100;
	/** Closest a halt at a tunnel stands to a station or another halt. */
	private static final int PORTAL_SPACING = 40;
	/** The platform reaches this far along the track either side of the halt's middle. */
	static final int HALF_LENGTH = 4;
	/** How far a halt reaches away from the track. */
	static final int DEPTH = 7;
	/** Most the ground under a halt may lie above or below the rail. */
	private static final int MAX_GROUND_STEP = 2;
	/**
	 * Most the ground may rise above the rail at a halt by a tunnel. The land rises towards a tunnel, so these halts
	 * may stand in the cut, dug into the slope.
	 */
	private static final int MAX_CUT = Section.MAX_TRENCH + 1;
	/** Closest another line may pass a halt. */
	static final int OTHER_LINE_CLEARANCE = 16;

	/** The terrain of a column, as the generator makes it. */
	@FunctionalInterface
	public interface Terrain {
		TerrainSampler.Column sample(int x, int z);
	}

	/** Whether another line passes close to a position. */
	@FunctionalInterface
	public interface NearLine {
		boolean test(int x, int z);
	}

	private HaltPlanner() {
	}

	/** The blocks of a line that get a halt beside them, in order. */
	public static int[] halts(TrackPlan plan, Terrain terrain, NearLine nearLine) {
		List<Integer> halts = new ArrayList<>();
		int tunnelStart = -1;
		for (int i = 0; i <= plan.length(); i++) {
			boolean tunnel = i < plan.length() && plan.sections()[i] == Section.TUNNEL;
			if (tunnel && tunnelStart < 0) {
				tunnelStart = i;
			} else if (!tunnel && tunnelStart >= 0) {
				if (i - tunnelStart > LONG_TUNNEL) {
					addPortalHalt(plan, tunnelStart, -1, terrain, nearLine, halts);
					addPortalHalt(plan, i - 1, 1, terrain, nearLine, halts);
				}
				tunnelStart = -1;
			}
		}
		// Each line counts from its own offset, so halts do not all stand the same distance from their stations.
		int open = Math.floorMod(plan.x()[0] * 31 + plan.z()[0], SPACING / 2);
		for (int i = STATION_DISTANCE; i < plan.length() - STATION_DISTANCE; i++) {
			Section section = plan.sections()[i];
			if (section != Section.TUNNEL && section != Section.BRIDGE) {
				open++;
			}
			if (open >= SPACING && farFrom(halts, i, STATION_DISTANCE) && fits(plan, i, terrain, nearLine, false)) {
				halts.add(i);
				open = 0;
			}
		}
		return halts.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/** Looks for a halt just outside the end of a tunnel, going away from it in {@code step}. */
	private static void addPortalHalt(TrackPlan plan, int end, int step, Terrain terrain, NearLine nearLine,
			List<Integer> halts) {
		for (int distance = PORTAL_NEAREST; distance <= PORTAL_FARTHEST; distance++) {
			int i = end + step * distance;
			if (i < PORTAL_SPACING || i >= plan.length() - PORTAL_SPACING) {
				return;
			}
			if (fits(plan, i, terrain, nearLine, true)) {
				if (farFrom(halts, i, PORTAL_SPACING)) {
					halts.add(i);
				}
				return;
			}
		}
	}

	private static boolean farFrom(List<Integer> halts, int i, int distance) {
		for (int halt : halts) {
			if (Math.abs(halt - i) < distance) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a halt fits at block i: the track runs straight and level, on the ground, past the whole platform, the
	 * ground beside it is dry and about as high as the rail, no bridge is near and no other line passes close. By a
	 * tunnel the track may run in a cut and the ground beside it may rise as high as a cut goes.
	 */
	static boolean fits(TrackPlan plan, int i, Terrain terrain, NearLine nearLine, boolean byTunnel) {
		if (i - HALF_LENGTH - 1 < 0 || i + HALF_LENGTH + 1 >= plan.length()) {
			return false;
		}
		int track = plan.track()[i];
		// Clear of bridges by as much ground as a double bridge spans, so a halt never stands where one runs on.
		int bridgeClearance = HALF_LENGTH + TrackPlanner.MAX_BRIDGE_GAP;
		for (int j = Math.max(0, i - bridgeClearance); j <= Math.min(plan.length() - 1, i + bridgeClearance); j++) {
			if (plan.sections()[j] == Section.BRIDGE) {
				return false;
			}
		}
		for (int j = i - HALF_LENGTH - 1; j <= i + HALF_LENGTH + 1; j++) {
			Section section = plan.sections()[j];
			boolean ground = section == Section.FLAT || section == Section.FILL || (byTunnel && section == Section.TRENCH);
			if (plan.corner()[j] || plan.track()[j] != track || !ground) {
				return false;
			}
		}
		Direction along = plan.direction(i, i + 1);
		Direction away = platformSide(plan, i);
		for (int u = -HALF_LENGTH; u <= HALF_LENGTH; u += HALF_LENGTH) {
			for (int v = 1; v <= DEPTH; v += (DEPTH - 1) / 2) {
				TerrainSampler.Column column = terrain.sample(plan.x()[i] + along.getStepX() * u + away.getStepX() * v,
						plan.z()[i] + along.getStepZ() * u + away.getStepZ() * v);
				int rise = column.ground() - track;
				if (column.water() != HeightProfile.DRY || rise < -MAX_GROUND_STEP
						|| rise > (byTunnel ? MAX_CUT : MAX_GROUND_STEP)) {
					return false;
				}
			}
		}
		int middleX = plan.x()[i] + away.getStepX() * DEPTH / 2;
		int middleZ = plan.z()[i] + away.getStepZ() * DEPTH / 2;
		return !nearLine.test(middleX, middleZ);
	}

	/** The side of the track a halt at block i stands on: the one without the torches and lamp posts. */
	static Direction platformSide(TrackPlan plan, int i) {
		Direction back = i > 0 ? plan.direction(i, i - 1) : plan.direction(i, i + 1).getOpposite();
		return back.getCounterClockWise();
	}

	/** Whether a line through the given corners passes within {@code clearance} blocks of a position. */
	public static boolean passesNear(List<Point> path, int x, int z, int clearance) {
		for (int k = 1; k < path.size(); k++) {
			Point from = path.get(k - 1);
			Point to = path.get(k);
			int nearestX = Math.clamp(x, Math.min(from.x(), to.x()), Math.max(from.x(), to.x()));
			int nearestZ = Math.clamp(z, Math.min(from.z(), to.z()), Math.max(from.z(), to.z()));
			long dx = x - nearestX;
			long dz = z - nearestZ;
			if (dx * dx + dz * dz <= (long) clearance * clearance) {
				return true;
			}
		}
		return false;
	}
}
