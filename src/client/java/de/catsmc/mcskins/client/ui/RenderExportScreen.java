package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.ModelRenderer;
import de.catsmc.mcskins.skin.PngCodec;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Renders the current 3D pose and camera as a high-resolution PNG with a transparent background. */
final class RenderExportScreen extends SkinScreen {
	private static final int[] SIZES = {512, 1024, 2048};
	private final SkinEditorScreen editor;
	private boolean busy;

	RenderExportScreen(SkinEditorScreen editor) {
		super("mcskins.render.title", editor);
		this.editor = editor;
	}

	@Override
	protected void init() {
		int buttonWidth = Math.min(240, width - 40);
		int x = (width - buttonWidth) / 2;
		int y = height / 2 - 30;
		for (int index = 0; index < SIZES.length; index++) {
			int size = SIZES[index];
			button(tr("mcskins.render.size", size, size), x, y + index * 24, buttonWidth, 20, () -> render(size)).active = !busy;
		}
		button(tr("mcskins.render.folder"), x, y + 76, buttonWidth, 20, () -> {
			try {
				GameCompat.openPath(editor.repository().outputDirectory("renders"));
			} catch (IOException exception) {
				failure(exception);
			}
		});
		button(tr("mcskins.back"), x, y + 100, buttonWidth, 20, this::onClose);
	}

	private void render(int size) {
		editor.refresh();
		ModelRenderer copy = editor.renderer().copyView();
		String name = editor.document().name();
		busy = true;
		status = tr("mcskins.render.rendering");
		rebuildWidgets();
		CompletableFuture.supplyAsync(() -> {
			try {
				int[] image = copy.renderImage(size);
				return editor.repository().writeOutput("renders", name, "png", PngCodec.encode(image, size, size));
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((path, error) -> minecraft.execute(() -> {
			busy = false;
			if (error == null) {
				status = tr("mcskins.render.saved", path.getFileName());
				editor.status = status;
			} else {
				Throwable cause = error.getCause() == null ? error : error.getCause();
				failure(cause instanceof Exception exception ? exception : new IOException(cause));
			}
			rebuildWidgets();
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		centered(graphics, tr("mcskins.render.hint"), width / 2, height / 2 - 50, MUTED, width - 20);
	}
}
