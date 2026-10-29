package net.valcheff.villagerailways;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.valcheff.villagerailways.network.PlannerSettings;
import net.valcheff.villagerailways.profile.TrackSettings;

/**
 * The server owner's settings in {@code config/village_railways.properties}, read when a world starts. The network
 * values apply to the lines planned from then on; lines keep the path they were planned with. The build values are
 * kept by each world from its first start, in {@code track.properties} of its save folder, because the plans of half
 * built lines are worked out again at every start and have to come out as they were built.
 */
public final class RailwayConfig {
	private static final String TRACK_FILE = "track.properties";

	/** The settings, in the order the file, the config screen and the network carry them. */
	public enum Setting {
		MAX_LINKS("maxLinks", 1, 3, PlannerSettings.DEFAULT.maxLinks()),
		MAX_LENGTH("maxLength", 300, PlannerSettings.DEFAULT.maxGroupLength(), PlannerSettings.DEFAULT.maxLength()),
		MIN_RUN("minRun", 16, 64, PlannerSettings.DEFAULT.minRun()),
		MAX_RUN("maxRun", 32, 128, PlannerSettings.DEFAULT.maxRun()),
		CUT_DEPTH("cutDepth", 2, 7, TrackSettings.DEFAULT.cutDepth()),
		PILLAR_SPACING("pillarSpacing", 4, 16, TrackSettings.DEFAULT.pillarSpacing()),
		SEA_TUBES("seaTubes", 0, 1, TrackSettings.DEFAULT.seaTubes() ? 1 : 0);

		public final String key;
		public final int min;
		public final int max;
		public final int fallback;

		Setting(String key, int min, int max, int fallback) {
			this.key = key;
			this.min = min;
			this.max = max;
			this.fallback = fallback;
		}

		/** Whether a world keeps this value from its first start. */
		public boolean keptByWorld() {
			return this == CUT_DEPTH || this == PILLAR_SPACING || this == SEA_TUBES;
		}
	}

	/** Whether the world about to be created in this game crosses seas in tubes, as chosen when creating it, or null. */
	private static volatile Boolean newWorldSeaTubes;

	private final int[] values;

	private RailwayConfig(int[] values) {
		this.values = values;
	}

	/** Reads the config file in {@code configDir}, and writes it back with every key and its explanation. */
	public static RailwayConfig load(Path configDir) {
		Properties props = read(file(configDir));
		int[] values = new int[Setting.values().length];
		for (Setting setting : Setting.values()) {
			values[setting.ordinal()] = readInt(props, setting);
		}
		RailwayConfig config = of(values);
		config.save(configDir);
		return config;
	}

	/**
	 * Settings with the given values, in {@link Setting} order; a value out of its range takes the default, and so do
	 * both straights of a staircase whose side step is shorter than its straight.
	 */
	public static RailwayConfig of(List<Integer> given) {
		int[] values = new int[Setting.values().length];
		for (Setting setting : Setting.values()) {
			int value = setting.ordinal() < given.size() ? given.get(setting.ordinal()) : setting.fallback;
			values[setting.ordinal()] = value >= setting.min && value <= setting.max ? value : setting.fallback;
		}
		return of(values);
	}

	private static RailwayConfig of(int[] values) {
		int minRun = values[Setting.MIN_RUN.ordinal()];
		int maxRun = values[Setting.MAX_RUN.ordinal()];
		if (maxRun < minRun) {
			VillageRailways.LOGGER.warn("maxRun={} is shorter than minRun={}, using {} and {}", maxRun, minRun,
					Setting.MIN_RUN.fallback, Setting.MAX_RUN.fallback);
			values[Setting.MIN_RUN.ordinal()] = Setting.MIN_RUN.fallback;
			values[Setting.MAX_RUN.ordinal()] = Setting.MAX_RUN.fallback;
		}
		return new RailwayConfig(values);
	}

	public int get(Setting setting) {
		return values[setting.ordinal()];
	}

	/** The values in {@link Setting} order. */
	public List<Integer> values() {
		List<Integer> list = new ArrayList<>();
		for (int value : values) {
			list.add(value);
		}
		return list;
	}

	public PlannerSettings planner() {
		PlannerSettings defaults = PlannerSettings.DEFAULT;
		return new PlannerSettings(get(Setting.MAX_LINKS), get(Setting.MAX_LENGTH), get(Setting.MIN_RUN),
				get(Setting.MAX_RUN), defaults.clearance(), defaults.stationMargin(), defaults.stationSpacing(),
				defaults.villageMargin(), defaults.structureMargin(), defaults.minAngle(), defaults.maxGroupLinks(),
				defaults.maxGroupLength());
	}

	/**
	 * Sets whether the world about to be created in this game crosses seas in tubes, over the config's value: the next
	 * world to start that keeps no build values yet keeps this.
	 */
	public static void chooseNewWorldSeaTubes(boolean seaTubes) {
		newWorldSeaTubes = seaTubes;
	}

	/** The build values for new worlds. */
	public TrackSettings track() {
		return new TrackSettings(get(Setting.CUT_DEPTH), get(Setting.PILLAR_SPACING), get(Setting.SEA_TUBES) == 1);
	}

