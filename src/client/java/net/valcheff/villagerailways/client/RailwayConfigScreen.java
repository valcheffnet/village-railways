package net.valcheff.villagerailways.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.valcheff.villagerailways.ConfigSync;
import net.valcheff.villagerailways.Platform;
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
	private SettingsList list;
	/** The settings in {@link Setting} order, null until the server answers. */
	private List<Integer> values;
	/** The settings as saved, to tell whether Done has changes to save. */
	private List<Integer> stored;
	/** The cut depth, pillar spacing and sea tubes (1 or 0) the current world keeps, null outside a world. */
	private List<Integer> world;
	private boolean canEdit;
	/** Whether the server's world opens its existing land to lines at its next start. */
	private boolean openLand;
	private boolean saving;
	private Component status;

	public RailwayConfigScreen(Screen parent) {
		super(Component.literal("Village Railways"));
		this.parent = parent;
		boolean connected = Minecraft.getInstance().getConnection() != null;
		onServer = connected && ClientPlatform.get().canSend(ConfigSync.Request.ID);
		if (onServer) {
			status = Component.literal("Asking the server for its settings...");
			ClientPlatform.get().send(new ConfigSync.Request());
		} else {
			values = new ArrayList<>(RailwayConfig.load(configDir()).values());
			stored = List.copyOf(values);
			canEdit = true;
			status = Component.literal(connected
					? "This server does not run Village Railways. These are the settings of the worlds this game hosts."
					: "The settings of the worlds this game hosts. They apply when a world starts.");
		}
	}

	/** The server's settings, as it sent them when asked or after a save. */
	public void show(ConfigSync.State state) {
		values = new ArrayList<>(state.values());
		stored = List.copyOf(values);
		world = state.world();
		openLand = state.openLand();
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
		layout.addToHeader(new StringWidget(title, font));
		list = new SettingsList();
		if (values != null) {
			for (Setting setting : Setting.values()) {
				if (setting == Setting.CUT_DEPTH) {
					list.addText(world == null ? "For new worlds:"
							: "For new worlds. This one keeps a cut depth of " + world.get(0) + ", pillars every "
									+ world.get(1) + " blocks and sea tubes " + (world.get(2) == 1 ? "on" : "off")
									+ ":");
				}
				int index = setting.ordinal();
				Tooltip tooltip = Tooltip.create(Component.literal(explanation(setting)));
				AbstractWidget widget;
				if (setting.isSwitch()) {
					// A cycle button sets its tooltip again on every click, from the one given to its builder.
					widget = CycleButton.onOffBuilder(values.get(index) == 1).withTooltip(on -> tooltip)
							.create(0, 0, WIDTH, 20, label(setting, 1), (button, on) -> values.set(index, on ? 1 : 0));
				} else {
					widget = new IntSlider(setting.min, setting.max, step(setting), values.get(index),
							value -> label(setting, value), value -> values.set(index, value));
					widget.setTooltip(tooltip);
				}
				widget.active = canEdit;
				list.addWidget(widget);
			}
		}
		if (onServer && values != null) {
			Button land = Button.builder(Component.literal(openLand
					? "Existing land opens at the next start: call off" : "Open the existing land to lines..."),
					button -> openLand()).width(WIDTH).build();
			land.setTooltip(Tooltip.create(Component.literal("At the next start of the server, lines are planned "
					+ "through the land that exists now as well, which they keep out of otherwise, and built there as "
					+ "it loads.")));
			land.active = canEdit;
			list.addWidget(land);
		}
		list.addText(status.getString());
		addRenderableWidget(list);

		GridLayout.RowHelper footer = layout.addToFooter(new GridLayout().columnSpacing(8)).createRowHelper(2);
		Button apply = footer.addChild(Button.builder(Component.literal("Apply"), button -> save()).width(150)
				.build());
		apply.active = canEdit && values != null;
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(150).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private void save() {
		if (onServer) {
			saving = true;
			status = Component.literal("Saving...");
			ClientPlatform.get().send(new ConfigSync.Save(List.copyOf(values)));
		} else {
			RailwayConfig.of(values).save(configDir());
			values = new ArrayList<>(RailwayConfig.load(configDir()).values());
			stored = List.copyOf(values);
			status = Component.literal("Saved. They apply the next time a world starts.");
		}
		rebuildWidgets();
	}

	/** Asks for the existing land to open, backed up first, after a warning, or calls that off. */
	private void openLand() {
		if (openLand) {
			ClientPlatform.get().send(new ConfigSync.OpenLand(false));
			return;
		}
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				ClientPlatform.get().send(new ConfigSync.OpenLand(true));
			}
			minecraft.setScreen(this);
		}, Component.literal("Open the existing land to lines?"), Component.literal("At the next start of the server, "
				+ "the world is backed up, then railway lines are planned through the land that exists now, where "
				+ "players have built. They can cut through houses, farms and redstone, and are built there as the "
				+ "land loads. Only the backup can undo it."),
				Component.literal("Backup and Build"), CommonComponents.GUI_CANCEL));
	}

	/** The background first: on this version a screen draws it itself, or every frame stays under the next one. */
	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		list.updateSize(width, height, layout.getHeaderHeight(), height - layout.getFooterHeight());
	}

	/** Saves what was changed and not saved yet, then goes back. */
	@Override
	public void onClose() {
		if (canEdit && values != null && !values.equals(stored)) {
			save();
		}
		minecraft.setScreen(parent);
	}

	private static Path configDir() {
		return Platform.get().configDir();
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
			case STARTER_MAP -> "Map for new players";
			case UPDATE_CHECK -> "Check for new versions";
			case SUPPORT_MESSAGE -> "Discord link for admins";
			case CUT_DEPTH -> "Deepest open cut: " + value + " blocks";
			case PILLAR_SPACING -> "Bridge pillars every " + value + " blocks";
			case SEA_TUBES -> "Glass tubes under the sea";
		});
	}

	private static String explanation(Setting setting) {
		return switch (setting) {
			case MAX_LINKS -> "Most lines one village may have.";
			case MAX_LENGTH -> "Longest line between two villages, measured between their centres. Trunk lines, which "
					+ "join groups of villages that would be left apart, may be longer.";
			case MIN_RUN, MAX_RUN -> "A line at a slant is built as a staircase of straights. The side step is at "
					+ "least as long as the straight.";
			case STARTER_MAP -> "A player who joins the world for the first time gets a map of the railway network "
					+ "round where they arrive. Players who were here before get none.";
			case UPDATE_CHECK -> "When the server starts it asks GitHub once whether a newer version is out, and tells "
					+ "admins when they join. Nothing about the server or its players is sent.";
			case SUPPORT_MESSAGE -> "Admins get a line with the Discord link for questions and bugs each time they join.";
			case CUT_DEPTH -> "Where the ground is higher than this over the rail, the line goes into a tunnel.";
			case PILLAR_SPACING -> "Blocks between the pillars of a bridge in the open.";
			case SEA_TUBES -> "Lines cross seas in glass tubes under water. Off, they keep off open sea and bridge "
					+ "only narrow straits.";
		};
	}

	/** The settings between the title and the buttons, one to a row, scrolling when they do not fit. */
	private class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
		/** A widget's height and the gap under it; a row of text takes two lines. */
		private static final int ROW_HEIGHT = 25;

		SettingsList() {
			super(RailwayConfigScreen.this.minecraft, RailwayConfigScreen.this.width, RailwayConfigScreen.this.height,
					0, 0, ROW_HEIGHT);
			setRenderBackground(false);
			setRenderTopAndBottom(false);
		}

		void addWidget(AbstractWidget widget) {
			addEntry(new Row(widget, List.of()));
		}

		void addText(String text) {
			addEntry(new Row(null, font.split(Component.literal(text), WIDTH)));
		}

		@Override
		public int getRowWidth() {
			return WIDTH;
		}

		@Override
		protected int getScrollbarPosition() {
			return width / 2 + WIDTH / 2 + 10;
		}

		/** One widget, or up to two lines of text. */
		private class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final AbstractWidget widget;
			private final List<FormattedCharSequence> lines;

			Row(AbstractWidget widget, List<FormattedCharSequence> lines) {
				this.widget = widget;
				this.lines = lines;
			}

			@Override
			public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
					int mouseX, int mouseY, boolean hovered, float partialTick) {
				if (widget != null) {
					widget.setX(left);
					widget.setY(top);
					widget.render(graphics, mouseX, mouseY, partialTick);
					return;
				}
				for (int i = 0; i < lines.size(); i++) {
					graphics.drawCenteredString(font, lines.get(i), left + rowWidth / 2, top + i * font.lineHeight,
							0xFFFFFF);
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return widget == null ? List.of() : List.of(widget);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return widget == null ? List.of() : List.of(widget);
			}
		}
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
			this.value = (Mth.clamp(current, min, max) - min) / (double) (max - min);
			updateMessage();
		}

		private int current() {
			int raw = min + (int) Math.round(value * (max - min));
			return Mth.clamp(min + Math.round((raw - min) / (float) step) * step, min, max);
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
