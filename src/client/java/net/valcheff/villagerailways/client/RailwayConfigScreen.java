package net.valcheff.villagerailways.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.RailwayConfig;
import net.valcheff.villagerailways.RailwayConfig.Setting;

/**
 * The settings of Village Railways. In a world whose server runs the mod, a singleplayer world included, they are that
 * server's: read from it, and saved to it by an operator or the player hosting the world. Anywhere else they are the
 * ones in this game's own config file, used by the worlds it hosts.
 */
public class RailwayConfigScreen extends Screen {
	private static final int WIDTH = 310;

	private final Screen parent;
	private final boolean onServer;
	private HeaderAndFooterLayout layout;
	/** The settings in {@link Setting} order, null until the server answers. */
	private List<Integer> values;
	/** The cut depth and pillar spacing the current world keeps, null outside a world. */
	private List<Integer> world;
	private boolean canEdit;
	private boolean saving;
	private Component status;

	public RailwayConfigScreen(Screen parent) {
		super(Component.literal("Village Railways"));
		this.parent = parent;
		boolean connected = Minecraft.getInstance().getConnection() != null;
		onServer = connected && ClientPlayNetworking.canSend(ConfigSync.Request.TYPE);
		if (onServer) {
			status = Component.literal("Asking the server for its settings...");
			ClientPlayNetworking.send(new ConfigSync.Request());
		} else {
			values = new ArrayList<>(RailwayConfig.load(configDir()).values());
			canEdit = true;
			status = Component.literal(connected
					? "This server does not run Village Railways. These are the settings of the worlds this game hosts."
					: "The settings of the worlds this game hosts. They apply when a world starts.");
		}
	}

	/** The server's settings, as it sent them when asked or after a save. */
	void show(ConfigSync.State state) {
		values = new ArrayList<>(state.values());
		world = state.world();
		canEdit = state.canEdit();
		status = Component.literal(saving ? "Saved. The server uses them from its next start."
				: canEdit ? "The settings of this server. They apply from its next start."
				: "The settings of this server. Only operators can change them.");
		saving = false;
		rebuildWidgets();
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);
		layout.addTitleHeader(title, font);
		LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(6));
		contents.defaultCellSetting().alignHorizontallyCenter();
		if (values != null) {
			for (Setting setting : Setting.values()) {
				if (setting == Setting.CUT_DEPTH) {
					contents.addChild(text(world == null ? "For new worlds:"
							: "For new worlds. This one keeps a cut depth of " + world.get(0) + " and pillars every "
									+ world.get(1) + " blocks:"));
				}
				int index = setting.ordinal();
				IntSlider slider = contents.addChild(new IntSlider(setting.min, setting.max, step(setting),
						values.get(index), value -> label(setting, value), value -> values.set(index, value)));
				slider.setTooltip(Tooltip.create(Component.literal(explanation(setting))));
				slider.active = canEdit;
			}
		}
		contents.addChild(text(status.getString()));

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		Button save = footer.addChild(Button.builder(Component.literal("Save"), button -> save()).width(150).build());
		save.active = canEdit && values != null;
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(150).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private void save() {
		if (onServer) {
			saving = true;
			status = Component.literal("Saving...");
			ClientPlayNetworking.send(new ConfigSync.Save(List.copyOf(values)));
		} else {
			RailwayConfig.of(values).save(configDir());
			values = new ArrayList<>(RailwayConfig.load(configDir()).values());
			status = Component.literal("Saved. They apply the next time a world starts.");
		}
		rebuildWidgets();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private MultiLineTextWidget text(String text) {
		return new MultiLineTextWidget(Component.literal(text), font).setMaxWidth(WIDTH).setCentered(true);
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	private static int step(Setting setting) {
		return switch (setting) {
			case MAX_LENGTH -> 100;
			case MIN_RUN, MAX_RUN -> 4;
			default -> 1;
		};
	}

	private static Component label(Setting setting, int value) {
		return Component.literal(switch (setting) {
			case MAX_LINKS -> "Lines per village: " + value;
			case MAX_LENGTH -> "Longest line: " + value + " blocks";
			case MIN_RUN -> "Shortest straight of a slanted line: " + value + " blocks";
			case MAX_RUN -> "Longest side step of a slanted line: " + value + " blocks";
			case CUT_DEPTH -> "Deepest open cut: " + value + " blocks";
			case PILLAR_SPACING -> "Bridge pillars every " + value + " blocks";
		});
	}

	private static String explanation(Setting setting) {
		return switch (setting) {
			case MAX_LINKS -> "Most lines one village may have.";
			case MAX_LENGTH -> "Longest line between two villages, measured between their centres. Trunk lines, which "
					+ "join groups of villages that would be left apart, may be longer.";
			case MIN_RUN, MAX_RUN -> "A line at a slant is built as a staircase of straights. The side step is at "
					+ "least as long as the straight.";
			case CUT_DEPTH -> "Where the ground is higher than this over the rail, the line goes into a tunnel.";
			case PILLAR_SPACING -> "Blocks between the pillars of a bridge in the open.";
		};
	}

	/** A slider over whole numbers in steps. */
	private static class IntSlider extends AbstractSliderButton {
		private final int min;
		private final int max;
		private final int step;
		private final IntFunction<Component> label;
		private final IntConsumer apply;

		IntSlider(int min, int max, int step, int current, IntFunction<Component> label, IntConsumer apply) {
			super(0, 0, WIDTH, 20, Component.empty(), 0);
			this.min = min;
			this.max = max;
			this.step = step;
			this.label = label;
			this.apply = apply;
			this.value = (Math.clamp(current, min, max) - min) / (double) (max - min);
			updateMessage();
		}

		private int current() {
			int raw = min + (int) Math.round(value * (max - min));
			return Math.clamp(min + Math.round((raw - min) / (float) step) * step, min, max);
		}

		@Override
		protected void updateMessage() {
			setMessage(label.apply(current()));
		}

		@Override
		protected void applyValue() {
			apply.accept(current());
		}
	}
}
