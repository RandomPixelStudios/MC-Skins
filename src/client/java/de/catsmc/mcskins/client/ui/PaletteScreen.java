package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.skin.ColorOps;
import de.catsmc.mcskins.skin.PaletteStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;

/** Extracts a palette from the skin and saves, loads and deletes named palettes (config/mcskins/palettes/). */
final class PaletteScreen extends SkinScreen {
	private static final int ROWS = 5;
	private static String draftName = "";
	private final SkinEditorScreen editor;
	private final PaletteStore store = new PaletteStore(McSkinsClient.repository().root());
	private List<PaletteStore.Palette> saved = List.of();
	private boolean loading;
	private int page;
	private int left;
	private int panelWidth;
	private String armedDelete;

	PaletteScreen(SkinEditorScreen editor) {
		super("mcskins.palettes.title", editor);
		this.editor = editor;
		reload();
	}

	private void reload() {
		loading = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				return store.list();
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((list, error) -> minecraft().execute(() -> {
			loading = false;
			if (error == null) {
				saved = list;
			} else {
				status = tr("mcskins.error", String.valueOf(error.getCause() == null ? error : error.getCause().getMessage()));
			}
			if (minecraft != null) {
				rebuildWidgets();
			}
		}));
	}

	private static net.minecraft.client.Minecraft minecraft() {
		return net.minecraft.client.Minecraft.getInstance();
	}

	private List<Integer> current() {
		return McSkinsClient.settings().palette();
	}

	@Override
	protected void init() {
		panelWidth = Math.min(300, width - 40);
		left = (width - panelWidth) / 2;
		int y = 52;
		int third = (panelWidth - 8) / 3;
		button(tr("mcskins.palettes.extract"), tr("mcskins.palettes.extract.tip"), left, y, third, 20, () -> {
			List<Integer> colors = ColorOps.sortForDisplay(ColorOps.extractPalette(editor.document().flatten(), 48, 6));
			if (colors.isEmpty()) {
				status = tr("mcskins.palettes.empty_skin");
				return;
			}
			editor.usePalette(colors);
			status = tr("mcskins.palettes.extracted", colors.size());
		});
		button(tr("mcskins.palettes.add_color"), tr("mcskins.palettes.add_color.tip"), left + third + 4, y, third, 20, () -> {
			var colors = new ArrayList<>(current());
			if (!colors.contains(editor.currentColor()) && colors.size() < PaletteStore.MAX_COLORS) {
				colors.add(editor.currentColor());
				editor.usePalette(colors);
			}
		});
		button(tr("mcskins.palettes.default"), tr("mcskins.palettes.default.tip"), left + 2 * third + 8, y, panelWidth - 2 * third - 8, 20,
			() -> editor.usePalette(List.of()));
		EditBox name = field(tr("mcskins.palettes.name"), draftName, left, y + 54, panelWidth - 84, 64);
		name.setResponder(value -> draftName = value);
		button(tr("mcskins.palettes.save"), left + panelWidth - 80, y + 54, 80, 20, () -> save(draftName));
		int listY = y + 82;
		int pages = Math.max(1, (saved.size() + ROWS - 1) / ROWS);
		page = Math.clamp(page, 0, pages - 1);
		for (int row = 0; row < ROWS; row++) {
			int index = page * ROWS + row;
			if (index >= saved.size()) {
				break;
			}
			PaletteStore.Palette palette = saved.get(index);
			int rowY = listY + row * 22;
			button(tr("mcskins.palettes.load"), left + panelWidth - 88, rowY, 50, 20, () -> {
				editor.usePalette(palette.colors());
				draftName = palette.name();
				status = tr("mcskins.palettes.loaded", palette.name());
				rebuildWidgets();
			});
			boolean armed = palette.name().equals(armedDelete);
			button(armed ? "?" : "✕", tr(armed ? "mcskins.palettes.delete.confirm" : "mcskins.palettes.delete"), left + panelWidth - 34, rowY, 34, 20,
				() -> delete(palette.name()));
		}
		int bottom = listY + ROWS * 22 + 4;
		button("‹", left, bottom, 20, 20, () -> {
			page--;
			rebuildWidgets();
		}).active = page > 0;
		button("›", left + 24, bottom, 20, 20, () -> {
			page++;
			rebuildWidgets();
		}).active = page < pages - 1;
		button(tr("mcskins.back"), left + panelWidth - 100, bottom, 100, 20, this::onClose);
	}

	private void save(String value) {
		List<Integer> colors = current();
		if (colors.isEmpty()) {
			status = tr("mcskins.palettes.nothing_to_save");
			return;
		}
		PaletteStore.Palette palette;
		try {
			palette = new PaletteStore.Palette(value, colors);
		} catch (IllegalArgumentException exception) {
			status = tr("mcskins.status.enter_name");
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				store.save(palette);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((ignored, error) -> minecraft().execute(() -> {
			status = error == null ? tr("mcskins.palettes.saved", palette.name())
				: tr("mcskins.error", error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
			reload();
		}));
	}

	private void delete(String name) {
		if (!name.equals(armedDelete)) {
			armedDelete = name;
			rebuildWidgets();
			return;
		}
		armedDelete = null;
		CompletableFuture.runAsync(() -> {
			try {
				store.delete(name);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((ignored, error) -> minecraft().execute(() -> {
			status = error == null ? tr("mcskins.palettes.deleted", name) : tr("mcskins.error", String.valueOf(error.getMessage()));
			reload();
		}));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		// Clicking a swatch of the current palette removes it.
		int index = swatchAt(event.x(), event.y());
		if (index >= 0) {
			var colors = new ArrayList<>(current());
			colors.remove(index);
			editor.usePalette(colors);
			return true;
		}
		return false;
	}

	private int swatchAt(double x, double y) {
		int top = 76;
		if (x < left || y < top || y >= top + 24) {
			return -1;
		}
		int perRow = Math.max(1, panelWidth / 10);
		int index = (int) ((y - top) / 12) * perRow + (int) ((x - left) / 10);
		return (x - left) / 10 < perRow && index < current().size() ? index : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		List<Integer> colors = current();
		text(graphics, tr(colors.isEmpty() ? "mcskins.palettes.using_default" : "mcskins.palettes.current", colors.size()), left, 40, MUTED, panelWidth);
		int perRow = Math.max(1, panelWidth / 10);
		for (int index = 0; index < colors.size(); index++) {
			int x = left + index % perRow * 10;
			int y = 76 + index / perRow * 12;
			if (y < 76 + 24) {
				graphics.fill(x, y, x + 9, y + 11, colors.get(index) | 0xFF000000);
			}
		}
		int listY = 52 + 82;
		if (loading) {
			text(graphics, tr("mcskins.loading"), left, listY + 6, MUTED, panelWidth);
		} else if (saved.isEmpty()) {
			text(graphics, tr("mcskins.palettes.none_saved"), left, listY + 6, MUTED, panelWidth);
		}
		for (int row = 0; row < ROWS; row++) {
			int index = page * ROWS + row;
			if (index >= saved.size()) {
				break;
			}
			PaletteStore.Palette palette = saved.get(index);
			int y = listY + row * 22;
			text(graphics, palette.name(), left, y + 2, 0xFFFFFFFF, panelWidth - 96);
			int count = Math.min(palette.colors().size(), (panelWidth - 96) / 6);
			for (int color = 0; color < count; color++) {
				graphics.fill(left + color * 6, y + 12, left + color * 6 + 5, y + 18, palette.colors().get(color) | 0xFF000000);
			}
		}
	}
}
