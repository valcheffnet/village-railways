package net.valcheff.villagerailways;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells admins about a newer version and where to ask for help. The server asks GitHub's release list once when the
 * world starts (the request carries no data about the server or its players), and admins see the result when they
 * join. {@code updateCheck} and {@code supportMessage} in the config turn the two off.
 */
final class UpdateCheck {
	private static final URI RELEASES =
			URI.create("https://api.github.com/repos/valcheffnet/village-railways/releases?per_page=20");
	private static final String DISCORD = "https://discord.com/invite/X6yxJpk9Fz";
	private static final String CURSEFORGE = "https://www.curseforge.com/minecraft/mc-mods/village-railways/files";
	private static final String HANGAR = "https://hangar.papermc.io/SasnycoN/VillageRailways/versions";

	/** The newer version the last check found, or null. Written by the HTTP client's thread. */
	private static volatile String newer;
	private static volatile boolean supportMessage;

	private UpdateCheck() {
	}

	static void start(RailwayConfig config) {
		newer = null;
		supportMessage = config.supportMessage();
		if (supportMessage) {
			VillageRailways.LOGGER.info("Questions or bugs? Come to Discord: {}", DISCORD);
		}
		if (!config.updateCheck()) {
			return;
		}
		String current = release(Platform.get().version());
		HttpRequest request = HttpRequest.newBuilder(RELEASES)
				.timeout(Duration.ofSeconds(10))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "village-railways/" + current)
				.build();
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
				.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> {
					if (error != null || response.statusCode() != 200) {
						VillageRailways.LOGGER.debug("Update check failed: {}",
								error != null ? error.toString() : "HTTP " + response.statusCode());
						return;
					}
					String latest = latest(response.body());
					if (latest != null && compare(latest, current) > 0) {
						newer = latest;
						VillageRailways.LOGGER.info("Village Railways {} is out, this server has {}: {}", latest, current,
								downloadPage());
					}
				});
	}

	static void joined(ServerPlayer player) {
		if (!ConfigSync.canEdit(player.level().getServer(), player)) {
			return;
		}
		String version = newer;
		if (version != null) {
			player.sendSystemMessage(Component.literal("[Village Railways] Version " + version + " is out, this world has "
					+ release(Platform.get().version()) + ". ").append(link("Download", downloadPage()))
					.withStyle(ChatFormatting.GOLD));
		}
		if (supportMessage) {
			player.sendSystemMessage(Component.literal("[Village Railways] Questions or bugs? Come to Discord: ")
					.append(link("click here", DISCORD)).withStyle(ChatFormatting.GRAY));
		}
	}

	/** CurseForge only allows downloads from CurseForge, and Hangar is where Paper users look. */
	private static String downloadPage() {
		return Platform.get().paper() ? HANGAR : CURSEFORGE;
	}

	private static Component link(String text, String url) {
		return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
	}

	/** The release a jar's version belongs to: 1.0.0 for 1.0.0+26.3, as the GitHub release is the same for all. */
	static String release(String version) {
		int plus = version.indexOf('+');
		return plus < 0 ? version : version.substring(0, plus);
	}

	/** The highest version among published releases; drafts and test builds don't count. */
	static String latest(String json) {
		String best = null;
		for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
			JsonObject release = element.getAsJsonObject();
			if (release.has("draft") && release.get("draft").getAsBoolean()) {
				continue;
			}
			String tag = release.get("tag_name").getAsString();
			String version = tag.startsWith("v") ? tag.substring(1) : tag;
			if (version.toLowerCase(Locale.ROOT).contains("test")) {
				continue;
			}
			if (best == null || compare(version, best) > 0) {
				best = version;
			}
		}
		return best;
	}

	/**
	 * Compares versions like 1.2.0-beta: the numbers first, then a version without a suffix is newer than the same
	 * numbers with one (1.0.0 after 1.0.0-beta), then the suffixes as text.
	 */
	static int compare(String a, String b) {
		String[] pa = a.split("-", 2);
		String[] pb = b.split("-", 2);
		String[] na = pa[0].split("\\.");
		String[] nb = pb[0].split("\\.");
		for (int i = 0; i < Math.max(na.length, nb.length); i++) {
			int c = Integer.compare(number(na, i), number(nb, i));
			if (c != 0) {
				return c;
			}
		}
		if (pa.length != pb.length) {
			return pa.length < pb.length ? 1 : -1;
		}
		return pa.length == 1 ? 0 : pa[1].compareTo(pb[1]);
	}

	private static int number(String[] parts, int i) {
		if (i >= parts.length) {
			return 0;
		}
		try {
			return Integer.parseInt(parts[i]);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
