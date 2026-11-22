package de.catsmc.mcskins.client.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;

/** Loads a PNG as a see-through guide over the flat view; it is never part of the skin. */
final class ReferenceScreen extends SkinScreen {
	private static String lastPath = "";
	private final SkinEditorScreen editor;
	private EditBox path;

	ReferenceScreen(SkinEditorScreen editor) {
		super("mcskins.reference.title", editor);
		this.editor = editor;
	}

	@Override
	protected void init() {
		int fieldWidth = Math.min(300, width - 40);
		int x = (width - fieldWidth) / 2;
		int y = height / 2 - 50;
		path = field(tr("mcskins.reference.path"), lastPath, x, y, fieldWidth - 64, 1024);
		path.setResponder(value -> lastPath = value);
		button(tr("mcskins.reference.load"), x + fieldWidth - 60, y, 60, 20, () -> load(Path.of(stripQuotes(path.getValue()))));
		int half = fieldWidth / 2 - 2;
		boolean loaded = editor.hasReference();
		selected(button(tr(editor.referenceShown() ? "mcskins.reference.shown" : "mcskins.reference.hidden"), x, y + 28, half, 20, () -> {
			editor.referenceShown(!editor.referenceShown());
			rebuildWidgets();
		}), loaded && editor.referenceShown()).active = loaded;
		button(tr("mcskins.reference.clear"), x + half + 4, y + 28, half, 20, () -> {
			editor.clearReference();
			rebuildWidgets();
		}).active = loaded;
		button("−", tr("mcskins.reference.opacity.tip"), x, y + 52, 20, 20, () -> {
			editor.referenceOpacity(editor.referenceOpacity() - 32);
			rebuildWidgets();
		}).active = loaded;
		button("+", tr("mcskins.reference.opacity.tip"), x + fieldWidth - 20, y + 52, 20, 20, () -> {
			editor.referenceOpacity(editor.referenceOpacity() + 32);
			rebuildWidgets();
		}).active = loaded;
		button(tr("mcskins.back"), x, y + 84, fieldWidth, 20, this::onClose);
	}

	private static String stripQuotes(String value) {
		String text = value.strip();
		if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) {
			text = text.substring(1, text.length() - 1);
		}
		return text;
	}

	private void load(Path file) {
		if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
			status = tr("mcskins.status.drop_png");
			return;
		}
		status = tr("mcskins.reference.loading");
		editor.loadReference(file, error -> {
			if (error == null) {
				status = tr("mcskins.reference.loaded", file.getFileName());
			} else {
				status = tr("mcskins.error", error);
			}
			rebuildWidgets();
		});
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (!paths.isEmpty()) {
			lastPath = paths.getFirst().toString();
			load(paths.getFirst());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int y = height / 2 - 50;
		centered(graphics, tr("mcskins.reference.hint"), width / 2, y - 14, MUTED, width - 20);
		centered(graphics, tr("mcskins.reference.opacity", Math.round(editor.referenceOpacity() * 100 / 255.0)), width / 2, y + 58, 0xFFFFFFFF, 200);
	}
}
