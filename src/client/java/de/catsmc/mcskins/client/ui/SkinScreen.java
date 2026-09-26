package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.GameCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class SkinScreen extends Screen {
	protected final Screen parent;
	protected String status = "";

	SkinScreen(String title, Screen parent) {
		super(Component.literal(title));
		this.parent = parent;
	}

	protected Button button(String label, int x, int y, int width, Runnable action) {
		return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, width, 20).build());
	}

	protected EditBox field(String label, String value, int x, int y, int width, int maxLength) {
		EditBox field = new EditBox(font, x, y, width, 20, Component.literal(label));
		field.setMaxLength(maxLength);
		field.setValue(value);
		return addRenderableWidget(field);
	}

	protected void failure(Exception exception) {
		String message = exception.getMessage();
		status = "Error: " + (message == null ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' '));
	}

	protected void text(GuiGraphicsExtractor graphics, String text, int x, int y, int color, int maxWidth) {
		graphics.text(font, font.plainSubstrByWidth(text, Math.max(0, maxWidth)), x, y, color, false);
	}

	protected boolean showTitle() {
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractBackground(graphics, mouseX, mouseY, delta);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (showTitle()) {
			String heading = font.plainSubstrByWidth(title.getString(), width - 20);
			graphics.text(font, heading, (width - font.width(heading)) / 2, 12, 0xFFFFFFFF, false);
		}
		text(graphics, status, 8, height - 13, 0xFFFFD38A, width - 16);
	}

	@Override
	public void onClose() {
		GameCompat.setScreen(minecraft, parent);
	}
}