	/**
	 * The build values of the world saved in {@code folder}: the ones it was first started with. A world that has
	 * lines from before these values were kept was built with the defaults.
	 */
	public TrackSettings trackFor(Path folder) throws IOException {
		Path file = folder.resolve(TRACK_FILE);
		TrackSettings kept;
		if (Files.exists(file)) {
			kept = keptBy(folder);
		} else {
			kept = Files.exists(folder.resolve("network.json")) ? TrackSettings.DEFAULT : newWorldTrack();
			Files.createDirectories(folder);
			try (Writer writer = Files.newBufferedWriter(file)) {
				writer.write("# The build values this world was first started with. Village Railways keeps them, so\n");
				writer.write("# lines planned before and after a restart meet. Do not change them.\n");
				writeTrack(writer, kept);
			}
		}
		if (!kept.equals(track())) {
			VillageRailways.LOGGER.info("This world keeps cutDepth={}, pillarSpacing={} and seaTubes={} from its "
					+ "first start; the values in the config apply to new worlds", kept.cutDepth(), kept.pillarSpacing(),
					kept.seaTubes() ? 1 : 0);
		}
		return kept;
	}

	/** The build values of a world starting for the first time: the config's, with the sea tubes chosen for it. */
	private TrackSettings newWorldTrack() {
		TrackSettings track = track();
		Boolean chosen = newWorldSeaTubes;
		newWorldSeaTubes = null;
		return chosen == null ? track : new TrackSettings(track.cutDepth(), track.pillarSpacing(), chosen);
	}

	/**
	 * The build values a world saved in {@code folder} keeps, or the defaults for one that keeps none yet. One that
	 * kept its values before sea tubes came has none, as the default.
	 */
	public static TrackSettings keptBy(Path folder) {
		Properties props = read(folder.resolve(TRACK_FILE));
		return new TrackSettings(readInt(props, Setting.CUT_DEPTH), readInt(props, Setting.PILLAR_SPACING),
				readInt(props, Setting.SEA_TUBES) == 1);
	}

	/** Writes the config file in {@code configDir} with every key and its explanation. */
	public void save(Path configDir) {
		Path file = file(configDir);
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				writer.write("# Village Railways\n");
				writer.write("# Read when a world starts. A value out of its range falls back to the default, with a\n");
				writer.write("# warning in the log.\n");
				writer.write("#\n");
				writer.write("# Changes to these apply to the lines planned after the next start. Lines planned before\n");
				writer.write("# stay as they are.\n");
				writer.write("\n# Most lines one village may have (1-3).\n");
				writer.write("maxLinks=" + get(Setting.MAX_LINKS) + "\n");
				writer.write("\n# Longest line between two villages, measured between their centres, in blocks (300-"
						+ Setting.MAX_LENGTH.max + ").\n");
				writer.write("# Trunk lines, which join groups of villages that would be left apart, may be longer.\n");
				writer.write("maxLength=" + get(Setting.MAX_LENGTH) + "\n");
				writer.write("\n# A line at a slant is built as a staircase of straights: the shortest straight\n");
				writer.write("# (16-64) and the longest side step (32-128, at least minRun), in blocks.\n");
				writer.write("minRun=" + get(Setting.MIN_RUN) + "\n");
				writer.write("maxRun=" + get(Setting.MAX_RUN) + "\n");
				writer.write("\n# A world keeps these from its first start, so changes apply to new worlds only.\n");
				writeTrack(writer, track());
			}
		} catch (IOException e) {
			VillageRailways.LOGGER.warn("Could not write {}", file, e);
		}
	}

	private static Path file(Path configDir) {
		return configDir.resolve(VillageRailways.MOD_ID + ".properties");
	}

	private static void writeTrack(Writer writer, TrackSettings settings) throws IOException {
		writer.write("# Deepest open cut, in blocks; where the ground is higher the line goes into a tunnel (2-7).\n");
		writer.write("cutDepth=" + settings.cutDepth() + "\n");
		writer.write("# Blocks between the pillars of a bridge in the open (4-16).\n");
		writer.write("pillarSpacing=" + settings.pillarSpacing() + "\n");
		writer.write("# Whether lines cross seas in glass tubes under water (1) or keep off open sea (0).\n");
		writer.write("seaTubes=" + (settings.seaTubes() ? 1 : 0) + "\n");
	}

	private static Properties read(Path file) {
		Properties props = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				props.load(reader);
			} catch (IOException e) {
				VillageRailways.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		return props;
	}

	private static int readInt(Properties props, Setting setting) {
		String raw = props.getProperty(setting.key);
		if (raw == null) {
			return setting.fallback;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value >= setting.min && value <= setting.max) {
				return value;
			}
		} catch (NumberFormatException e) {
			// Reported below like a value out of range.
		}
		VillageRailways.LOGGER.warn("Invalid {}={} (allowed {}-{}), using {}", setting.key, raw, setting.min,
				setting.max, setting.fallback);
		return setting.fallback;
	}
}
