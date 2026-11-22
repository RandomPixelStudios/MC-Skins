package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.skin.SkinCheck;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Reports pixels the game shows differently and offers to highlight or fix them. */
final class SkinCheckScreen extends SkinScreen {
	private final SkinEditorScreen editor;
	private SkinCheck.Report report;

	SkinCheckScreen(SkinEditorScreen editor) {
		super("mcskins.check.title", editor);
		this.editor = editor;
	}

	@Override
	protected void init() {
		SkinDocument document = editor.document();
		report = SkinCheck.check(document.flatten(), document.slim());
		int buttonWidth = Math.min(260, width - 40);
		int x = (width - buttonWidth) / 2;
		int y = height / 2 + 4;
		boolean baseProblems = !report.transparentBase().isEmpty() || !report.translucentBase().isEmpty();
		button(tr("mcskins.check.show"), tr("mcskins.check.show.tip"), x, y, buttonWidth, 20, () -> {
			editor.showProblems(report.all());
			onClose();
		}).active = !report.clean();
		button(tr("mcskins.check.fix_base"), tr("mcskins.check.fix_base.tip"), x, y + 24, buttonWidth, 20, () -> {
			int[] fix = SkinCheck.baseFixLayer(document.flatten(), document.slim(), report);
			editor.change("mcskins.history.skin_fix", () -> document.insertLayer(new SkinLayer(tr("mcskins.check.fix_layer"), fix), 0));
			editor.showProblems(null);
			rebuildWidgets();
		}).active = baseProblems && document.layers().size() < SkinDocument.MAX_LAYERS;
		button(tr("mcskins.check.fix_outside"), tr("mcskins.check.fix_outside.tip"), x, y + 48, buttonWidth, 20, () -> {
			editor.change("mcskins.history.skin_fix", () -> document.clearEverywhere(report.outsideUv()));
			editor.showProblems(null);
			rebuildWidgets();
		}).active = !report.outsideUv().isEmpty();
		button(tr("mcskins.back"), x, y + 76, buttonWidth, 20, this::onClose);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int y = height / 2 - 60;
		if (report.clean()) {
			centered(graphics, tr("mcskins.check.clean"), width / 2, y + 20, SUCCESS, width - 20);
			return;
		}
		centered(graphics, tr("mcskins.check.transparent", report.transparentBase().cardinality()), width / 2, y, 0xFFFFFFFF, width - 20);
		centered(graphics, tr("mcskins.check.translucent", report.translucentBase().cardinality()), width / 2, y + 14, 0xFFFFFFFF, width - 20);
		centered(graphics, tr("mcskins.check.outside", report.outsideUv().cardinality()), width / 2, y + 28, 0xFFFFFFFF, width - 20);
		centered(graphics, tr("mcskins.check.explain"), width / 2, y + 46, MUTED, width - 20);
	}
}
