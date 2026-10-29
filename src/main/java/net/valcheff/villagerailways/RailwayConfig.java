package net.valcheff.villagerailways;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
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

	private final PlannerSettings planner;
	private final TrackSettings track;

	private RailwayConfig(PlannerSettings planner, TrackSettings track) {
		this.planner = planner;
		this.track = track;
	}

	/** Reads the config file in {@code configDir}, and writes it back with every key and its explanation. */
	public static RailwayConfig load(Path configDir) {
		Path file = configDir.resolve(VillageRailways.MOD_ID + ".properties");
		Properties props = read(file);
		PlannerSettings defaults = PlannerSettings.DEFAULT;
		int maxLinks = readInt(props, "maxLinks", defaults.maxLinks(), 1, 3);
		int maxLength = readInt(props, "maxLength", defaults.maxLength(), 300, defaults.maxGroupLength());
		int minRun = readInt(props, "minRun", defaults.minRun(), 16, 64);
		int maxRun = readInt(props, "maxRun", defaults.maxRun(), 32, 128);
		if (maxRun < minRun) {
			VillageRailways.LOGGER.warn("maxRun={} is shorter than minRun={}, using {} and {}", maxRun, minRun,
					defaults.minRun(), defaults.maxRun());
			minRun = defaults.minRun();
			maxRun = defaults.maxRun();
		}
		PlannerSettings planner = new PlannerSettings(maxLinks, maxLength, minRun, maxRun, defaults.clearance(),
				defaults.stationMargin(), defaults.stationSpacing(), defaults.villageMargin(), defaults.structureMargin(),
				defaults.minAngle(), defaults.maxOceanCrossing(), defaults.maxGroupLinks(), defaults.maxGroupLength());
		RailwayConfig config = new RailwayConfig(planner, readTrack(props, TrackSettings.DEFAULT));
		config.save(file);
		return config;
	}

	public PlannerSettings planner() {
		return planner;
	}

	/**
	 * The build values of the world saved in {@code folder}: the ones it was first started with. A world that has
	 * lines from before these values were kept was built with the defaults.
	 */
	public TrackSettings trackFor(Path folder) throws IOException {
		Path file = folder.resolve(TRACK_FILE);
		TrackSettings kept;
		if (Files.exists(file)) {
			kept = readTrack(read(file), TrackSettings.DEFAULT);
		} else {
			kept = Files.exists(folder.resolve("network.json")) ? TrackSettings.DEFAULT : track;
			Files.createDirectories(folder);
			try (Writer writer = Files.newBufferedWriter(file)) {
				writer.write("# The build values this world was first started with. Village Railways keeps them, so\n");
				writer.write("# lines planned before and after a restart meet. Do not change them.\n");
				writeTrack(writer, kept);
			}
		}
		if (!kept.equals(track)) {
			VillageRailways.LOGGER.info("This world keeps cutDepth={} and pillarSpacing={} from its first start; "
					+ "the values in the config apply to new worlds", kept.cutDepth(), kept.pillarSpacing());
		}
		return kept;
	}

	private void save(Path file) {
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
				writer.write("maxLinks=" + planner.maxLinks() + "\n");
				writer.write("\n# Longest line between two villages, measured between their centres, in blocks (300-"
						+ PlannerSettings.DEFAULT.maxGroupLength() + ").\n");
				writer.write("# Trunk lines, which join groups of villages that would be left apart, may be longer.\n");
				writer.write("maxLength=" + planner.maxLength() + "\n");
				writer.write("\n# A line at a slant is built as a staircase of straights: the shortest straight\n");
				writer.write("# (16-64) and the longest side step (32-128, at least minRun), in blocks.\n");
				writer.write("minRun=" + planner.minRun() + "\n");
				writer.write("maxRun=" + planner.maxRun() + "\n");
				writer.write("\n# A world keeps these from its first start, so changes apply to new worlds only.\n");
				writeTrack(writer, track);
			}
		} catch (IOException e) {
			VillageRailways.LOGGER.warn("Could not write {}", file, e);
		}
	}

	private static void writeTrack(Writer writer, TrackSettings settings) throws IOException {
		writer.write("# Deepest open cut, in blocks; where the ground is higher the line goes into a tunnel (2-7).\n");
		writer.write("cutDepth=" + settings.cutDepth() + "\n");
		writer.write("# Blocks between the pillars of a bridge in the open (4-16).\n");
		writer.write("pillarSpacing=" + settings.pillarSpacing() + "\n");
	}

	private static TrackSettings readTrack(Properties props, TrackSettings defaults) {
		return new TrackSettings(readInt(props, "cutDepth", defaults.cutDepth(), 2, 7),
				readInt(props, "pillarSpacing", defaults.pillarSpacing(), 4, 16));
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

	private static int readInt(Properties props, String key, int fallback, int min, int max) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value >= min && value <= max) {
				return value;
			}
		} catch (NumberFormatException e) {
			// Reported below like a value out of range.
		}
		VillageRailways.LOGGER.warn("Invalid {}={} (allowed {}-{}), using {}", key, raw, min, max, fallback);
		return fallback;
	}
}
