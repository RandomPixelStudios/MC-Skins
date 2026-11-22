package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.skin.PartCopy;
import de.catsmc.mcskins.skin.SkinGeometry;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Copies body parts onto each other, or base onto overlay and back, on the active layer. */
final class PartCopyScreen extends SkinScreen {
	private static SkinGeometry.Part from = SkinGeometry.Part.RIGHT_ARM;
	private static SkinGeometry.Part to = SkinGeometry.Part.LEFT_ARM;
	private static boolean mirror = true;
	private static PartCopy.Shells shells = PartCopy.Shells.BOTH;
	/** null copies the shells of every part. */
	private static SkinGeometry.Part shellPart;
	private final SkinEditorScreen editor;

	PartCopyScreen(SkinEditorScreen editor) {
		super("mcskins.parts.title", editor);
		this.editor = editor;
	}

	private static String part(SkinGeometry.Part part) {
		return part == null ? tr("mcskins.parts.all") : tr("mcskins.part." + part.name().toLowerCase(Locale.ROOT));
	}

	private static SkinGeometry.Part next(SkinGeometry.Part part) {
		var values = SkinGeometry.Part.values();
		return values[(part.ordinal() + 1) % values.length];
	}

	@Override
	protected void init() {
		int columnWidth = Math.min(150, (width - 40) / 2);
		int x = width / 2 - columnWidth - 2;
		int y = Math.max(40, height / 2 - 90);
		button(tr("mcskins.parts.from", part(from)), x, y, columnWidth, 20, () -> {
			from = next(from);
			rebuildWidgets();
		});
		button(tr("mcskins.parts.to", part(to)), x + columnWidth + 4, y, columnWidth, 20, () -> {
			to = next(to);
			rebuildWidgets();
		});
		selected(button(tr("mcskins.parts.mirror", tr(mirror ? "options.on" : "options.off")), tr("mcskins.parts.mirror.tip"),
			x, y + 24, columnWidth, 20, () -> {
				mirror = !mirror;
				rebuildWidgets();
			}), mirror);
		button(tr("mcskins.parts.shells." + shells.name().toLowerCase(Locale.ROOT)), x + columnWidth + 4, y + 24, columnWidth, 20, () -> {
			shells = PartCopy.Shells.values()[(shells.ordinal() + 1) % PartCopy.Shells.values().length];
			rebuildWidgets();
		});
		button(tr("mcskins.parts.copy"), tr("mcskins.parts.copy.tip"), x, y + 48, columnWidth * 2 + 4, 20, () -> {
			boolean slim = editor.document().slim();
			editor.changeCanvas("mcskins.history.part_copy", canvas -> PartCopy.copyPart(canvas, slim, from, to, mirror, shells));
			editor.status = tr("mcskins.status.part_copied", part(from), part(to));
			onClose();
		}).active = from != to || mirror;

		int shellY = y + 92;
		button(tr("mcskins.parts.shell_part", part(shellPart)), x, shellY, columnWidth * 2 + 4, 20, () -> {
			shellPart = shellPart == null ? SkinGeometry.Part.HEAD
				: shellPart.ordinal() == SkinGeometry.Part.values().length - 1 ? null : next(shellPart);
			rebuildWidgets();
		});
		button(tr("mcskins.parts.base_to_overlay"), tr("mcskins.parts.base_to_overlay.tip"), x, shellY + 24, columnWidth, 20, () -> shell(true));
		button(tr("mcskins.parts.overlay_to_base"), tr("mcskins.parts.overlay_to_base.tip"), x + columnWidth + 4, shellY + 24, columnWidth, 20, () -> shell(false));
		button(tr("mcskins.back"), x, shellY + 56, columnWidth * 2 + 4, 20, this::onClose);
	}

	private void shell(boolean toOverlay) {
		boolean slim = editor.document().slim();
		SkinGeometry.Part part = shellPart;
		editor.changeCanvas("mcskins.history.shell_copy", canvas -> PartCopy.copyShell(canvas, slim, part, toOverlay));
		editor.status = tr(toOverlay ? "mcskins.status.base_to_overlay" : "mcskins.status.overlay_to_base", part(part));
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int y = Math.max(40, height / 2 - 90);
		centered(graphics, tr("mcskins.parts.layer_hint", editor.document().layers().get(editor.document().activeLayer()).name()),
			width / 2, y - 12, MUTED, width - 20);
		centered(graphics, tr("mcskins.parts.shell_caption"), width / 2, y + 78, 0xFFFFFFFF, width - 20);
	}
}
