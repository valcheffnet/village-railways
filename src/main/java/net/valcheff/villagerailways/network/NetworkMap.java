package net.valcheff.villagerailways.network;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/** Draws the network as a PNG for checking it by eye: village footprints, centers and lines, north up. */
public final class NetworkMap {
	private static final int MAX_SIZE = 1600;
	private static final int BORDER = 40;
	private static final Color BACKGROUND = new Color(0xF4F1EA);
	private static final Color OCEAN = new Color(0xB9D4E8);
	private static final Color FOOTPRINT = new Color(0xC9C2B4);
	private static final Color VILLAGE = new Color(0xF28C28);
	private static final Color PLANNED = new Color(0x202020);
	private static final Color BUILT = new Color(0x2E7D32);
	private static final Color LABEL = new Color(0x555555);

	private NetworkMap() {
	}

	/** Tells whether a block column is ocean, for shading the map. */
	@FunctionalInterface
	public interface OceanTest {
		boolean isOcean(int x, int z);
	}

	public static Path write(NetworkPlanner planner, OceanTest ocean, Path file) throws IOException {
		List<Village> villages = planner.villages();
		List<Link> links = planner.links();
		int minX = villages.stream().mapToInt(Village::minX).min().orElse(0);
		int minZ = villages.stream().mapToInt(Village::minZ).min().orElse(0);
		int maxX = villages.stream().mapToInt(Village::maxX).max().orElse(1);
		int maxZ = villages.stream().mapToInt(Village::maxZ).max().orElse(1);
		double scale = (double) (MAX_SIZE - 2 * BORDER) / Math.max(maxX - minX, maxZ - minZ);
		int width = (int) ((maxX - minX) * scale) + 2 * BORDER;
		int height = (int) ((maxZ - minZ) * scale) + 2 * BORDER;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, width, height);

		g.setColor(OCEAN);
		int cell = Math.max(16, (int) Math.ceil(4 / scale));
		for (int x = minX - (int) (BORDER / scale); x <= maxX + BORDER / scale; x += cell) {
			for (int z = minZ - (int) (BORDER / scale); z <= maxZ + BORDER / scale; z += cell) {
				if (ocean.isOcean(x, z)) {
					int left = px(x, minX, scale);
					int top = px(z, minZ, scale);
					g.fillRect(left, top, px(x + cell, minX, scale) - left, px(z + cell, minZ, scale) - top);
				}
			}
		}
		g.setColor(FOOTPRINT);
		for (Village village : villages) {
			int x = px(village.minX(), minX, scale);
			int y = px(village.minZ(), minZ, scale);
			g.fillRect(x, y, Math.max(2, px(village.maxX(), minX, scale) - x), Math.max(2, px(village.maxZ(), minZ, scale) - y));
		}
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (Link link : links) {
			g.setColor(link.status() == Link.Status.BUILT ? BUILT : PLANNED);
			List<Point> path = link.path();
			for (int i = 1; i < path.size(); i++) {
				g.drawLine(px(path.get(i - 1).x(), minX, scale), px(path.get(i - 1).z(), minZ, scale),
						px(path.get(i).x(), minX, scale), px(path.get(i).z(), minZ, scale));
			}
		}
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		for (Village village : villages) {
			int x = px(village.center().x(), minX, scale);
			int y = px(village.center().z(), minZ, scale);
			g.setColor(VILLAGE);
			g.fillOval(x - 7, y - 7, 14, 14);
			g.setColor(LABEL);
			g.drawString(village.center().x() + ", " + village.center().z(), x + 9, y - 6);
		}
		g.dispose();

		Files.createDirectories(file.getParent());
		ImageIO.write(image, "png", file.toFile());
		return file;
	}

	private static int px(int block, int min, double scale) {
		return (int) Math.round((block - min) * scale) + BORDER;
	}
}
