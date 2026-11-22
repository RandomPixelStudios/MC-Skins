package de.catsmc.mcskins.client.preview;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

final class PreviewFailureScreen extends Screen {
	private final PreviewSession session;
	private final boolean fallback;
	private Button keepEditing;

	PreviewFailureScreen(PreviewSession session, boolean fallback) {
		super(Component.translatable("mcskins.preview.recovery"));
		this.session = session;
		this.fallback = fallback;
	}

	@Override
	protected void init() {
		if (fallback) {
			session.fallbackShown();
		}
		addRenderableWidget(Button.builder(Component.translatable("mcskins.preview.safe_exit"), button -> session.exit())
			.bounds((width - 220) / 2, height - 64, 220, 20).build());
		keepEditing = addRenderableWidget(Button.builder(Component.translatable("mcskins.preview.keep_editing"), button -> session.backWithoutCleanup())
			.bounds((width - 220) / 2, height - 40, 220, 20).build());
		keepEditing.active = session.stopped();
	}

	@Override
	public void tick() {
		keepEditing.active = session.stopped();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int y = 20;
		for (String text : new String[]{title.getString(), session.problem(), session.folder(),
			I18n.get("mcskins.preview.recovery.line1"), I18n.get("mcskins.preview.recovery.line2")}) {
			for (var line : font.split(Component.literal(text), Math.max(1, width - 32))) {
				graphics.text(font, line, 16, y, 0xFFFFFFFF, true);
				y += 12;
			}
			y += 6;
		}
	}

	@Override
	public void onClose() {
		session.exit();
	}
}
