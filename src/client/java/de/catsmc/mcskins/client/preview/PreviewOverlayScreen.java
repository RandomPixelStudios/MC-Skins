package de.catsmc.mcskins.client.preview;

import java.util.Locale;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.compat.InputCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

final class PreviewOverlayScreen extends Screen {
	private final PreviewSession session;
	private int dragButton = -1;

	PreviewOverlayScreen(PreviewSession session) {
		super(Component.translatable("mcskins.preview.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		dragButton = -1;
		addRenderableWidget(Button.builder(Component.translatable("mcskins.preview.back"), button -> session.exit())
			.bounds(8, 8, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("mcskins.preview.play"), button -> session.play())
			.bounds(112, 8, 84, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("mcskins.view.reset"), button -> session.resetCamera())
			.bounds(200, 8, 84, 20).build());
		PreviewSession.Animation[] animations = PreviewSession.Animation.values();
		addRenderableWidget(Button.builder(Component.translatable("mcskins.preview.animation",
			Component.translatable("mcskins.preview.animation." + session.animation().name().toLowerCase(Locale.ROOT))), button -> {
			session.animation(animations[(session.animation().ordinal() + 1) % animations.length]);
			rebuildWidgets();
		}).bounds(8, 32, 160, 20).build());
		if (session.recording()) {
			// Nothing but the world is drawn while a GIF is being recorded.
			clearWidgets();
			return;
		}
		int columnWidth = 150;
		int x = width - columnWidth - 8;
		String[] slots = {"head", "chest", "legs", "feet"};
		for (int slot = 0; slot < slots.length; slot++) {
			int index = slot;
			int material = session.armor(slot);
			String value = slot == 1 && material == PreviewSession.MATERIALS.length ? I18n.get("mcskins.preview.armor.elytra")
				: I18n.get("mcskins.preview.armor." + PreviewSession.MATERIALS[material]);
			addRenderableWidget(Button.builder(Component.literal(I18n.get("mcskins.preview.slot." + slots[slot], value)), button -> {
				session.cycleArmor(index);
				rebuildWidgets();
			}).bounds(x, 8 + slot * 22, columnWidth, 20).build());
		}
		addRenderableWidget(Button.builder(Component.literal(I18n.get("mcskins.preview.main_hand",
			I18n.get("mcskins.preview.item." + PreviewSession.HAND_ITEMS[session.mainHand()]))), button -> {
			session.cycleHand(true);
			rebuildWidgets();
		}).bounds(x, 8 + 4 * 22, columnWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal(I18n.get("mcskins.preview.off_hand",
			I18n.get("mcskins.preview.item." + PreviewSession.HAND_ITEMS[session.offHand()]))), button -> {
			session.cycleHand(false);
			rebuildWidgets();
		}).bounds(x, 8 + 5 * 22, columnWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal(I18n.get("mcskins.preview.environment",
			I18n.get("mcskins.preview.environment." + session.environment().name().toLowerCase(Locale.ROOT)))), button -> {
			session.cycleEnvironment();
			rebuildWidgets();
		}).bounds(x, 8 + 6 * 22, columnWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("mcskins.preview.record", PreviewSession.RECORD_SECONDS), button -> {
			session.startRecording();
			rebuildWidgets();
		}).bounds(x, 8 + 7 * 22, columnWidth, 20).build());
	}

	private boolean wasRecording;

	@Override
	public void tick() {
		if (wasRecording != session.recording()) {
			wasRecording = session.recording();
			rebuildWidgets();
		}
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
		if (session.recording()) {
			return;
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (!session.recordStatus().isEmpty()) {
			graphics.text(font, font.plainSubstrByWidth(session.recordStatus(), Math.max(0, width - 16)), 8, height - 54, 0xFFFFD38A, true);
		}
		line(graphics, I18n.get("mcskins.preview.line1"), 58);
		line(graphics, I18n.get("mcskins.preview.line2"), height - 42);
		line(graphics, I18n.get("mcskins.preview.line3"), height - 30);
		line(graphics, I18n.get("mcskins.preview.line4"), height - 18);
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
