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
	private static final Color TRUNK_BUILT = new Color(0x4A148C);
	private static final Color TRUNK_PLANNED = new Color(0xAB47BC);
	private static final Color OBSTACLE = new Color(0xD64545);
	private static final Color OBSTACLE_LABEL = new Color(0x9E2B2B);
	private static final Color LABEL = new Color(0x555555);

	private NetworkMap() {
	}

	/** Tells whether a block column is ocean, for shading the map. */
	@FunctionalInterface
	public interface OceanTest {
		boolean isOcean(int x, int z);
	}

	/** An area the network keeps out of, such as a structure, as minX, minZ, maxX, maxZ with a name. */
	public record Obstacle(String name, int minX, int minZ, int maxX, int maxZ) {
	}

	/** Obstacles in a block area, looked up once the map knows its extent. */
	@FunctionalInterface
	public interface ObstacleSource {
		List<Obstacle> obstaclesIn(int minX, int minZ, int maxX, int maxZ);
	}

	/** Draws the square of {@code radius} blocks around {@code center}: the villages in it and every line touching it. */
	public static Path write(NetworkPlanner planner, Point center, int radius, OceanTest ocean, ObstacleSource obstacles,
			Path file) throws IOException {
		int minX = center.x() - radius;
		int minZ = center.z() - radius;
		int maxX = center.x() + radius;
		int maxZ = center.z() + radius;
		Box area = new Box(minX, minZ, maxX, maxZ);
		List<Village> villages = planner.villages().stream()
				.filter(village -> area.touches(village.center(), village.center(), 0))
				.toList();
		List<Link> links = planner.links().stream()
				.filter(link -> {
					for (int i = 1; i < link.path().size(); i++) {
						if (area.touches(link.path().get(i - 1), link.path().get(i), 0)) {
							return true;
						}
					}
					return false;
				})
				.toList();
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
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		for (Obstacle obstacle : obstacles.obstaclesIn(minX, minZ, maxX, maxZ)) {
			int x = px(obstacle.minX(), minX, scale);
			int y = px(obstacle.minZ(), minZ, scale);
			g.setColor(OBSTACLE);
			g.fillRect(x, y, Math.max(3, px(obstacle.maxX(), minX, scale) - x), Math.max(3, px(obstacle.maxZ(), minZ, scale) - y));
			g.setColor(OBSTACLE_LABEL);
			g.drawString(obstacle.name().replace("minecraft:", ""), x + 5, y + 12);
		}
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (Link link : links) {
			boolean built = link.status() == Link.Status.BUILT;
			g.setColor(link.trunk() ? (built ? TRUNK_BUILT : TRUNK_PLANNED) : built ? BUILT : PLANNED);
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
