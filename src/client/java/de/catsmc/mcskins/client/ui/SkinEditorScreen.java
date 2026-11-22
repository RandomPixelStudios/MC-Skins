package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.ClientSettings;
import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.compat.InputCompat;
import de.catsmc.mcskins.client.preview.PreviewSession;
import de.catsmc.mcskins.skin.BlendMode;
import de.catsmc.mcskins.skin.Clip;
import de.catsmc.mcskins.skin.ColorOps;
import de.catsmc.mcskins.skin.LayerGroup;
import de.catsmc.mcskins.skin.ModelRenderer;
import de.catsmc.mcskins.skin.PngCodec;
import de.catsmc.mcskins.skin.SafeFiles;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinGeometry;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import de.catsmc.mcskins.skin.SkinTools;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

final class SkinEditorScreen extends SkinScreen {
	private enum Tool {
		BRUSH("B", "mcskins.tool.brush", InputCompat.KEY_B),
		ERASER("E", "mcskins.tool.eraser", InputCompat.KEY_E),
		PICKER("I", "mcskins.tool.picker", InputCompat.KEY_I),
		FILL("F", "mcskins.tool.fill", InputCompat.KEY_F),
		LINE("L", "mcskins.tool.line", InputCompat.KEY_L),
		RECTANGLE("□", "mcskins.tool.rectangle", InputCompat.KEY_U),
		FILLED_RECTANGLE("■", "mcskins.tool.filled_rectangle", -1),
		LIGHTEN("+", "mcskins.tool.lighten", InputCompat.KEY_D),
		DARKEN("−", "mcskins.tool.darken", InputCompat.KEY_S),
		NOISE("N", "mcskins.tool.noise", InputCompat.KEY_N),
		SELECT("⬚", "mcskins.tool.select", InputCompat.KEY_Q),
		WAND("✦", "mcskins.tool.wand", InputCompat.KEY_W),
		GRADIENT("▤", "mcskins.tool.gradient", InputCompat.KEY_J),
		SHADE("◐", "mcskins.tool.shade", InputCompat.KEY_H),
		REPLACE("≈", "mcskins.tool.replace", InputCompat.KEY_K);

		final String label;
		final String key;
		final int shortcut;

		Tool(String label, String key, int shortcut) {
			this.label = label;
			this.key = key;
			this.shortcut = shortcut;
		}

		boolean shape() {
			return this == LINE || this == RECTANGLE || this == FILLED_RECTANGLE || this == GRADIENT;
		}

		boolean sized() {
			return this != PICKER && this != FILL && this != SELECT && this != WAND && this != GRADIENT && this != REPLACE;
		}

		boolean selecting() {
			return this == SELECT || this == WAND;
		}
	}

	private static final int[] PALETTE = {
		0xFF000000, 0xFF3C3C3C, 0xFF6E6E6E, 0xFF9E9E9E, 0xFFCFCFCF, 0xFFFFFFFF, 0xFF5C3A21, 0xFF8B5A2B,
		0xFFB98868, 0xFFE0B48A, 0xFFF3D2B3, 0xFF6B4226, 0xFF7F0000, 0xFFC00000, 0xFFFF4040, 0xFFFF9090,
		0xFF804000, 0xFFFF8000, 0xFFFFB060, 0xFFFFD8A0, 0xFF807000, 0xFFFFE000, 0xFFFFF080, 0xFFFFFFC0,
		0xFF2F5A1E, 0xFF4CA02A, 0xFF8AD65A, 0xFFC8F0A8, 0xFF0F4F4F, 0xFF20A0A0, 0xFF60E0E0, 0xFFB0FFFF,
		0xFF102060, 0xFF2050C0, 0xFF5090FF, 0xFFA8C8FF, 0xFF3A1060, 0xFF7030C0, 0xFFA870FF, 0xFFD8B8FF,
		0xFF600030, 0xFFC02070, 0xFFFF70B0, 0xFFFFC0E0, 0xFF3B2A1A, 0xFF59473A, 0xFF7D6E62, 0xFFA59C94};
	private static final int LAYER_ROW = 18;
	private static final int SELECTION_TINT = 0x6040A0FF;
	private static final int PROBLEM_TINT = 0xB0FF2020;

	private final SkinRepository repository;
	private final SkinDocument document;
	private final ClientSettings settings = McSkinsClient.settings();
	private final SkinModelPreview preview = new SkinModelPreview();
	private final PixelTexture flatTexture = new PixelTexture("MC-Skins flat canvas");
	private final PixelTexture svTexture = new PixelTexture("MC-Skins color field");
	private final PixelTexture hueTexture = new PixelTexture("MC-Skins hue bar");
	private final PixelTexture referenceTexture = new PixelTexture("MC-Skins reference image");
	private final int[] flatPixels = new int[4096];
	private float fieldHue = Float.NaN;
	private float fieldValue = Float.NaN;
	private boolean fieldWheel;
	private int fieldCells;
	private int barSize;
	private float barHue = Float.NaN;
	private float barSaturation = Float.NaN;
	private boolean barWheel;
	private final SplittableRandom random = new SplittableRandom();
	private final BitSet strokeTouched = new BitSet(4096);
	private Tool tool = Tool.BRUSH;
	private int color = 0xFFB98868;
	private float hue;
	private float saturation;
	private float value;
	private int previousColor = 0xFFB98868;
	private boolean flat;
	private boolean overlayTarget;
	private int[] pixels;
	private int leftX;
	private int rightX;
	private int viewX;
	private int viewY;
	private int viewWidth = 1;
	private int viewHeight = 1;
	private int toolbarBottom;
	private int svSize = 96;
	private int layerTop;
	private int sizeRowY;
	private int optionRowY;
	private int layerBottom;
	private int paletteTop;
	private double flatZoom = 1;
	private double flatPanX;
	private double flatPanY;
	private int cameraButton = -1;
	private boolean painting;
	private int shapeStart = -1;
	private int shapeEnd = -1;
	private int lastPixel = -1;
	private double lastMouseX;
	private double lastMouseY;
	private int hoverPixel = -1;
	private boolean svDrag;
	private boolean hueDrag;
	private boolean alphaDrag;
	private boolean opacityDrag;
	private int scrollLayers;
	private int scrollPalette;
	private long lastAutosave = System.nanoTime();
	private long lastLayerClick;
	private int lastLayerClicked = -1;
	private CompletableFuture<?> saving;
	private CompletableFuture<?> importing;
	private boolean closing;
	private boolean textureDirty;
	private int[] swatchCache;
	private int swatchRecentCount;
	private int footprintPixel = -2;
	private Tool footprintTool;
	private int footprintBrush;
	private boolean footprintMirror;
	private boolean footprintSlim;
	private BitSet footprintCache = new BitSet();
	private boolean syncFields;
	private EditBox hexBox;
	private boolean shadeLighten = true;

	// ---- selection state: the selection mask, a floating (lifted or pasted) clip and the internal clipboard ----
	private BitSet selection;
	private Clip floating;
	private int[] floatingBase;
	private String floatingLabel;
	private Clip moveStartClip;
	private int moveAnchor = -1;
	private int selectStart = -1;
	private int selectEnd = -1;
	private boolean selectAdd;
	private static Clip clipboard;

	// ---- overlays: reference image and skin check problems ----
	private int[] reference;
	private int referenceOpacity = 128;
	private boolean referenceShown = true;
	private boolean referenceDirty;
	private BitSet problems;

	SkinEditorScreen(Screen parent, SkinRepository repository, SkinDocument document) {
		super("mcskins.editor.title", parent);
		this.repository = repository;
		this.document = document;
		List<Integer> recent = settings.recentColors();
		if (!recent.isEmpty()) {
			color = recent.getFirst();
			previousColor = color;
		}
		updateHsv();
		preview.renderer().editing(true);
		preview.renderer().grid(settings.grid());
		refresh();
	}

	@Override
	protected boolean showTitle() {
		return false;
	}

	SkinDocument document() {
		return document;
	}

	SkinRepository repository() {
		return repository;
	}

	ModelRenderer renderer() {
		return preview.renderer();
	}

