package net.valcheff.villagerailways.profile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

/** Draws a line's height profile as a PNG: terrain, water and the track coloured by section, heights stretched. */
public final class ProfileMap {
	private static final int WIDTH = 1600;
	private static final int BORDER = 40;
	private static final int VERTICAL_SCALE = 4;
	private static final Color BACKGROUND = new Color(0xF4F1EA);
	private static final Color GROUND = new Color(0xB59B7C);
	private static final Color WATER = new Color(0x8FBCE0);
	private static final Color CORNER = new Color(0xCCCCCC);
	private static final Map<Section, Color> SECTION_COLORS = Map.of(
			Section.FLAT, new Color(0x202020),
			Section.FILL, new Color(0x8D6E63),
			Section.BRIDGE, new Color(0x1E88E5),
			Section.TRENCH, new Color(0xF28C28),
			Section.TUNNEL, new Color(0x8E24AA));

	private ProfileMap() {
	}

	public static Path write(TrackPlan plan, String title, Path file) throws IOException {
		int length = plan.length();
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int i = 0; i < length; i++) {
			minY = Math.min(minY, Math.min(plan.ground()[i], plan.track()[i]));
			maxY = Math.max(maxY, Math.max(Math.max(plan.ground()[i], plan.track()[i]), plan.water()[i]));
		}
		minY -= 4;
		maxY += 6;
		double scaleX = (double) (WIDTH - 2 * BORDER) / length;
		int height = (maxY - minY) * VERTICAL_SCALE + 2 * BORDER;

		BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, WIDTH, height);
		int bottom = height - BORDER;
		for (int i = 0; i < length; i++) {
			int left = BORDER + (int) (i * scaleX);
			int right = Math.max(left + 1, BORDER + (int) ((i + 1) * scaleX));
			if (plan.corner()[i]) {
				g.setColor(CORNER);
				g.fillRect(left, BORDER, right - left, bottom - BORDER);
			}
			if (plan.water()[i] != HeightProfile.DRY) {
				g.setColor(WATER);
				int top = y(plan.water()[i], minY, bottom);
				g.fillRect(left, top, right - left, bottom - top);
			}
			g.setColor(GROUND);
			int groundTop = y(plan.ground()[i], minY, bottom);
			g.fillRect(left, groundTop, right - left, bottom - groundTop);
		}
		g.setStroke(new BasicStroke(3f));
		for (int i = 1; i < length; i++) {
			g.setColor(SECTION_COLORS.get(plan.sections()[i]));
			g.drawLine(BORDER + (int) ((i - 1) * scaleX), y(plan.track()[i - 1], minY, bottom),
					BORDER + (int) (i * scaleX), y(plan.track()[i], minY, bottom));
		}
		g.setColor(Color.DARK_GRAY);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		g.drawString(title + "   (heights x" + VERTICAL_SCALE + ")", BORDER, 22);
		g.drawString("y " + (maxY - 6), 4, y(maxY - 6, minY, bottom));
		g.drawString("y " + (minY + 4), 4, y(minY + 4, minY, bottom));
		g.dispose();

		Files.createDirectories(file.getParent());
		ImageIO.write(image, "png", file.toFile());
		return file;
	}

	private static int y(int blockY, int minY, int bottom) {
		return bottom - (blockY - minY) * VERTICAL_SCALE;
	}
}
