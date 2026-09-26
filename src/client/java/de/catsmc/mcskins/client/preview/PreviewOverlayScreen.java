package de.catsmc.mcskins.client.preview;

import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.compat.InputCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class PreviewOverlayScreen extends Screen {
	private final PreviewSession session;
	private int dragButton = -1;

	PreviewOverlayScreen(PreviewSession session) {
		super(Component.literal("Temporary skin test world"));
		this.session = session;
	}

	@Override
	protected void init() {
		dragButton = -1;
		addRenderableWidget(Button.builder(Component.literal("Back to Edit"), button -> session.exit())
			.bounds(8, 8, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Play skin"), button -> session.play())
			.bounds(112, 8, 84, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset view"), button -> session.resetCamera())
			.bounds(200, 8, 84, 20).build());
		PreviewSession.Animation[] animations = PreviewSession.Animation.values();
		addRenderableWidget(Button.builder(Component.literal("Animation: " + session.animation()), button -> {
			session.animation(animations[(session.animation().ordinal() + 1) % animations.length]);
			rebuildWidgets();
		}).bounds(8, 32, 160, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		line(graphics, "Temporary local world; account skin unchanged", 58);
		line(graphics, "Drag: orbit | Right drag: pan | Wheel: zoom", height - 42);
		line(graphics, "1-8: animation | R: reset | Esc pauses (Back to Editor there)", height - 30);
		line(graphics, "Play skin walks freely; Esc: Back to Edit and delete this world", height - 18);
	}

	private void line(GuiGraphicsExtractor graphics, String text, int y) {
		graphics.text(font, font.plainSubstrByWidth(text, Math.max(0, width - 16)), 8, y, 0xFFFFFFFF, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.y() > 72 && event.y() < height - 48
			&& (event.button() == InputCompat.MOUSE_BUTTON_LEFT
				|| event.button() == InputCompat.MOUSE_BUTTON_MIDDLE
				|| event.button() == InputCompat.MOUSE_BUTTON_RIGHT)) {
			clearFocus();
			dragButton = event.button();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragButton == event.button()) {
			if (dragButton == InputCompat.MOUSE_BUTTON_RIGHT) {
				session.pan(dx, dy);
			} else {
				session.orbit(dx, dy);
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragButton == event.button()) {
			dragButton = -1;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		session.zoom(vertical);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		PreviewSession.Animation[] animations = PreviewSession.Animation.values();
		if (event.key() >= InputCompat.KEY_1 && event.key() <= InputCompat.KEY_8
			&& event.key() - InputCompat.KEY_1 < animations.length) {
			session.animation(animations[event.key() - InputCompat.KEY_1]);
			rebuildWidgets();
			return true;
		}
		int key = event.key();
		if (key == InputCompat.KEY_ESCAPE) {
			pauseGame();
		} else if (key == InputCompat.KEY_EQUALS || key == InputCompat.KEY_ADD) {
			session.zoom(1);
		} else if (key == InputCompat.KEY_MINUS) {
			session.zoom(-1);
		} else if (key == InputCompat.KEY_R) {
			session.resetCamera();
		} else {
			return super.keyPressed(event);
		}
		return true;
	}

	@Override
	public void onClose() {
		pauseGame();
	}

	private void pauseGame() {
		GameCompat.setScreen(Minecraft.getInstance(), new PauseScreen(true));
	}
}