	@Override
	protected void init() {
		endInteraction();
		refresh();
		leftX = 4;
		rightX = width - 156;
		viewX = leftX + 116;
		viewY = 26;
		int viewRight = rightX - 4;
		svSize = (int) Math.clamp((height - 120) * 0.45, 48, 112);

		int x = 4;
		button(tr("mcskins.back"), x, 4, 40, 18, this::onClose);
		button(tr("mcskins.save"), tr("mcskins.save.tip"), x + 42, 4, 40, 18, this::saveAsync);
		button("←", tr("mcskins.undo.tip"), x + 86, 4, 18, 18, () -> history(document::undo)).active = document.canUndo() || floating != null;
		button("→", tr("mcskins.redo.tip"), x + 106, 4, 18, 18, () -> history(document::redo)).active = document.canRedo();
		int right = width - 4;
		right -= 52;
		button(tr("mcskins.folder"), tr("mcskins.folder.tip"), right, 4, 52, 18, this::openExports);
		right -= 58;
		button(tr("mcskins.export"), tr("mcskins.export.tip"), right, 4, 56, 18, this::export);
		right -= 70;
		button(tr("mcskins.test_world"), tr("mcskins.test_world.tip"), right, 4, 68, 18, () -> {
			commitFloating();
			endInteraction();
			PreviewSession.start(this, document, message -> status = message);
		}).active = PreviewSession.canStart();
		right -= 20;
		button("☰", tr("mcskins.menu.tip"), right, 4, 18, 18, () -> open(new EditorMenuScreen(this)));

		int toolY = 40;
		Tool[] tools = Tool.values();
		for (int index = 0; index < tools.length; index++) {
			Tool choice = tools[index];
			String tip = tr(choice.key) + (choice.shortcut >= 0 ? " [" + InputCompat.keyName(choice.shortcut) + "]" : "");
			selected(button(choice.label, tip, leftX + 2 + index % 5 * 22, toolY + index / 5 * 20, 21, 18, () -> select(choice)), tool == choice);
		}
		int sizeY = toolY + 62;
		sizeRowY = sizeY;
		button("−", tr("mcskins.brush.smaller"), leftX + 2, sizeY, 18, 16, () -> brush(-1)).active = settings.brushSize() > 1;
		button("+", tr("mcskins.brush.larger"), leftX + 92, sizeY, 18, 16, () -> brush(1)).active = settings.brushSize() < 8;
		int toggleY = sizeY + 20;
		selected(button(tr("mcskins.mirror"), tr("mcskins.mirror.tip"), leftX + 2, toggleY, 53, 16, () -> {
			settings.mirror(!settings.mirror());
			rebuildWidgets();
		}), settings.mirror());
		selected(button(tr("mcskins.grid"), tr("mcskins.grid.tip"), leftX + 57, toggleY, 53, 16, this::toggleGrid), settings.grid());
		optionRowY = toggleY + 18;
		toolOptions(optionRowY);

		layerTop = optionRowY + 20;
		layerBottom = height - 74;
		int layerButtons = layerBottom + 4;
		var layers = document.layers();
		SkinLayer active = layers.get(document.activeLayer());
		button("+", tr("mcskins.layer.add"), leftX + 2, layerButtons, 17, 16,
			() -> change("mcskins.history.add_layer", () -> document.addLayer(tr("mcskins.layer.default_name", layers.size() + 1)))).active = layers.size() < SkinDocument.MAX_LAYERS;
		button("⧉", tr("mcskins.layer.duplicate"), leftX + 20, layerButtons, 17, 16, () -> change("mcskins.history.duplicate_layer",
			() -> document.duplicateLayer(tr("mcskins.layer.copy_suffix")))).active = layers.size() < SkinDocument.MAX_LAYERS;
		button("▲", tr("mcskins.layer.up"), leftX + 38, layerButtons, 17, 16, () -> change("mcskins.history.move_layer", () -> document.moveLayer(1))).active = document.activeLayer() < layers.size() - 1;
		button("▼", tr("mcskins.layer.down"), leftX + 56, layerButtons, 17, 16, () -> change("mcskins.history.move_layer", () -> document.moveLayer(-1))).active = document.activeLayer() > 0;
		button("↓", tr(document.activeLayer() > 0 && !document.canMergeDown() ? "mcskins.layer.merge.hidden" : "mcskins.layer.merge"),
			leftX + 74, layerButtons, 17, 16, () -> change("mcskins.history.merge", document::mergeDown)).active = document.canMergeDown();
		button("✕", tr("mcskins.layer.delete"), leftX + 92, layerButtons, 18, 16, () -> change("mcskins.history.delete_layer", document::removeLayer)).active = layers.size() > 1;
		button(tr("mcskins.layer.rename"), tr("mcskins.layer.rename.tip"), leftX + 2, layerButtons + 18, 53, 14, this::renameLayer);
		button(tr("mcskins.layer.clear"), tr("mcskins.layer.clear.tip"), leftX + 57, layerButtons + 18, 53, 14, () -> change("mcskins.history.clear_layer", document::clearLayer));
		button(tr("mcskins.blend." + active.blend().name().toLowerCase(Locale.ROOT)), tr("mcskins.blend.tip"), leftX + 2, layerButtons + 34, 53, 14,
			() -> change("mcskins.history.blend", () -> document.setLayerBlend(document.activeLayer(), active.blend().next())));
		selected(button("α", tr("mcskins.layer.alpha_lock.tip"), leftX + 57, layerButtons + 34, 26, 14,
			() -> change("mcskins.history.alpha_lock", () -> document.setLayerAlphaLock(document.activeLayer(), !active.alphaLock()))), active.alphaLock());
		button("⊞", tr("mcskins.group.tip"), leftX + 85, layerButtons + 34, 25, 14, () -> open(new LayerGroupScreen(this)));

		viewToolbar(viewX, viewRight);
		viewWidth = Math.max(1, viewRight - viewX);
		viewHeight = Math.max(1, height - 16 - toolbarBottom);
		viewY = toolbarBottom;
		preview.viewport(viewX, viewY, viewWidth, viewHeight);

		int inner = rightX + 6;
		int hexY = 44 + 18 + svSize + 16;
		hexBox = field(tr("mcskins.color.hex"), String.format(Locale.ROOT, "%08X", color), inner, hexY, 80, 9);
		hexBox.setResponder(text -> {
			if (syncFields) {
				return;
			}
			String cleaned = text.strip().replaceFirst("^#", "");
			if (cleaned.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
				int parsed = (int) Long.parseLong(cleaned, 16);
				setColor(cleaned.length() == 6 ? parsed | 0xFF000000 : parsed);
			}
		});
		button("⇄", tr("mcskins.color.swap"), inner + 84, hexY + 1, 18, 18, this::swapColors);
		selected(button("◎", tr("mcskins.color.wheel.tip"), inner + 104, hexY + 1, 18, 18, () -> {
			settings.colorWheel(!settings.colorWheel());
			rebuildWidgets();
		}), settings.colorWheel());
		button("▦", tr("mcskins.palette.tip"), inner + 124, hexY + 1, 18, 18, () -> open(new PaletteScreen(this)));
		paletteTop = hexY + 24;
		swatchCache = null;
		syncFields();
	}

	/** The option buttons of the current tool (dithering, shading mode and strength, replace scope and tolerance). */
	private void toolOptions(int y) {
		switch (tool) {
			case GRADIENT -> selected(button(tr("mcskins.gradient.dither", onOff(settings.gradientDither())), tr("mcskins.gradient.dither.tip"),
				leftX + 2, y, 108, 16, () -> {
					settings.gradientDither(!settings.gradientDither());
					rebuildWidgets();
				}), settings.gradientDither());
			case SHADE -> {
				button(tr(shadeLighten ? "mcskins.shade.lighten" : "mcskins.shade.darken"), tr("mcskins.shade.mode.tip"), leftX + 2, y, 52, 16, () -> {
					shadeLighten = !shadeLighten;
					rebuildWidgets();
				});
				button("−", tr("mcskins.shade.weaker"), leftX + 56, y, 14, 16, () -> {
					settings.shadeStrength(settings.shadeStrength() - 1);
					rebuildWidgets();
				}).active = settings.shadeStrength() > 1;
				button("+", tr("mcskins.shade.stronger"), leftX + 96, y, 14, 16, () -> {
					settings.shadeStrength(settings.shadeStrength() + 1);
					rebuildWidgets();
				}).active = settings.shadeStrength() < 10;
			}
			case REPLACE -> {
				button(tr(settings.replaceWholeSkin() ? "mcskins.replace.skin" : "mcskins.replace.layer"), tr("mcskins.replace.scope.tip"),
					leftX + 2, y, 52, 16, () -> {
						settings.replaceWholeSkin(!settings.replaceWholeSkin());
						rebuildWidgets();
					});
				button("−", tr("mcskins.replace.tolerance.tip"), leftX + 56, y, 14, 16, () -> {
					settings.replaceTolerance(settings.replaceTolerance() - 4);
					rebuildWidgets();
				}).active = settings.replaceTolerance() > 0;
				button("+", tr("mcskins.replace.tolerance.tip"), leftX + 96, y, 14, 16, () -> {
					settings.replaceTolerance(settings.replaceTolerance() + 4);
					rebuildWidgets();
				}).active = settings.replaceTolerance() < 64;
			}
			default -> {
			}
		}
	}

	private static String onOff(boolean value) {
		return tr(value ? "options.on" : "options.off");
	}

	private void viewToolbar(int left, int right) {
		ModelRenderer model = preview.renderer();
		int x = left;
		int y = 26;
		record Item(String label, String tip, Runnable action, boolean selected) {
		}
		var items = new ArrayList<Item>();
		items.add(new Item(flat ? "2D" : "3D", tr("mcskins.view.mode.tip"), this::toggleFlat, false));
		items.add(new Item(tr(overlayTarget ? "mcskins.shell.outer" : "mcskins.shell.base"), tr("mcskins.shell.tip"), this::toggleShell, overlayTarget));
		if (!flat) {
			items.add(new Item(tr(model.overlay() ? "mcskins.overlay.shown" : "mcskins.overlay.hidden"), tr("mcskins.overlay.tip"), () -> {
				model.overlay(!model.overlay());
				preview.invalidate();
				rebuildWidgets();
			}, false));
			items.add(new Item(tr("mcskins.pose." + model.pose().name().toLowerCase(Locale.ROOT)), tr("mcskins.pose.tip"), () -> {
				var values = ModelRenderer.Pose.values();
				model.pose(values[(model.pose().ordinal() + 1) % values.length]);
				preview.invalidate();
				rebuildWidgets();
			}, false));
			items.add(new Item(tr("mcskins.animation." + model.animation().name().toLowerCase(Locale.ROOT)), tr("mcskins.animation.tip"), () -> {
				var values = ModelRenderer.Animation.values();
				model.animation(values[(model.animation().ordinal() + 1) % values.length]);
				preview.invalidate();
				rebuildWidgets();
			}, false));
			items.add(new Item(tr("mcskins.part." + model.focus().name().toLowerCase(Locale.ROOT)), tr("mcskins.part.tip"), () -> {
				var values = ModelRenderer.Focus.values();
				model.focus(values[(model.focus().ordinal() + 1) % values.length]);
				preview.invalidate();
				rebuildWidgets();
			}, false));
			String[] views = {"front", "back", "right", "left", "top", "bottom"};
			for (int index = 0; index < views.length; index++) {
				int preset = index;
				items.add(new Item(tr("mcskins.view." + views[index] + ".short"), tr("mcskins.view." + views[index]), () -> preview.view(preset), false));
			}
		}
		items.add(new Item(tr("mcskins.view.reset.short"), tr("mcskins.view.reset"), this::resetView, false));
		if (reference != null) {
			items.add(new Item(tr("mcskins.reference.short"), tr("mcskins.reference.toggle.tip"), () -> {
				referenceShown = !referenceShown;
				rebuildWidgets();
			}, referenceShown));
		}
		if (problems != null) {
			items.add(new Item(tr("mcskins.check.short"), tr("mcskins.check.hide.tip"), () -> {
				problems = null;
				rebuildWidgets();
			}, true));
		}
		if (tool.selecting() || selection != null || floating != null) {
			boolean any = selection != null || floating != null;
			items.add(new Item(tr("mcskins.selection.copy"), tr("mcskins.selection.copy.tip"), this::copySelection, false));
			items.add(new Item(tr("mcskins.selection.cut"), tr("mcskins.selection.cut.tip"), this::cutSelection, false));
			items.add(new Item(tr("mcskins.selection.paste"), tr("mcskins.selection.paste.tip"), this::paste, false));
			if (any) {
				items.add(new Item("↔", tr("mcskins.selection.flip_h"), () -> transformSelection("mcskins.history.flip", Clip::flipHorizontal), false));
				items.add(new Item("↕", tr("mcskins.selection.flip_v"), () -> transformSelection("mcskins.history.flip", Clip::flipVertical), false));
				items.add(new Item("⟳", tr("mcskins.selection.rotate"), () -> transformSelection("mcskins.history.rotate", Clip::rotateClockwise), false));
				items.add(new Item("✕", tr("mcskins.selection.deselect"), this::deselect, false));
			}
		}
		for (Item item : items) {
			int itemWidth = Math.max(18, font.width(item.label) + 8);
			if (x + itemWidth > right && x > left) {
				x = left;
				y += 18;
			}
			selected(button(item.label, item.tip, x, y, itemWidth, 16, item.action), item.selected);
			x += itemWidth + 2;
		}
		toolbarBottom = y + 18;
	}

