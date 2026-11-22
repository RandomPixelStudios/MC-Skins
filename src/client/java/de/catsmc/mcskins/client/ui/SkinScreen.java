package de.catsmc.mcskins.client.ui;

import com.mojang.logging.LogUtils;
import de.catsmc.mcskins.client.compat.GameCompat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

abstract class SkinScreen extends Screen {
	static final int BORDER = 0xFF000000;
	static final int ACCENT = 0xFFFFFFFF;
	static final int MUTED = 0xFFA0A0A0;
	static final int WARNING = 0xFFFFD38A;
	static final int SUCCESS = 0xFF75D6AD;
	private static final Logger LOGGER = LogUtils.getLogger();

	protected final Screen parent;
	protected String status = "";
	private final List<Button> selectedButtons = new ArrayList<>();

	SkinScreen(String titleKey, Screen parent) {
		super(Component.translatable(titleKey));
		this.parent = parent;
	}

	static String tr(String key, Object... arguments) {
		return I18n.get(key, arguments);
	}

	@Override
	protected void rebuildWidgets() {
		selectedButtons.clear();
		super.rebuildWidgets();
	}

	@Override
	protected void clearWidgets() {
		selectedButtons.clear();
		super.clearWidgets();
	}

	protected Button button(String label, int x, int y, int width, Runnable action) {
		return button(label, x, y, width, 20, action);
	}

	protected Button button(String label, int x, int y, int width, int height, Runnable action) {
		return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, width, height).build());
	}

	/** A button with a hover tooltip; the tooltip should name the shortcut if one exists. */
	protected Button button(String label, String tooltip, int x, int y, int width, int height, Runnable action) {
		Button button = button(label, x, y, width, height, action);
		button.setTooltip(Tooltip.create(Component.literal(tooltip)));
		return button;
	}

	/** Marks a button as the selected choice in a group; it gets an accent outline. */
	protected Button selected(Button button, boolean selected) {
		if (selected) {
			selectedButtons.add(button);
		}
		return button;
	}

	protected EditBox field(String label, String value, int x, int y, int width, int maxLength) {
		EditBox field = new EditBox(font, x, y, width, 20, Component.literal(label));
		field.setMaxLength(maxLength);
		field.setValue(value);
		field.setHint(Component.literal(label).withColor(0xFF6F7780));
		return addRenderableWidget(field);
	}

	protected void failure(Exception exception) {
		LOGGER.warn("MC-Skins: {}", String.valueOf(exception));
		String message = exception.getMessage();
		status = tr("mcskins.error", message == null ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' '));
	}

	protected void text(GuiGraphicsExtractor graphics, String text, int x, int y, int color, int maxWidth) {
		graphics.text(font, font.plainSubstrByWidth(text, Math.max(0, maxWidth)), x, y, color, true);
	}

	protected void centered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color, int maxWidth) {
		String clipped = font.plainSubstrByWidth(text, Math.max(0, maxWidth));
		graphics.text(font, clipped, centerX - font.width(clipped) / 2, y, color, true);
	}

	/** A vanilla-style section caption over the menu background, clipped to the section width. */
	protected void caption(GuiGraphicsExtractor graphics, int x, int y, int width, String caption) {
		graphics.text(font, font.plainSubstrByWidth(caption, Math.max(0, width - 8)), x + 4, y + 3, 0xFFFFFFFF, true);
	}

	protected boolean showTitle() {
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		for (Button button : selectedButtons) {
			if (button.visible) {
				graphics.outline(button.getX() - 1, button.getY() - 1, button.getWidth() + 2, button.getHeight() + 2, ACCENT);
			}
		}
		if (showTitle()) {
			centered(graphics, title.getString(), width / 2, 12, 0xFFFFFFFF, width - 20);
		}
		if (!status.isEmpty()) {
			text(graphics, status, 8, height - 13, WARNING, width - 16);
		}
	}

	@Override
	public void onClose() {
		GameCompat.setScreen(minecraft, parent);
	}
}
