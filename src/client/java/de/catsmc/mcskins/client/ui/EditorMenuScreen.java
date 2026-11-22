package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.GameCompat;

/** The editor's "☰" menu with the less frequent tools; every entry returns to the editor when done. */
final class EditorMenuScreen extends SkinScreen {
	private final SkinEditorScreen editor;

	EditorMenuScreen(SkinEditorScreen editor) {
		super("mcskins.menu.title", editor);
		this.editor = editor;
	}

	@Override
	protected void init() {
		int buttonWidth = Math.min(220, width - 40);
		int x = (width - buttonWidth) / 2;
		int y = Math.max(32, height / 2 - 100);
		button(tr("mcskins.menu.parts"), tr("mcskins.menu.parts.tip"), x, y, buttonWidth, 20, () -> go(new PartCopyScreen(editor)));
		button(tr("mcskins.menu.check"), tr("mcskins.menu.check.tip"), x, y + 24, buttonWidth, 20, () -> go(new SkinCheckScreen(editor)));
		button(tr("mcskins.menu.reference"), tr("mcskins.menu.reference.tip"), x, y + 48, buttonWidth, 20, () -> go(new ReferenceScreen(editor)));
		button(tr("mcskins.menu.render"), tr("mcskins.menu.render.tip"), x, y + 72, buttonWidth, 20, () -> go(new RenderExportScreen(editor)));
		button(tr("mcskins.menu.palettes"), tr("mcskins.menu.palettes.tip"), x, y + 96, buttonWidth, 20, () -> go(new PaletteScreen(editor)));
		button(tr("mcskins.menu.history"), tr("mcskins.menu.history.tip"), x, y + 120, buttonWidth, 20, () -> go(new HistoryScreen(editor)));
		button(tr("mcskins.menu.paste_image"), tr("mcskins.menu.paste_image.tip"), x, y + 144, buttonWidth, 20, () -> {
			onClose();
			editor.pasteSystemImage();
		});
		button(tr("mcskins.back"), x, y + 176, buttonWidth, 20, this::onClose);
	}

	private void go(SkinScreen screen) {
		GameCompat.setScreen(minecraft, screen);
	}
}