	/** Opens a dialog on top of the editor, finishing any floating selection first. */
	void open(Screen screen) {
		commitFloating();
		endInteraction();
		GameCompat.setScreen(minecraft, screen);
	}

	private void toggleFlat() {
		flat = !flat;
		endInteraction();
		rebuildWidgets();
	}

	private void toggleShell() {
		overlayTarget = !overlayTarget;
		preview.renderer().target(overlayTarget ? ModelRenderer.Shell.OUTER : ModelRenderer.Shell.BASE);
		preview.invalidate();
		rebuildWidgets();
	}

	private void toggleGrid() {
		settings.grid(!settings.grid());
		preview.renderer().grid(settings.grid());
		preview.invalidate();
		rebuildWidgets();
	}

	private void resetView() {
		if (flat) {
			flatZoom = 1;
			flatPanX = 0;
			flatPanY = 0;
		} else {
			preview.reset();
		}
	}

	private void brush(int delta) {
		settings.brushSize(settings.brushSize() + delta);
		rebuildWidgets();
	}

	private void select(Tool selected) {
		if (!selected.selecting()) {
			commitFloating();
		}
		endInteraction();
		tool = selected;
		rebuildWidgets();
	}

	// ---- color --------------------------------------------------------------------------------------------

	private void syncFields() {
		if (hexBox == null) {
			return;
		}
		syncFields = true;
		if (!hexBox.isFocused()) {
			hexBox.setValue(String.format(Locale.ROOT, "%08X", color));
		}
		syncFields = false;
	}

	private void setColor(int argb) {
		color = argb;
		updateHsv();
		syncFields();
	}

	private void swapColors() {
		int swap = previousColor;
		previousColor = color;
		setColor(swap);
	}

	private void updateHsv() {
		float[] hsv = ColorOps.hsv(color);
		if (hsv[1] != 0) {
			hue = hsv[0];
		}
		if (hsv[2] != 0) {
			saturation = hsv[1];
		}
		value = hsv[2];
	}

	private int svLeft() {
		return rightX + 6;
	}

	private int svTop() {
		return 62;
	}

	private int alphaTop() {
		return svTop() + svSize + 4;
	}

	private boolean pressColor(double x, double y) {
		int left = svLeft();
		int top = svTop();
		if (x >= rightX + 6 && x < rightX + 70 && y >= 40 && y < 56) {
			swapColors();
			return true;
		}
		if (x >= left && y >= top && x < left + svSize && y < top + svSize) {
			if (settings.colorWheel()) {
				double dx = (x - left - svSize / 2.0) / (svSize / 2.0);
				double dy = (y - top - svSize / 2.0) / (svSize / 2.0);
				if (dx * dx + dy * dy > 1.1) {
					return false;
				}
			}
			svDrag = true;
			dragColor(x, y);
			return true;
		}
		if (x >= left + svSize + 4 && x < left + svSize + 16 && y >= top && y < top + svSize) {
			hueDrag = true;
			dragColor(x, y);
			return true;
		}
		if (x >= left && x < left + svSize + 16 && y >= alphaTop() && y < alphaTop() + 8) {
			alphaDrag = true;
			dragColor(x, y);
			return true;
		}
		int swatch = swatchAt(x, y);
		if (swatch != 0) {
			setColor(swatch);
			return true;
		}
		return false;
	}

	private void dragColor(double x, double y) {
		int left = svLeft();
		int top = svTop();
		boolean wheel = settings.colorWheel();
		if (svDrag) {
			if (wheel) {
				double dx = (x - left - svSize / 2.0) / (svSize / 2.0);
				double dy = (y - top - svSize / 2.0) / (svSize / 2.0);
				double angle = Math.atan2(dy, dx) / (Math.PI * 2);
				hue = (float) (angle < 0 ? angle + 1 : angle);
				saturation = (float) Math.min(1, Math.sqrt(dx * dx + dy * dy));
			} else {
				saturation = (float) Math.clamp((x - left) / Math.max(1, svSize - 1), 0, 1);
				value = (float) Math.clamp(1 - (y - top) / Math.max(1, svSize - 1), 0, 1);
			}
		} else if (hueDrag) {
			if (wheel) {
				value = (float) Math.clamp(1 - (y - top) / Math.max(1, svSize - 1), 0, 1);
			} else {
				hue = (float) Math.clamp((y - top) / Math.max(1, svSize - 1), 0, 0.9999);
			}
		} else if (alphaDrag) {
			int alpha = (int) Math.round(Math.clamp((x - left) / (svSize + 15.0), 0, 1) * 255);
			color = alpha << 24 | color & 0xFFFFFF;
			syncFields();
			return;
		}
		color = ColorOps.fromHsv(hue, saturation, value, color);
		syncFields();
	}

	/** Recent colors first, then the custom or default palette; returns 0 when no swatch is under the point. */
	private int swatchAt(double x, double y) {
		int left = rightX + 6;
		if (x < left || x >= left + 8 * 18 || y < paletteTop || y >= height - 16) {
			return 0;
		}
		int[] swatches = swatches();
		int origin = paletteTop + 10 - scrollPalette;
		int column = (int) ((x - left) / 18);
		int row = (int) Math.floor((y - origin) / 14);
		if (row < 0 || (x - left) % 18 >= 16 || (y - origin) % 14 >= 12) {
			return 0;
		}
		int index = row * 8 + column;
		return index >= 0 && index < swatches.length ? swatches[index] : 0;
	}

	private int[] swatches() {
		if (swatchCache == null) {
			List<Integer> recent = settings.recentColors();
			List<Integer> custom = settings.palette();
			int[] palette = custom.isEmpty() ? PALETTE : custom.stream().mapToInt(Integer::intValue).toArray();
			int recentRows = (recent.size() + 7) / 8;
			int[] result = new int[recentRows * 8 + palette.length];
			for (int index = 0; index < recent.size(); index++) {
				result[index] = recent.get(index);
			}
			System.arraycopy(palette, 0, result, recentRows * 8, palette.length);
			swatchCache = result;
			swatchRecentCount = recent.size();
		}
		return swatchCache;
	}

	/** Replaces the palette shown in the color panel (empty restores the default one). */
	void usePalette(List<Integer> colors) {
		settings.palette(colors);
		swatchCache = null;
		scrollPalette = 0;
	}

	int currentColor() {
		return color;
	}

	// ---- painting -----------------------------------------------------------------------------------------

	private SkinGeometry geometry() {
		return SkinGeometry.of(document.slim());
	}

	void refresh() {
		textureDirty = false;
		pixels = floating != null ? document.flattenWith(floating.stampOnto(floatingBase)) : document.flatten();
		preview.texture(pixels, document.slim());
	}

	private void markTextureDirty() {
		textureDirty = true;
	}

	/** Runs an editing operation as one named undo step; a floating selection is put down first. */
	void change(String labelKey, Runnable operation) {
		commitFloating();
		endInteraction();
		try {
			document.label(tr(labelKey));
			operation.run();
			refresh();
		} catch (RuntimeException exception) {
			failure(exception);
		}
		rebuildWidgets();
	}

	/** Replaces the active layer with a transformed copy of it, as one named undo step. */
	void changeCanvas(String labelKey, UnaryOperator<int[]> transform) {
		change(labelKey, () -> document.applyCanvas(transform.apply(document.activeCanvas())));
	}

	/** Undo, redo or a history jump: a floating selection is dropped instead of committed. */
	void history(Runnable operation) {
		cancelFloating();
		endInteraction();
		try {
			operation.run();
			refresh();
		} catch (RuntimeException exception) {
			failure(exception);
		}
		rebuildWidgets();
	}

	private int pickPixel(double x, double y) {
		if (flat) {
			double scale = flatScale();
			int column = (int) Math.floor((x - flatLeft()) / scale);
			int row = (int) Math.floor((y - flatTop()) / scale);
			return column < 0 || row < 0 || column >= 64 || row >= 64 ? -1 : row * 64 + column;
		}
		return preview.pick(x, y);
	}

	private BitSet footprint(int pixel) {
		if (pixel < 0) {
			return new BitSet();
		}
		boolean onFace = geometry().faceAt(pixel & 63, pixel >>> 6) != null;
		BitSet result = tool.sized()
			? SkinTools.brush(geometry(), pixel, settings.brushSize(), onFace)
			: single(pixel);
		return settings.mirror() && !tool.selecting() ? SkinTools.mirrored(geometry(), result) : result;
	}

