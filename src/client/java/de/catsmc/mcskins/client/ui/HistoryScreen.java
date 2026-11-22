package de.catsmc.mcskins.client.ui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/** The undo history as a list of named steps; clicking a step undoes or redoes up to it. */
final class HistoryScreen extends SkinScreen {
	private static final int ROW = 14;
	private final SkinEditorScreen editor;
	private int scroll;
	private int listTop;
	private int listBottom;
	private int listLeft;
	private int listWidth;

	HistoryScreen(SkinEditorScreen editor) {
		super("mcskins.history.title", editor);
		this.editor = editor;
	}

	@Override
	protected void init() {
		listWidth = Math.min(260, width - 40);
		listLeft = (width - listWidth) / 2;
		listTop = 36;
		listBottom = height - 44;
		button(tr("mcskins.back"), listLeft, height - 38, listWidth, 20, this::onClose);
		// Start with the current step in view.
		int position = editor.document().historyPosition();
		int visible = (listBottom - listTop) / ROW;
		scroll = Math.max(0, position - visible / 2);
		clampScroll();
	}

	private void clampScroll() {
		int visible = (listBottom - listTop) / ROW;
		scroll = Math.clamp(scroll, 0, Math.max(0, editor.document().history().size() - visible));
	}

	static String label(String raw) {
		return switch (raw) {
			case "Opened" -> tr("mcskins.history.opened");
			case "Edit" -> tr("mcskins.history.edit");
			default -> raw;
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.x() >= listLeft && event.x() < listLeft + listWidth && event.y() >= listTop && event.y() < listBottom) {
			int index = scroll + (int) ((event.y() - listTop) / ROW);
			if (index < editor.document().history().size()) {
				editor.history(() -> editor.document().jumpTo(index));
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll -= (int) Math.signum(vertical) * 3;
		clampScroll();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		List<String> steps = editor.document().history();
		int position = editor.document().historyPosition();
		graphics.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x60000000);
		graphics.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
		for (int index = scroll; index < steps.size(); index++) {
			int y = listTop + (index - scroll) * ROW;
			if (y >= listBottom) {
				break;
			}
			boolean hovered = mouseX >= listLeft && mouseX < listLeft + listWidth && mouseY >= y && mouseY < y + ROW;
			if (index == position) {
				graphics.fill(listLeft, y, listLeft + listWidth, y + ROW, 0x50FFFFFF);
			} else if (hovered) {
				graphics.fill(listLeft, y, listLeft + listWidth, y + ROW, 0x30FFFFFF);
			}
			int color = index > position ? 0xFF7A8088 : 0xFFFFFFFF;
			text(graphics, (index + 1) + ". " + label(steps.get(index)), listLeft + 6, y + 3, color, listWidth - 12);
		}
		graphics.disableScissor();
		centered(graphics, tr("mcskins.history.hint"), width / 2, 24, MUTED, width - 20);
	}
}
