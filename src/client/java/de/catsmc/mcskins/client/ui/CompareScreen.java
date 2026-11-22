package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.InputCompat;
import de.catsmc.mcskins.skin.ModelRenderer;
import de.catsmc.mcskins.skin.SkinDocument;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/** Two skins side by side in 3D; dragging, zooming, poses and views apply to both at once. */
final class CompareScreen extends SkinScreen {
	private final SkinDocument left;
	private final SkinDocument right;
	private final SkinModelPreview leftPreview = new SkinModelPreview();
	private final SkinModelPreview rightPreview = new SkinModelPreview();
	private boolean dragging;
	private int top;
	private int boxWidth;
	private int boxHeight;

	CompareScreen(Screen parent, SkinDocument left, SkinDocument right) {
		super("mcskins.compare.title", parent);
		this.left = left;
		this.right = right;
		leftPreview.texture(left.flatten(), left.slim());
		rightPreview.texture(right.flatten(), right.slim());
	}

	@Override
	protected void init() {
		dragging = false;
		top = 54;
		boxWidth = (width - 30) / 2;
		boxHeight = Math.max(40, height - top - 40);
		leftPreview.viewport(10, top, boxWidth, boxHeight);
		rightPreview.viewport(20 + boxWidth, top, boxWidth, boxHeight);
		button(tr("mcskins.back"), 10, 26, 60, 20, this::onClose);
		ModelRenderer model = leftPreview.renderer();
		button(tr("mcskins.pose." + model.pose().name().toLowerCase(Locale.ROOT)), tr("mcskins.pose.tip"), 74, 26, 90, 20, () -> {
			var values = ModelRenderer.Pose.values();
			ModelRenderer.Pose next = values[(model.pose().ordinal() + 1) % values.length];
			both(renderer -> renderer.pose(next));
			rebuildWidgets();
		});
		button(tr("mcskins.animation." + model.animation().name().toLowerCase(Locale.ROOT)), tr("mcskins.animation.tip"), 168, 26, 90, 20, () -> {
			var values = ModelRenderer.Animation.values();
			ModelRenderer.Animation next = values[(model.animation().ordinal() + 1) % values.length];
			both(renderer -> renderer.animation(next));
			rebuildWidgets();
		});
		String[] views = {"front", "back", "right", "left"};
		int x = 262;
		for (int index = 0; index < views.length; index++) {
			int preset = index;
			button(tr("mcskins.view." + views[index] + ".short"), tr("mcskins.view." + views[index]), x, 26, 22, 20, () -> {
				leftPreview.view(preset);
				rightPreview.view(preset);
			});
			x += 24;
		}
		button(tr("mcskins.view.reset.short"), tr("mcskins.view.reset"), x, 26, 22, 20, () -> {
			leftPreview.reset();
			rightPreview.reset();
		});
	}

	private void both(java.util.function.Consumer<ModelRenderer> change) {
		change.accept(leftPreview.renderer());
		change.accept(rightPreview.renderer());
		leftPreview.invalidate();
		rightPreview.invalidate();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.y() >= top && event.y() < top + boxHeight && event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			dragging = true;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			leftPreview.orbit(dx, dy);
			rightPreview.orbit(dx, dy);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = dragging;
		dragging = false;
		return handled || super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		leftPreview.zoom(vertical);
		rightPreview.zoom(vertical);
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(10, top, 10 + boxWidth, top + boxHeight, 0x30000000);
		graphics.fill(20 + boxWidth, top, 20 + 2 * boxWidth, top + boxHeight, 0x30000000);
		graphics.enableScissor(10, top, 10 + boxWidth, top + boxHeight);
		leftPreview.render(graphics);
		graphics.disableScissor();
		graphics.enableScissor(20 + boxWidth, top, 20 + 2 * boxWidth, top + boxHeight);
		rightPreview.render(graphics);
		graphics.disableScissor();
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		centered(graphics, left.name() + " · " + tr(left.slim() ? "mcskins.slim" : "mcskins.classic"), 10 + boxWidth / 2, top + boxHeight + 4, 0xFFFFFFFF, boxWidth);
		centered(graphics, right.name() + " · " + tr(right.slim() ? "mcskins.slim" : "mcskins.classic"), 20 + boxWidth + boxWidth / 2, top + boxHeight + 4, 0xFFFFFFFF, boxWidth);
		text(graphics, tr("mcskins.compare.hint"), 10, top + boxHeight + 16, MUTED, width - 20);
	}

	@Override
	public void removed() {
		leftPreview.close();
		rightPreview.close();
		super.removed();
	}
}