	private BitSet hoverFootprint(int pixel) {
		if (pixel != footprintPixel || tool != footprintTool || settings.brushSize() != footprintBrush
			|| settings.mirror() != footprintMirror || document.slim() != footprintSlim) {
			footprintCache = footprint(pixel);
			footprintPixel = pixel;
			footprintTool = tool;
			footprintBrush = settings.brushSize();
			footprintMirror = settings.mirror();
			footprintSlim = document.slim();
		}
		return footprintCache;
	}

	private static BitSet single(int pixel) {
		var result = new BitSet(4096);
		result.set(pixel);
		return result;
	}

	private BitSet shapePixels() {
		if (shapeStart < 0 || shapeEnd < 0) {
			return new BitSet();
		}
		if (tool == Tool.GRADIENT) {
			return SkinTools.line(shapeStart, shapeEnd);
		}
		BitSet shape = tool == Tool.LINE ? SkinTools.line(shapeStart, shapeEnd)
			: SkinTools.rectangle(shapeStart, shapeEnd, tool == Tool.FILLED_RECTANGLE);
		shape = SkinTools.thicken(shape, settings.brushSize());
		SkinGeometry.Face face = geometry().faceAt(shapeStart & 63, shapeStart >>> 6);
		if (!flat && face != null) {
			var clipped = new BitSet(4096);
			for (int pixel = shape.nextSetBit(0); pixel >= 0; pixel = shape.nextSetBit(pixel + 1)) {
				if (face.contains(pixel & 63, pixel >>> 6)) {
					clipped.set(pixel);
				}
			}
			shape = clipped;
		}
		return settings.mirror() ? SkinTools.mirrored(geometry(), shape) : shape;
	}

	/** The area a fill (or gradient) covers from a pixel: the selection if there is one, else the same-color region. */
	private BitSet fillArea(int pixel) {
		if (selection != null) {
			return (BitSet) selection.clone();
		}
		int[] canvas = document.activeCanvas();
		var area = SkinTools.fill(canvas, pixel, geometry().faceAt(pixel & 63, pixel >>> 6));
		if (settings.mirror() && geometry().mirror(pixel) >= 0) {
			int mirror = geometry().mirror(pixel);
			area.or(SkinTools.fill(canvas, mirror, geometry().faceAt(mirror & 63, mirror >>> 6)));
		}
		return area;
	}

	private void beginTool(int pixel, double mouseX, double mouseY, boolean shift) {
		if (pixel < 0) {
			status = tr(flat ? "mcskins.status.outside_texture" : "mcskins.status.no_pixel");
			return;
		}
		if (tool == Tool.PICKER) {
			previousColor = color;
			setColor(document.sample(pixel & 63, pixel >>> 6));
			return;
		}
		if (tool.selecting()) {
			beginSelection(pixel, shift);
			return;
		}
		commitFloating();
		if (!document.effectivelyVisible(document.activeLayer())) {
			status = tr("mcskins.status.hidden_layer");
			return;
		}
		if (tool == Tool.BRUSH || tool == Tool.FILL || tool.shape() || tool == Tool.NOISE || tool == Tool.REPLACE) {
			settings.useColor(color);
			swatchCache = null;
		}
		if (tool.shape()) {
			shapeStart = pixel;
			shapeEnd = pixel;
			return;
		}
		document.label(tr(tool.key));
		document.beginStroke();
		strokeTouched.clear();
		painting = true;
		preview.freeze(true);
		lastPixel = pixel;
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		if (tool == Tool.FILL) {
			paint(fillArea(pixel));
			endInteraction();
			return;
		}
		if (tool == Tool.REPLACE) {
			replaceColor(pixel);
			endInteraction();
			return;
		}
		paint(footprint(pixel));
	}

	private void replaceColor(int pixel) {
		boolean skin = settings.replaceWholeSkin();
		int[] image = skin ? document.flatten() : document.activeCanvas();
		BitSet area = ColorOps.matching(image, image[pixel], settings.replaceTolerance(), selection);
		paint(area);
		status = tr("mcskins.status.replaced", area.cardinality());
	}

