package net.valcheff.villagerailways.profile;

/** What has to be built at one block of line, from the track height and the terrain there. */
public enum Section {
	/** Rail on the ground. */
	FLAT,
	/** Up to {@link #MAX_FILL} blocks of solid embankment under the rail. */
	FILL,
	/** A deck on pillars: over water, or over a drop higher than {@link #MAX_FILL}. */
	BRIDGE,
	/** Open cut, up to {@link #MAX_TRENCH} blocks deep unless the world sets another depth. */
	TRENCH,
	/** 3x3 tunnel where the cut would be deeper. */
	TUNNEL,
	/**
	 * A glass tube under water, where a line crosses a sea: level on columns over the sea bed, and on through the
	 * islands and shores while the rail is below the water's surface.
	 */
	TUBE;

	public static final int MAX_FILL = 3;
	public static final int MAX_TRENCH = 5;

	/** The section for a rail at {@code track}, with open cuts up to {@link #MAX_TRENCH} deep. */
	public static Section of(int track, int ground, int water) {
		return of(track, ground, water, MAX_TRENCH);
	}

	/**
	 * The section for a rail at {@code track} over ground whose first free y is {@code ground}, with the water surface's
	 * first free y in {@code water} or {@link HeightProfile#DRY}, and open cuts up to {@code maxTrench} deep.
	 */
	public static Section of(int track, int ground, int water, int maxTrench) {
		if (water != HeightProfile.DRY) {
			return track > water ? BRIDGE : TUBE;
		}
		if (track == ground) {
			return FLAT;
		}
		if (track > ground) {
			return track - ground <= MAX_FILL ? FILL : BRIDGE;
		}
		return ground - track <= maxTrench ? TRENCH : TUNNEL;
	}
}
