package net.valcheff.villagerailways;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a world folder the way the game's own backup does, into the {@code backups} folder of the game or server: in
 * singleplayer the one the game uses, on a dedicated server one beside its world rather than outside its folder.
 */
final class WorldBackup {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private WorldBackup() {
	}

	/** Writes the backup of the world in {@code world} into {@code backups} and returns its file. */
	static Path write(Path world, Path backups) throws IOException {
		String name = world.getFileName().toString();
		Files.createDirectories(backups);
		Path file = backups.resolve(LocalDateTime.now().format(STAMP) + "_" + name + ".zip");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(world)) {
			// The running server holds the lock, which a backup does not need.
			files = walk.filter(Files::isRegularFile)
					.filter(path -> !path.getFileName().toString().equals("session.lock")).toList();
		}
		try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
			for (Path path : files) {
				String entry = world.relativize(path).toString().replace(File.separatorChar, '/');
				zip.putNextEntry(new ZipEntry(name + "/" + entry));
				Files.copy(path, zip);
				zip.closeEntry();
			}
		}
		return file;
	}
}