	private void dragTool(double mouseX, double mouseY) {
		if (shapeStart >= 0) {
			int pixel = pickPixel(mouseX, mouseY);
			if (pixel >= 0) {
				shapeEnd = pixel;
			}
			return;
		}
		if (!painting) {
			return;
		}
		var area = new BitSet(4096);
		if (flat) {
			int pixel = pickPixel(mouseX, mouseY);
			if (pixel >= 0) {
				BitSet path = lastPixel >= 0 ? SkinTools.line(lastPixel, pixel) : single(pixel);
				for (int step = path.nextSetBit(0); step >= 0; step = path.nextSetBit(step + 1)) {
					area.or(footprint(step));
				}
				lastPixel = pixel;
			}
		} else {
			int steps = (int) Math.clamp(Math.ceil(Math.max(Math.abs(mouseX - lastMouseX), Math.abs(mouseY - lastMouseY)) * 2), 1, 512);
			var picked = new BitSet(4096);
			for (int step = 1; step <= steps; step++) {
				double fraction = step / (double) steps;
				int pixel = preview.pick(lastMouseX + (mouseX - lastMouseX) * fraction, lastMouseY + (mouseY - lastMouseY) * fraction);
				if (pixel >= 0 && !picked.get(pixel)) {
					picked.set(pixel);
					area.or(footprint(pixel));
				}
			}
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		paint(area);
	}

	private void finishShape() {
		if (shapeStart < 0) {
			return;
		}
		int start = shapeStart;
		int end = shapeEnd;
		BitSet shape = tool == Tool.GRADIENT ? fillArea(start) : shapePixels();
		shapeStart = -1;
		shapeEnd = -1;
		document.label(tr(tool.key));
		document.beginStroke();
		strokeTouched.clear();
		if (tool == Tool.GRADIENT) {
			if (selection != null) {
				shape.and(selection);
			}
			boolean dither = settings.gradientDither();
			try {
				for (int pixel = shape.nextSetBit(0); pixel >= 0; pixel = shape.nextSetBit(pixel + 1)) {
					document.paint(pixel & 63, pixel >>> 6, ColorOps.gradient(pixel & 63, pixel >>> 6, start & 63, start >>> 6,
						end & 63, end >>> 6, color, previousColor, dither));
				}
			} catch (RuntimeException exception) {
				failure(exception);
			}
			markTextureDirty();
		} else {
			paint(shape);
		}
		document.endStroke();
	}

	/** Paints a set of texture pixels with the current tool, limited to the selection if there is one. */
	private void paint(BitSet requested) {
		BitSet area = requested;
		if (selection != null) {
			area = (BitSet) requested.clone();
			area.and(selection);
		}
		double strength = settings.shadeStrength() * 0.05;
		try {
			for (int pixel = area.nextSetBit(0); pixel >= 0; pixel = area.nextSetBit(pixel + 1)) {
				int x = pixel & 63;
				int y = pixel >>> 6;
				int next = switch (tool) {
					case ERASER -> 0;
					case LIGHTEN, DARKEN -> {
						if (strokeTouched.get(pixel)) {
							yield Integer.MIN_VALUE;
						}
						int sample = document.sample(x, y);
						yield sample >>> 24 == 0 ? Integer.MIN_VALUE : SkinTools.shade(sample, tool == Tool.LIGHTEN ? 0.14 : -0.14);
					}
					case SHADE -> {
						if (strokeTouched.get(pixel)) {
							yield Integer.MIN_VALUE;
						}
						int sample = document.sample(x, y);
						yield sample >>> 24 == 0 ? Integer.MIN_VALUE : ColorOps.shadeShifted(sample, strength, shadeLighten);
					}
					case NOISE -> strokeTouched.get(pixel) ? Integer.MIN_VALUE : SkinTools.noise(color, 0.12, random);
					default -> color;
				};
				strokeTouched.set(pixel);
				if (next != Integer.MIN_VALUE) {
					document.paint(x, y, next);
				}
			}
			markTextureDirty();
		} catch (RuntimeException exception) {
			endInteraction();
			failure(exception);
		}
	}

	private void endInteraction() {
		painting = false;
		cameraButton = -1;
		svDrag = false;
		hueDrag = false;
		alphaDrag = false;
		if (opacityDrag) {
			opacityDrag = false;
			document.endStroke();
		}
		shapeStart = -1;
		shapeEnd = -1;
		lastPixel = -1;
		selectStart = -1;
		selectEnd = -1;
		moveAnchor = -1;
		moveStartClip = null;
		document.endStroke();
		preview.freeze(false);
	}

	// ---- selection ----------------------------------------------------------------------------------------

	private boolean insideSelection(int pixel) {
		if (floating != null) {
			return floating.canvasMask().get(pixel);
		}
		return selection != null && selection.get(pixel);
	}

	private void beginSelection(int pixel, boolean add) {
		if (!flat) {
			status = tr("mcskins.status.select_flat");
			return;
		}
		if (!add && insideSelection(pixel)) {
			if (floating == null) {
				lift("mcskins.history.move_selection");
			}
			if (floating != null) {
				moveStartClip = floating;
				moveAnchor = pixel;
			}
			return;
		}
		commitFloating();
		if (tool == Tool.WAND) {
			BitSet region = SkinTools.fill(document.activeCanvas(), pixel, null);
			if (add && selection != null) {
				region.or(selection);
			}
			selection = region;
			status = tr("mcskins.status.selected", region.cardinality());
			rebuildWidgets();
			return;
		}
		selectStart = pixel;
		selectEnd = pixel;
		selectAdd = add;
	}

	private BitSet selectionRectangle() {
		return SkinTools.rectangle(selectStart, selectEnd, true);
	}

	private void finishSelection() {
		if (selectStart < 0) {
			return;
		}
		BitSet rectangle = selectionRectangle();
		boolean click = selectStart == selectEnd;
		if (selectAdd && selection != null) {
			rectangle.or(selection);
			selection = rectangle;
		} else {
			selection = click ? null : rectangle;
		}
		if (selection != null) {
			status = tr("mcskins.status.selected", selection.cardinality());
		}
		selectStart = -1;
		selectEnd = -1;
	}

	/** Lifts the selected pixels of the active layer into a floating clip; the layer itself is unchanged until commit. */
	private void lift(String labelKey) {
		if (selection == null || selection.isEmpty()) {
			return;
		}
		int[] canvas = document.activeCanvas();
		Clip clip = Clip.of(canvas, selection);
		if (clip == null) {
			return;
		}
		floating = clip;
		floatingBase = Clip.cleared(canvas, selection);
		floatingLabel = labelKey;
		selection = null;
		refresh();
	}

	/** Puts a floating clip down on the active layer as one undo step and selects it. */
	void commitFloating() {
		if (floating == null) {
			return;
		}
		Clip clip = floating;
		int[] base = floatingBase;
		String label = floatingLabel;
		floating = null;
		floatingBase = null;
		moveStartClip = null;
		try {
			document.endStroke();
			document.label(tr(label));
			document.applyCanvas(clip.stampOnto(base));
			selection = clip.canvasMask();
		} catch (RuntimeException exception) {
			failure(exception);
		}
		refresh();
	}

	private void cancelFloating() {
		if (floating != null) {
			floating = null;
			floatingBase = null;
			moveStartClip = null;
			refresh();
		}
	}

	private void deselect() {
		if (floating != null) {
			cancelFloating();
		}
		selection = null;
		rebuildWidgets();
	}

	private Clip currentClip() {
		if (floating != null) {
			return floating;
		}
		return selection == null ? null : Clip.of(document.activeCanvas(), selection);
	}

	private void copySelection() {
		Clip clip = currentClip();
		if (clip == null) {
			status = tr("mcskins.status.nothing_selected");
			return;
		}
		clipboard = clip;
		status = tr("mcskins.status.copied", clip.width(), clip.height());
	}

	private void cutSelection() {
		Clip clip = currentClip();
		if (clip == null) {
			status = tr("mcskins.status.nothing_selected");
			return;
		}
		clipboard = clip;
		if (floating != null) {
			// The lifted pixels are already off the layer; keep the hole.
			int[] base = floatingBase;
			floating = null;
			floatingBase = null;
			document.label(tr("mcskins.history.cut"));
			document.applyCanvas(base);
		} else {
			BitSet mask = (BitSet) selection.clone();
			changeCanvas("mcskins.history.cut", canvas -> Clip.cleared(canvas, mask));
		}
		refresh();
		rebuildWidgets();
	}

	private void deleteSelection() {
		if (floating != null) {
			int[] base = floatingBase;
			floating = null;
			floatingBase = null;
			document.label(tr("mcskins.history.delete_selection"));
			document.applyCanvas(base);
			refresh();
			rebuildWidgets();
		} else if (selection != null) {
			BitSet mask = (BitSet) selection.clone();
			changeCanvas("mcskins.history.delete_selection", canvas -> Clip.cleared(canvas, mask));
		}
	}

	/** Ctrl+V: the internal clipboard as a floating clip, or else an image from the system clipboard as a new layer. */
	private void paste() {
		if (clipboard == null) {
			pasteSystemImage();
			return;
		}
		commitFloating();
		if (!document.effectivelyVisible(document.activeLayer())) {
			status = tr("mcskins.status.hidden_layer");
			return;
		}
		floating = clipboard;
		floatingBase = document.activeCanvas();
		floatingLabel = "mcskins.history.paste";
		selection = null;
		if (!flat) {
			flat = true;
		}
		tool = Tool.SELECT;
		status = tr("mcskins.status.pasted");
		refresh();
		rebuildWidgets();
	}

	private void transformSelection(String labelKey, UnaryOperator<Clip> transform) {
		boolean wasFloating = floating != null;
		if (!wasFloating) {
			lift(labelKey);
		}
		if (floating == null) {
			status = tr("mcskins.status.nothing_selected");
			return;
		}
		floating = transform.apply(floating);
		if (!wasFloating) {
			floatingLabel = labelKey;
			commitFloating();
		}
		refresh();
		rebuildWidgets();
	}

	private void selectAll() {
		commitFloating();
		selection = new BitSet(4096);
		selection.set(0, 4096);
		tool = tool.selecting() ? tool : Tool.SELECT;
		rebuildWidgets();
	}

	/** Reads an image from the system clipboard on the I/O thread and adds it as a new layer. */
	void pasteSystemImage() {
		if (importing != null && !importing.isDone()) {
			return;
		}
		if (document.layers().size() >= SkinDocument.MAX_LAYERS) {
			failure(new IllegalStateException(tr("mcskins.status.layers_full", SkinDocument.MAX_LAYERS)));
			return;
		}
		String text;
		try {
			text = minecraft.keyboardHandler.getClipboard();
		} catch (RuntimeException exception) {
			text = "";
		}
		String clipboardText = text;
		String name = tr("mcskins.layer.pasted");
		status = tr("mcskins.status.reading_clipboard");
		importing = CompletableFuture.supplyAsync(() -> {
			try {
				BufferedImage image = ClipboardImage.read(clipboardText);
				return SkinDocument.layerFromImage(image, name);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((layer, error) -> minecraft.execute(() -> {
			if (error == null) {
				change("mcskins.history.paste_image", () -> document.addImportedLayer(layer));
				status = tr("mcskins.status.pasted_image");
			} else {
				Throwable cause = error.getCause() == null ? error : error.getCause();
				status = cause instanceof IOException ? cause.getMessage() : tr("mcskins.clipboard.failed");
			}
		}));
	}

	// ---- reference image and skin check -------------------------------------------------------------------

	boolean hasReference() {
		return reference != null;
	}

	boolean referenceShown() {
		return referenceShown;
	}

	int referenceOpacity() {
		return referenceOpacity;
	}

	void referenceOpacity(int opacity) {
		referenceOpacity = Math.clamp(opacity, 16, 255);
		referenceDirty = true;
	}

	void referenceShown(boolean shown) {
		referenceShown = shown;
	}

	void clearReference() {
		reference = null;
		referenceTexture.close();
	}

	/** Loads a PNG as the reference image (on the I/O thread); the callback gets null or an error message. */
	void loadReference(Path path, java.util.function.Consumer<String> done) {
		CompletableFuture.supplyAsync(() -> {
			try {
				BufferedImage image = PngCodec.decode(SafeFiles.read(path, PngCodec.MAX_BYTES), 4096, 4_194_304);
				int[] scaled = new int[256 * 256];
				for (int y = 0; y < 256; y++) {
					for (int x = 0; x < 256; x++) {
						scaled[y * 256 + x] = image.getRGB(x * image.getWidth() / 256, y * image.getHeight() / 256);
					}
				}
				return scaled;
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((scaled, error) -> minecraft.execute(() -> {
			if (error == null) {
				reference = scaled;
				referenceShown = true;
				referenceDirty = true;
				flat = true;
				done.accept(null);
			} else {
				Throwable cause = error.getCause() == null ? error : error.getCause();
				done.accept(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
			}
		}));
	}

	/** Highlights pixels found by the skin check in red, in both views, until dismissed. */
	void showProblems(BitSet pixels) {
		problems = pixels == null || pixels.isEmpty() ? null : (BitSet) pixels.clone();
		if (problems != null) {
			flat = true;
		}
	}

	// ---- layers -------------------------------------------------------------------------------------------

	/** A row of the layer list: a group header ({@code layer} = -1) or a layer. */
	private record Row(int layer, int group) {
	}

	private List<Row> rows() {
		var rows = new ArrayList<Row>();
		var layers = document.layers();
		int lastGroup = 0;
		for (int index = layers.size() - 1; index >= 0; index--) {
			int group = layers.get(index).group();
			LayerGroup info = group == 0 ? null : document.group(group);
			if (info != null && group != lastGroup) {
				rows.add(new Row(-1, group));
			}
			lastGroup = info == null ? 0 : group;
			if (info == null || !info.collapsed() || index == document.activeLayer()) {
				rows.add(new Row(index, info == null ? 0 : group));
			}
		}
		return rows;
	}

	private int layerListBottom() {
		return layerBottom - 14;
	}

	private int layerContentHeight() {
		return rows().size() * LAYER_ROW;
	}

	private Row rowAt(double y) {
		if (y < layerTop + 12) {
			return null;
		}
		int row = ((int) (y - layerTop - 12) + scrollLayers) / LAYER_ROW;
		List<Row> rows = rows();
		return row < 0 || row >= rows.size() ? null : rows.get(row);
	}

	private boolean pressLayers(double x, double y) {
		if (x < leftX || x >= leftX + 112) {
			return false;
		}
		int opacityY = layerListBottom() + 2;
		if (y >= opacityY && y < opacityY + 10) {
			commitFloating();
			opacityDrag = true;
			document.label(tr("mcskins.history.layer_opacity"));
			document.beginStroke();
			dragOpacity(x);
			return true;
		}
		if (y < layerTop + 12 || y >= layerListBottom()) {
			return false;
		}
		Row row = rowAt(y);
		if (row == null) {
			return true;
		}
		if (row.layer() < 0) {
			int group = row.group();
			LayerGroup info = document.group(group);
			if (info == null) {
				return true;
			}
			if (x < leftX + 12) {
				document.setGroupCollapsed(group, !info.collapsed());
				rebuildWidgets();
			} else if (x < leftX + 24) {
				change("mcskins.history.group_visibility", () -> document.setGroupVisible(group, !info.visible()));
			} else {
				open(new LayerGroupScreen(this, group));
			}
			return true;
		}
		int index = row.layer();
		if (x < leftX + 16) {
			change("mcskins.history.layer_visibility", () -> document.setLayerVisible(index, !document.layers().get(index).visible()));
			return true;
		}
		long now = System.currentTimeMillis();
		boolean doubleClick = index == lastLayerClicked && now - lastLayerClick < 400;
		lastLayerClick = now;
		lastLayerClicked = index;
		commitFloating();
		endInteraction();
		document.selectLayer(index);
		refresh();
		rebuildWidgets();
		if (doubleClick) {
			renameLayer();
		}
		return true;
	}

	private void dragOpacity(double x) {
		int opacity = (int) Math.round(Math.clamp((x - leftX - 4) / 104.0, 0, 1) * 255);
		try {
			document.setLayerOpacity(document.activeLayer(), opacity);
			markTextureDirty();
		} catch (RuntimeException exception) {
			failure(exception);
		}
	}

	private void renameLayer() {
		int index = document.activeLayer();
		open(new TextPromptScreen("mcskins.layer.rename.title", this,
			document.layers().get(index).name(), value -> {
				try {
					document.label(tr("mcskins.history.rename_layer"));
					document.renameLayer(index, value);
					return null;
				} catch (RuntimeException exception) {
					return exception.getMessage();
				}
			}));
	}

	// ---- files --------------------------------------------------------------------------------------------

	private void saveAsync() {
		if (closing || saving != null && !saving.isDone()) {
			return;
		}
		commitFloating();
		endInteraction();
		SkinRepository.PreparedSave prepared;
		try {
			prepared = repository.prepare(document);
		} catch (IOException | RuntimeException exception) {
			failure(exception);
			return;
		}
		status = tr("mcskins.status.saving");
		lastAutosave = System.nanoTime();
		saving = write(prepared).whenComplete((written, error) -> minecraft.execute(() -> {
			if (error == null) {
				if (written) {
					document.markSaved(prepared.revision(), prepared.sequence());
				}
				status = tr("mcskins.status.saved", document.name());
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
		}));
	}

	private CompletableFuture<Boolean> write(SkinRepository.PreparedSave prepared) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return repository.write(prepared);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io());
	}

	private void saveAndLeave(SkinScreen dialog, Button button) {
		if (closing) {
			return;
		}
		endInteraction();
		SkinRepository.PreparedSave prepared;
		try {
			prepared = repository.prepare(document);
		} catch (IOException | RuntimeException exception) {
			failure(exception);
			dialog.status = status;
			return;
		}
		closing = true;
		button.active = false;
		dialog.status = tr("mcskins.status.saving");
		saving = write(prepared).whenComplete((written, error) -> minecraft.execute(() -> {
			closing = false;
			if (error == null) {
				if (written) {
					document.markSaved(prepared.revision(), prepared.sequence());
				}
				if (GameCompat.currentScreen(minecraft) == dialog) {
					GameCompat.setScreen(minecraft, parent);
				}
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
				dialog.status = status;
				button.active = true;
			}
		}));
	}

	private void export() {
		commitFloating();
		int[] flattened = document.flatten();
		var id = document.id();
		status = tr("mcskins.status.exporting");
		CompletableFuture.supplyAsync(() -> {
			try {
				return repository.export(id, flattened);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((path, error) -> minecraft.execute(() -> {
			if (error == null) {
				status = tr("mcskins.status.exported", path.getFileName());
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
		}));
	}

	private void openExports() {
		try {
			if (!GameCompat.openPath(repository.exportsDirectory())) {
				status = tr("mcskins.status.folder_failed", repository.exportsDirectory());
			}
		} catch (IOException exception) {
			failure(exception);
		}
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		for (Path path : paths) {
			if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
				if (importing != null && !importing.isDone()) {
					return;
				}
				if (document.layers().size() >= SkinDocument.MAX_LAYERS) {
					failure(new IllegalStateException(tr("mcskins.status.layers_full", SkinDocument.MAX_LAYERS)));
					return;
				}
				String fallback = tr("mcskins.layer.imported");
				status = tr("mcskins.status.importing", path.getFileName());
				importing = CompletableFuture.supplyAsync(() -> {
					try {
						return SkinDocument.decodeLayer(path, 64, 64, fallback);
					} catch (IOException exception) {
						throw new CompletionException(exception);
					}
				}, McSkinsClient.io()).whenComplete((layer, error) -> minecraft.execute(() -> {
					if (error == null) {
						change("mcskins.history.import_layer", () -> document.addImportedLayer(layer));
						status = tr("mcskins.status.imported_layer", path.getFileName());
					} else {
						failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
					}
				}));
				return;
			}
		}
		status = tr("mcskins.status.drop_png");
	}

	// ---- input --------------------------------------------------------------------------------------------

	private boolean onView(double x, double y) {
		return x >= viewX && y >= viewY && x < viewX + viewWidth && y < viewY + viewHeight;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double x = event.x();
		double y = event.y();
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		clearFocus();
		syncFields();
		if (event.button() == InputCompat.MOUSE_BUTTON_LEFT && (pressColor(x, y) || pressLayers(x, y))) {
			return true;
		}
		if (!onView(x, y)) {
			return false;
		}
		if (event.button() == InputCompat.MOUSE_BUTTON_RIGHT || event.button() == InputCompat.MOUSE_BUTTON_MIDDLE) {
			cameraButton = event.button();
			lastMouseX = x;
			lastMouseY = y;
			return true;
		}
		if (event.button() != InputCompat.MOUSE_BUTTON_LEFT) {
			return false;
		}
		if (event.hasAltDown()) {
			int pixel = pickPixel(x, y);
			if (pixel >= 0) {
				previousColor = color;
				setColor(document.sample(pixel & 63, pixel >>> 6));
			}
			return true;
		}
		beginTool(pickPixel(x, y), x, y, event.hasShiftDown());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (svDrag || hueDrag || alphaDrag) {
			dragColor(event.x(), event.y());
			return true;
		}
		if (opacityDrag) {
			dragOpacity(event.x());
			return true;
		}
		if (cameraButton >= 0 && event.button() == cameraButton) {
			boolean orbit = cameraButton == InputCompat.MOUSE_BUTTON_RIGHT && !flat;
			if (flat) {
				flatPanX += dx;
				flatPanY += dy;
			} else if (orbit) {
				preview.orbit(dx, dy);
			} else {
				preview.pan(dx, dy);
			}
			return true;
		}
		if (event.button() == InputCompat.MOUSE_BUTTON_LEFT && moveStartClip != null) {
			int pixel = pickPixel(event.x(), event.y());
			if (pixel >= 0) {
				floating = moveStartClip.moved((pixel & 63) - (moveAnchor & 63), (pixel >>> 6) - (moveAnchor >>> 6));
				refresh();
			}
			return true;
		}
		if (event.button() == InputCompat.MOUSE_BUTTON_LEFT && selectStart >= 0) {
			int pixel = pickPixel(event.x(), event.y());
			if (pixel >= 0) {
				selectEnd = pixel;
			}
			return true;
		}
		if (event.button() == InputCompat.MOUSE_BUTTON_LEFT && (painting || shapeStart >= 0)) {
			dragTool(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputCompat.MOUSE_BUTTON_LEFT && selectStart >= 0) {
			finishSelection();
			endInteraction();
			rebuildWidgets();
			return true;
		}
		if (event.button() == InputCompat.MOUSE_BUTTON_LEFT && moveStartClip != null) {
			endInteraction();
			rebuildWidgets();
			return true;
		}
		if (shapeStart >= 0 && event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			finishShape();
			refresh();
			endInteraction();
			rebuildWidgets();
			return true;
		}
		boolean handled = painting || cameraButton >= 0 || svDrag || hueDrag || alphaDrag || opacityDrag;
		endInteraction();
		if (handled) {
			rebuildWidgets();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (x >= leftX && x < leftX + 112 && y >= layerTop && y < layerListBottom()) {
			int visible = layerListBottom() - layerTop - 12;
			scrollLayers = Math.clamp((int) (scrollLayers - vertical * LAYER_ROW), 0, Math.max(0, layerContentHeight() - visible));
			return true;
		}
		if (x >= rightX && y >= paletteTop) {
			int content = (swatches().length + 7) / 8 * 14 + 10;
			scrollPalette = Math.clamp((int) (scrollPalette - vertical * 14), 0, Math.max(0, content - (height - 16 - paletteTop)));
			return true;
		}
		if (onView(x, y) && !painting) {
			if (flat) {
				double before = flatScale();
				double anchorX = (x - flatLeft()) / before;
				double anchorY = (y - flatTop()) / before;
				flatZoom = Math.clamp(flatZoom * Math.exp(vertical * 0.15), 0.5, 8);
				double after = flatScale();
				flatPanX += (x - flatLeft()) - anchorX * after;
				flatPanY += (y - flatTop()) - anchorY * after;
			} else {
				preview.zoom(vertical);
			}
			return true;
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() instanceof EditBox) {
			return super.keyPressed(event);
		}
		int key = event.key();
		int letter = InputCompat.shortcutKey(event);
		if (key == InputCompat.KEY_ESCAPE && (floating != null || selection != null)) {
			deselect();
			return true;
		}
		if ((key == InputCompat.KEY_ENTER || key == InputCompat.KEY_KP_ENTER) && floating != null) {
			commitFloating();
			rebuildWidgets();
			return true;
		}
		if ((key == InputCompat.KEY_DELETE || key == InputCompat.KEY_BACKSPACE) && (floating != null || selection != null)) {
			deleteSelection();
			return true;
		}
		if (event.hasControlDown()) {
			if (letter == InputCompat.KEY_Z) {
				history(event.hasShiftDown() ? document::redo : document::undo);
			} else if (letter == InputCompat.KEY_Y) {
				history(document::redo);
			} else if (letter == InputCompat.KEY_S) {
				saveAsync();
			} else if (letter == InputCompat.KEY_C) {
				copySelection();
			} else if (letter == InputCompat.KEY_X) {
				cutSelection();
			} else if (letter == InputCompat.KEY_V) {
				if (event.hasShiftDown()) {
					pasteSystemImage();
				} else {
					paste();
				}
			} else if (letter == InputCompat.KEY_A) {
				selectAll();
			} else {
				return super.keyPressed(event);
			}
			return true;
		}
		for (Tool choice : Tool.values()) {
			if (choice.shortcut >= 0 && letter == choice.shortcut) {
				select(choice);
				return true;
			}
		}
		if (key == InputCompat.KEY_LEFT_BRACKET) {
			brush(-1);
		} else if (key == InputCompat.KEY_RIGHT_BRACKET) {
			brush(1);
		} else if (letter == InputCompat.KEY_M) {
			settings.mirror(!settings.mirror());
			rebuildWidgets();
		} else if (letter == InputCompat.KEY_G) {
			toggleGrid();
		} else if (letter == InputCompat.KEY_O) {
			toggleShell();
		} else if (key == InputCompat.KEY_TAB) {
			toggleFlat();
		} else if (letter == InputCompat.KEY_R) {
			resetView();
		} else if (letter == InputCompat.KEY_X) {
			swapColors();
		} else {
			return super.keyPressed(event);
		}
		return true;
	}

	@Override
	public void tick() {
		if (!closing && floating == null && document.dirty() && (saving == null || saving.isDone())
			&& System.nanoTime() - lastAutosave > 60_000_000_000L) {
			saveAsync();
			if (saving != null) {
				status = tr("mcskins.status.autosaving");
			}
		}
	}

	// ---- drawing ------------------------------------------------------------------------------------------

	private double flatScale() {
		return Math.max(1, Math.min(viewWidth - 8, viewHeight - 8) / 64.0) * flatZoom;
	}

	private double flatLeft() {
		return viewX + (viewWidth - 64 * flatScale()) / 2 + flatPanX;
	}

	private double flatTop() {
		return viewY + (viewHeight - 64 * flatScale()) / 2 + flatPanY;
	}

	private BitSet selectionShown() {
		if (selectStart >= 0) {
			BitSet rectangle = selectionRectangle();
			if (selectAdd && selection != null) {
				rectangle.or(selection);
			}
			return rectangle;
		}
		if (floating != null) {
			return floating.canvasMask();
		}
		return selection;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (textureDirty) {
			refresh();
		}
		hoverPixel = onView(mouseX, mouseY) && cameraButton < 0 ? pickPixel(mouseX, mouseY) : -1;
		BitSet hover = shapeStart >= 0 ? shapePixels() : tool.selecting() ? new BitSet() : hoverFootprint(hoverPixel);
		BitSet chosen = selectionShown();
		graphics.enableScissor(viewX, viewY, viewX + viewWidth, viewY + viewHeight);
		if (flat) {
			drawFlat(graphics, hover, chosen);
		} else {
			BitSet highlight = hover;
			if (chosen != null || problems != null) {
				highlight = (BitSet) hover.clone();
				if (chosen != null) {
					highlight.or(chosen);
				}
				if (problems != null) {
					highlight.or(problems);
				}
			}
			preview.highlight(highlight);
			preview.render(graphics);
		}
		graphics.disableScissor();
		caption(graphics, leftX, 26, 112, tr("mcskins.tools"));
		caption(graphics, leftX, layerTop, 112, tr("mcskins.layers", document.layers().size(), SkinDocument.MAX_LAYERS));
		caption(graphics, rightX, 26, 152, tr("mcskins.color"));
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		String name = document.name() + (document.dirty() || floating != null ? " •" : "");
		centered(graphics, name, (130 + width - 206) / 2, 9, 0xFFFFFFFF, Math.max(0, width - 206 - 130));
		if (tool.sized()) {
			centered(graphics, tr("mcskins.brush.size", settings.brushSize()), leftX + 56, sizeRowY + 4, MUTED, 70);
		} else {
			centered(graphics, tr("mcskins.brush.size", "–"), leftX + 56, sizeRowY + 4, 0xFF5A6068, 70);
		}
		if (tool == Tool.SHADE) {
			centered(graphics, settings.shadeStrength() * 5 + "%", leftX + 83, optionRowY + 4, MUTED, 24);
		} else if (tool == Tool.REPLACE) {
			centered(graphics, "±" + settings.replaceTolerance(), leftX + 83, optionRowY + 4, MUTED, 24);
		} else if (tool.selecting()) {
			text(graphics, tr("mcskins.selection.hint"), leftX + 4, optionRowY + 4, MUTED, 106);
		}
		drawLayers(graphics);
		drawColor(graphics);
		drawInfo(graphics, mouseX, mouseY);
	}

	private void drawInfo(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (status.isEmpty()) {
			String hint = tr(tool.selecting() ? "mcskins.hint.select" : flat ? "mcskins.hint.flat" : "mcskins.hint.model");
			text(graphics, hint, viewX + 4, height - 13, MUTED, viewWidth - 8);
		}
		if (hoverPixel >= 0) {
			int x = hoverPixel & 63;
			int y = hoverPixel >>> 6;
			SkinGeometry.Face face = geometry().faceAt(x, y);
			String where = face == null ? "" : " · " + tr("mcskins.part." + face.box().part().name().toLowerCase(Locale.ROOT))
				+ " " + tr("mcskins.side." + face.side().name().toLowerCase(Locale.ROOT))
				+ (face.box().outer() ? " (" + tr("mcskins.shell.outer") + ")" : "");
			String info = x + ", " + y + where;
			int infoWidth = font.width(info);
			graphics.text(font, info, viewX + viewWidth - infoWidth - 4, viewY + viewHeight - 11, 0xFFFFFFFF, true);
		}
	}

	private void drawFlat(GuiGraphicsExtractor graphics, BitSet highlight, BitSet chosen) {
		double scale = flatScale();
		double left = flatLeft();
		double top = flatTop();
		SkinGeometry geometry = geometry();
		for (int row = 0; row < 64; row++) {
			for (int column = 0; column < 64; column++) {
				int index = row * 64 + column;
				SkinGeometry.Face face = geometry.faceAt(column, row);
				int checker = ((row + column) & 1) == 0 ? 0x50000000 : 0x30000000;
				int background = face == null ? 0x00000000 : checker;
				int pixel = pixels[index];
				int shown = pixel >>> 24 == 0 ? background : pixel >>> 24 == 255 ? pixel : SkinPixels.over(pixel, 0xFF000000 | background);
				if (face != null && face.box().outer() != overlayTarget && shown >>> 24 == 255) {
					shown = SkinPixels.over(0x70000000, shown);
				}
				if (chosen != null && chosen.get(index)) {
					shown = SkinPixels.over(SELECTION_TINT, shown >>> 24 == 0 ? 0xFF202020 : shown);
				}
				if (problems != null && problems.get(index)) {
					shown = SkinPixels.over(PROBLEM_TINT, shown >>> 24 == 0 ? 0xFF000000 : shown);
				}
				if (highlight.get(index)) {
					shown = SkinPixels.over(0x80FFFFFF, shown);
				}
				flatPixels[index] = shown;
			}
		}
		flatTexture.set(flatPixels, 64, 64);
		int canvasLeft = (int) Math.floor(left);
		int canvasTop = (int) Math.floor(top);
		int canvasWidth = (int) Math.floor(left + 64 * scale) - canvasLeft;
		int canvasHeight = (int) Math.floor(top + 64 * scale) - canvasTop;
		flatTexture.draw(graphics, canvasLeft, canvasTop, canvasWidth, canvasHeight);
		if (reference != null && referenceShown) {
			if (referenceDirty) {
				int[] faded = new int[reference.length];
				for (int index = 0; index < reference.length; index++) {
					int alpha = (reference[index] >>> 24) * referenceOpacity / 255;
					faded[index] = alpha << 24 | reference[index] & 0xFFFFFF;
				}
				referenceTexture.set(faded, 256, 256);
				referenceDirty = false;
			}
			referenceTexture.draw(graphics, canvasLeft, canvasTop, canvasWidth, canvasHeight);
		}
		if (settings.grid() && scale >= 6) {
			for (int index = 0; index <= 64; index++) {
				int x = (int) Math.floor(left + index * scale);
				int y = (int) Math.floor(top + index * scale);
				graphics.fill(x, (int) top, x + 1, (int) (top + 64 * scale), 0x30000000);
				graphics.fill((int) left, y, (int) (left + 64 * scale), y + 1, 0x30000000);
			}
		}
		for (SkinGeometry.Face face : geometry.faces()) {
			int x0 = (int) Math.floor(left + face.u() * scale);
			int y0 = (int) Math.floor(top + face.v() * scale);
			int x1 = (int) Math.floor(left + (face.u() + face.columns()) * scale);
			int y1 = (int) Math.floor(top + (face.v() + face.rows()) * scale);
			graphics.outline(x0, y0, x1 - x0, y1 - y0, face.box().outer() == overlayTarget ? 0x60FFFFFF : 0x28FFFFFF);
		}
		if (shapeStart >= 0 && tool == Tool.GRADIENT) {
			int x0 = (int) (left + ((shapeStart & 63) + 0.5) * scale);
			int y0 = (int) (top + ((shapeStart >>> 6) + 0.5) * scale);
			graphics.fill(x0 - 1, y0 - 1, x0 + 2, y0 + 2, color | 0xFF000000);
		}
	}

	private void drawLayers(GuiGraphicsExtractor graphics) {
		int top = layerTop + 12;
		int bottom = layerListBottom();
		var layers = document.layers();
		List<Row> rows = rows();
		int visible = bottom - top;
		int content = rows.size() * LAYER_ROW;
		scrollLayers = Math.clamp(scrollLayers, 0, Math.max(0, content - visible));
		graphics.enableScissor(leftX + 1, top, leftX + 111, bottom);
		for (int position = 0; position < rows.size(); position++) {
			Row row = rows.get(position);
			int y = top - scrollLayers + position * LAYER_ROW;
			if (y + LAYER_ROW < top || y > bottom) {
				continue;
			}
			if (row.layer() < 0) {
				LayerGroup group = document.group(row.group());
				if (group == null) {
					continue;
				}
				graphics.fill(leftX + 1, y, leftX + 111, y + LAYER_ROW - 1, 0x40000000);
				text(graphics, group.collapsed() ? "▸" : "▾", leftX + 3, y + 5, 0xFFE0E0E0, 10);
				text(graphics, group.visible() ? "●" : "○", leftX + 13, y + 5, group.visible() ? 0xFFE0E0E0 : 0xFF6F7780, 10);
				text(graphics, group.name(), leftX + 25, y + 5, 0xFFFFE6A0, 60);
				if (group.opacity() < 255) {
					String percent = Math.round(group.opacity() * 100 / 255.0) + "%";
					text(graphics, percent, leftX + 108 - font.width(percent), y + 5, MUTED, 30);
				}
				continue;
			}
			int index = row.layer();
			SkinLayer layer = layers.get(index);
			int indent = row.group() != 0 ? 4 : 0;
			boolean active = index == document.activeLayer();
			if (active) {
				graphics.outline(leftX + 1, y, 110, LAYER_ROW - 1, 0xFFFFFFFF);
			}
			if (indent > 0) {
				graphics.fill(leftX + 1, y, leftX + 3, y + LAYER_ROW - 1, 0xFFFFE6A0);
			}
			text(graphics, layer.visible() ? "●" : "○", leftX + 5, y + 5, layer.visible() ? 0xFFE0E0E0 : 0xFF6F7780, 10);
			SkinPixels.head(graphics, layer.pixels(), leftX + 16, y + 1, 2);
			String marks = (layer.blend() != BlendMode.NORMAL ? tr("mcskins.blend.short." + layer.blend().name().toLowerCase(Locale.ROOT)) : "")
				+ (layer.alphaLock() ? "α" : "");
			String percent = layer.opacity() < 255 ? Math.round(layer.opacity() * 100 / 255.0) + "%" : "";
			String right = (marks + " " + percent).strip();
			int rightWidth = right.isEmpty() ? 0 : font.width(right) + 2;
			text(graphics, layer.name(), leftX + 36, y + 5, active ? 0xFFFFFFFF : MUTED, Math.max(10, 72 - rightWidth));
			if (!right.isEmpty()) {
				text(graphics, right, leftX + 108 - font.width(right), y + 5, MUTED, 40);
			}
		}
		graphics.disableScissor();
		int opacityY = bottom + 2;
		int opacity = layers.get(document.activeLayer()).opacity();
		graphics.fill(leftX + 4, opacityY + 3, leftX + 108, opacityY + 7, 0x80000000);
		graphics.fill(leftX + 4, opacityY + 3, leftX + 4 + opacity * 104 / 255, opacityY + 7, ACCENT);
		int knob = leftX + 4 + opacity * 104 / 255;
		graphics.fill(knob - 1, opacityY + 1, knob + 2, opacityY + 9, 0xFFFFFFFF);
		if (content > visible) {
			int track = visible - 4;
			int thumb = Math.max(10, track * visible / content);
			int thumbTop = top + 2 + (track - thumb) * scrollLayers / Math.max(1, content - visible);
			graphics.fill(leftX + 108, thumbTop, leftX + 110, thumbTop + thumb, 0xFF6F7780);
		}
	}

	private void drawColor(GuiGraphicsExtractor graphics) {
		int left = rightX + 6;
		checker(graphics, left, 40, 64, 16);
		graphics.fill(left, 40, left + 32, 56, previousColor);
		graphics.fill(left + 32, 40, left + 64, 56, color);
		graphics.outline(left - 1, 39, 66, 18, BORDER);
		text(graphics, String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF), left + 70, 41, 0xFFE0E0E0, 70);
		text(graphics, tr("mcskins.color.alpha", Math.round((color >>> 24) * 100 / 255.0)), left + 70, 50, MUTED, 70);
		int top = svTop();
		boolean wheel = settings.colorWheel();
		int cells = Math.max(2, wheel ? svSize : svSize / 3);
		if (wheel ? value != fieldValue || cells != fieldCells || !fieldWheel : hue != fieldHue || cells != fieldCells || fieldWheel) {
			int[] field = new int[cells * cells];
			for (int row = 0; row < cells; row++) {
				for (int column = 0; column < cells; column++) {
					if (wheel) {
						double dx = (column + 0.5) / cells * 2 - 1;
						double dy = (row + 0.5) / cells * 2 - 1;
						double radius = Math.sqrt(dx * dx + dy * dy);
						double angle = Math.atan2(dy, dx) / (Math.PI * 2);
						field[row * cells + column] = radius > 1 ? 0
							: ColorOps.fromHsv((float) (angle < 0 ? angle + 1 : angle), (float) radius, value, 0xFF000000);
					} else {
						field[row * cells + column] = ColorOps.fromHsv(hue, column / (float) (cells - 1), 1 - row / (float) (cells - 1), 0xFF000000);
					}
				}
			}
			svTexture.set(field, cells, cells);
			fieldHue = hue;
			fieldValue = value;
			fieldCells = cells;
			fieldWheel = wheel;
		}
		svTexture.draw(graphics, left, top, svSize, svSize);
		if (barSize != svSize || barWheel != wheel || wheel && (barHue != hue || barSaturation != saturation)) {
			int[] bar = new int[svSize];
			for (int row = 0; row < svSize; row++) {
				bar[row] = wheel ? ColorOps.fromHsv(hue, saturation, 1 - row / (float) Math.max(1, svSize - 1), 0xFF000000)
					: ColorOps.fromHsv(row / (float) svSize, 1, 1, 0xFF000000);
			}
			hueTexture.set(bar, 1, svSize);
			barSize = svSize;
			barWheel = wheel;
			barHue = hue;
			barSaturation = saturation;
		}
		hueTexture.draw(graphics, left + svSize + 4, top, 12, svSize);
		if (!wheel) {
			graphics.outline(left - 1, top - 1, svSize + 2, svSize + 2, BORDER);
		}
		graphics.outline(left + svSize + 3, top - 1, 14, svSize + 2, BORDER);
		int knobX;
		int knobY;
		if (wheel) {
			double angle = hue * Math.PI * 2;
			knobX = left + svSize / 2 + (int) Math.round(Math.cos(angle) * saturation * (svSize / 2.0 - 1));
			knobY = top + svSize / 2 + (int) Math.round(Math.sin(angle) * saturation * (svSize / 2.0 - 1));
		} else {
			knobX = left + Math.round(saturation * (svSize - 1));
			knobY = top + Math.round((1 - value) * (svSize - 1));
		}
		graphics.outline(knobX - 2, knobY - 2, 5, 5, 0xFF000000);
		graphics.outline(knobX - 1, knobY - 1, 3, 3, 0xFFFFFFFF);
		int barY = top + Math.round((wheel ? 1 - value : hue) * (svSize - 1));
		graphics.fill(left + svSize + 2, barY - 1, left + svSize + 18, barY + 2, 0xFFFFFFFF);
		int alphaY = alphaTop();
		int alphaWidth = svSize + 16;
		checker(graphics, left, alphaY, alphaWidth, 8);
		for (int column = 0; column < alphaWidth; column += 2) {
			int alpha = column * 255 / Math.max(1, alphaWidth - 1);
			graphics.fill(left + column, alphaY, left + column + 2, alphaY + 8, alpha << 24 | color & 0xFFFFFF);
		}
		int alphaKnob = left + (color >>> 24) * (alphaWidth - 1) / 255;
		graphics.fill(alphaKnob - 1, alphaY - 1, alphaKnob + 2, alphaY + 9, 0xFFFFFFFF);
		graphics.outline(left - 1, alphaY - 1, alphaWidth + 2, 10, BORDER);

		int[] swatches = swatches();
		int recentCount = swatchRecentCount;
		int origin = paletteTop + 10 - scrollPalette;
		graphics.enableScissor(rightX + 1, paletteTop, rightX + 151, height - 17);
		text(graphics, tr(recentCount > 0 ? "mcskins.color.recent" : settings.palette().isEmpty() ? "mcskins.color.palette" : "mcskins.color.custom_palette"),
			left, paletteTop - scrollPalette, MUTED, 140);
		for (int index = 0; index < swatches.length; index++) {
			int x = left + index % 8 * 18;
			int y = origin + index / 8 * 14;
			if (index < recentCount || index >= (recentCount + 7) / 8 * 8) {
				checker(graphics, x, y, 16, 12);
				graphics.fill(x, y, x + 16, y + 12, swatches[index]);
				if (swatches[index] == color) {
					graphics.outline(x - 1, y - 1, 18, 14, 0xFFFFFFFF);
				}
			}
		}
		graphics.disableScissor();
	}

	private static void checker(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		for (int row = 0; row < height; row += 4) {
			for (int column = 0; column < width; column += 4) {
				graphics.fill(x + column, y + row, Math.min(x + width, x + column + 4), Math.min(y + height, y + row + 4),
					((row + column) / 4 & 1) == 0 ? 0xFF8A8A8A : 0xFF5A5A5A);
			}
		}
	}

	@Override
	public void removed() {
		endInteraction();
		preview.close();
		flatTexture.close();
		svTexture.close();
		hueTexture.close();
		referenceTexture.close();
		referenceDirty = true;
		fieldHue = Float.NaN;
		fieldValue = Float.NaN;
		barSize = 0;
		settings.flush();
		super.removed();
	}

	@Override
	public void onClose() {
		commitFloating();
		endInteraction();
		if (!document.dirty()) {
			super.onClose();
			return;
		}
		GameCompat.setScreen(minecraft, new SkinScreen("mcskins.unsaved.title", this) {
			@Override
			protected void init() {
				int x = (width - 240) / 2;
				int y = height / 2 - 30;
				Button[] save = new Button[1];
				save[0] = button(tr("mcskins.unsaved.save"), x, y, 240, () -> SkinEditorScreen.this.saveAndLeave(this, save[0]));
				save[0].active = !closing;
				setInitialFocus(save[0]);
				button(tr("mcskins.unsaved.discard"), x, y + 24, 240, () -> GameCompat.setScreen(minecraft, SkinEditorScreen.this.parent));
				button(tr("mcskins.unsaved.keep"), x, y + 48, 240, this::onClose);
			}

			@Override
			public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
				super.extractRenderState(graphics, mouseX, mouseY, delta);
				centered(graphics, tr("mcskins.unsaved.message", document.name()), width / 2, height / 2 - 48, MUTED, width - 20);
			}
		});
	}
}
