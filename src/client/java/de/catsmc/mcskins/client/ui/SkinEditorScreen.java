package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.compat.InputCompat;
import de.catsmc.mcskins.client.preview.PreviewSession;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

final class SkinEditorScreen extends SkinScreen {
	private enum Tool { PAINT, ERASE, PICK, FILL }

	private static final int[] BASIC = {0xFF000000, 0xFF7F0000, 0xFF007F00, 0xFF7F7F00, 0xFF00007F, 0xFF7F007F, 0xFF007F7F, 0xFF808080,
		0xFF404040, 0xFFC00000, 0xFF00C000, 0xFFC0C000, 0xFF0000C0, 0xFFC000C0, 0xFF00C0C0, 0xFFC0C0C0,
		0xFF808080, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF0000FF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
		0xFF400000, 0xFF804000, 0xFF808000, 0xFF408000, 0xFF004040, 0xFF404080, 0xFF800080, 0xFF804080,
		0xFFFF8080, 0xFFFFB080, 0xFFFFFF80, 0xFFB0FFB0, 0xFF80FFFF, 0xFFB0B0FF, 0xFFFF80FF, 0xFFD8B0B0,
		0xFF603010, 0xFF805030, 0xFFA08050, 0xFF507030, 0xFF305050, 0xFF405070, 0xFF705060, 0xFF505050};

	private final SkinRepository repository;
	private final SkinDocument document;
	private final SkinModelPreview preview = new SkinModelPreview();
	private final int[] custom = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
		0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
	private int customNext;
	private Tool tool = Tool.PAINT;
	private int color = 0xFFB98868;
	private float hue = 0.07F;
	private float saturation = 0.45F;
	private float value = 0.73F;
	private int previousColor = 0xFFB98868;
	private int[] pixels;
	private int canvasX = 122;
	private int canvasY = 34;
	private int canvasWidth = 1;
	private int canvasHeight = 1;
	private int dockRight;
	private int cameraButton = -1;
	private boolean painting;
	private boolean svDrag;
	private boolean hueDrag;
	private boolean layerDrag;
	private int layerGrab;
	private boolean colorDrag;
	private int colorGrab;
	private double previousMouseX;
	private double previousMouseY;
	private int scrollLayers;
	private int scrollColor;
	private int svSize = 116;
	private long lastAutosave = System.nanoTime();
	private boolean syncFields;
	private EditBox redBox;
	private EditBox greenBox;
	private EditBox blueBox;
	private EditBox hexBox;

	SkinEditorScreen(Screen parent, SkinRepository repository, SkinDocument document) {
		super("Skin Editor", parent);
		this.repository = repository;
		this.document = document;
		int rgb = color;
		float[] hsv = rgbToHsv(rgb);
		hue = hsv[0];
		saturation = hsv[1];
		value = hsv[2];
		refresh();
	}

	@Override
	protected boolean showTitle() {
		return false;
	}

	@Override
	protected void init() {
		endInteraction();
		refresh();
		dockRight = width - 164;
		svSize = (int) Math.clamp((height - 300) * 0.22, 80, 118);
		canvasX = 122;
		canvasY = 34;
		canvasWidth = Math.max(1, dockRight - 6 - canvasX);
		canvasHeight = Math.max(1, height - canvasY - 26);
		preview.viewport(canvasX, canvasY, canvasWidth, canvasHeight);
		button("Back", 4, 6, 38, this::onClose);
		button("Save", 46, 6, 38, () -> { save(); rebuildWidgets(); });
		button("Pose", 88, 6, 58, () -> {
			var poses = SkinModelPreview.Pose.values();
			preview.pose(poses[(preview.pose().ordinal() + 1) % poses.length]);
			rebuildWidgets();
		});
		button("Anim", 150, 6, 64, () -> {
			var animations = SkinModelPreview.Animation.values();
			preview.animate(animations[(preview.animation().ordinal() + 1) % animations.length]);
			rebuildWidgets();
		});
		button("View", 218, 6, 58, () -> GameCompat.setScreen(minecraft, new ViewPanel(this)));
		String[] labels = {"Brush", "Erase", "Pick", "Fill"};
		for (int index = 0; index < Tool.values().length; index++) {
			Tool selected = Tool.values()[index];
			button(labels[index], 6 + index % 2 * 55, 48 + index / 2 * 23, 52, () -> select(selected)).active = tool != selected;
		}
		button("←", 6, 94, 52, () -> { change(document::undo); rebuildWidgets(); }).active = document.canUndo();
		button("→", 61, 94, 52, () -> { change(document::redo); rebuildWidgets(); }).active = document.canRedo();
		button("Export PNG", 6, 134, 105, () -> {
			try {
				status = "Exported: " + repository.export(document);
			} catch (IOException | RuntimeException exception) {
				failure(exception);
			}
		});
		button("Test World", 6, 157, 105, () -> {
			endInteraction();
			PreviewSession.start(this, document, message -> status = message);
		}).active = PreviewSession.canStart();
		button("Reset View", 6, 180, 105, preview::reset);
		int actionY = height - 48;
		button("Add", 6, actionY, 24, () -> { change(() -> document.addLayer("Layer " + (document.layers().size() + 1))); rebuildWidgets(); }).active = document.layers().size() < SkinDocument.MAX_LAYERS;
		button("Del", 33, actionY, 24, () -> { change(document::removeLayer); rebuildWidgets(); }).active = document.layers().size() > 1;
		button("Up", 60, actionY, 24, () -> { change(() -> document.moveLayer(1)); rebuildWidgets(); }).active = document.activeLayer() < document.layers().size() - 1;
		button("Dn", 87, actionY, 24, () -> { change(() -> document.moveLayer(-1)); rebuildWidgets(); }).active = document.activeLayer() > 0;
		int footer = height - 96;
		redBox = numberBox("Red 0-255", (color >>> 16) & 255, dockRight + 22, footer);
		greenBox = numberBox("Green 0-255", (color >>> 8) & 255, dockRight + 73, footer);
		blueBox = numberBox("Blue 0-255", color & 255, dockRight + 124, footer);
		redBox.setResponder(text -> channel(16, text));
		greenBox.setResponder(text -> channel(8, text));
		blueBox.setResponder(text -> channel(0, text));
		hexBox = field("Hex RRGGBB or AARRGGBB", String.format(Locale.ROOT, "%08X", color), dockRight + 8, footer + 24, 76, 8);
		hexBox.setResponder(text -> {
			if (syncFields) {
				return;
			}
			String cleaned = text.strip().replaceFirst("^#", "");
			if (!cleaned.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
				return;
			}
			int parsed = (int) Long.parseLong(cleaned, 16);
			if (cleaned.length() == 6) {
				parsed |= 0xFF000000;
			}
			setColor(parsed);
		});
		button("Add", dockRight + 88, footer + 24, 64, () -> {
			for (int index = 0; index < custom.length; index++) {
				int slot = (customNext + index) % custom.length;
				if (custom[slot] == 0xFFFFFFFF) {
					custom[slot] = color;
					customNext = (slot + 1) % custom.length;
					return;
				}
			}
			custom[customNext] = color;
			customNext = (customNext + 1) % custom.length;
		}).visible = height >= 400;
		redBox.visible = height >= 400;
		greenBox.visible = height >= 400;
		blueBox.visible = height >= 400;
		hexBox.visible = height >= 400;
		syncFields();
	}

	private EditBox numberBox(String label, int value, int x, int y) {
		EditBox box = field(label, Integer.toString(value), x, y, 36, 3);
		return box;
	}

	private void channel(int shift, String text) {
		if (syncFields) {
			return;
		}
		String cleaned = text.strip();
		if (!cleaned.matches("[0-9]{1,3}")) {
			return;
		}
		int channel = Integer.parseInt(cleaned);
		if (channel > 255) {
			return;
		}
		setColor(color & ~(255 << shift) | channel << shift);
	}

	private void syncFields() {
		syncFields = true;
		redBox.setValue(Integer.toString(color >>> 16 & 255));
		greenBox.setValue(Integer.toString(color >>> 8 & 255));
		blueBox.setValue(Integer.toString(color & 255));
		hexBox.setValue(String.format(Locale.ROOT, "%08X", color));
		syncFields = false;
	}

	private void setColor(int rgb) {
		color = rgb;
		float[] hsv = rgbToHsv(rgb);
		hue = hsv[0];
		saturation = hsv[1];
		value = hsv[2];
		syncFields();
	}

	private static float[] rgbToHsv(int rgb) {
		float red = ((rgb >>> 16) & 255) / 255F;
		float green = ((rgb >>> 8) & 255) / 255F;
		float blue = (rgb & 255) / 255F;
		float maximum = Math.max(red, Math.max(green, blue));
		float minimum = Math.min(red, Math.min(green, blue));
		float delta = maximum - minimum;
		float hue = 0;
		if (delta != 0) {
			if (maximum == red) {
				hue = (green - blue) / delta / 6;
			} else if (maximum == green) {
				hue = (blue - red) / delta / 6 + 0.33333334F;
			} else {
				hue = (red - green) / delta / 6 + 0.6666667F;
			}
			if (hue < 0) {
				hue++;
			}
		}
		return new float[]{hue, maximum == 0 ? 0 : delta / maximum, maximum};
	}

	private static int hsvToRgb(float hue, float saturation, float value, int alpha) {
		float sector = (hue - (float) Math.floor(hue)) * 6;
		int face = (int) sector;
		float fraction = sector - face;
		float low = value * (1 - saturation);
		float midLow = value * (1 - saturation * fraction);
		float midHigh = value * (1 - saturation * (1 - fraction));
		float red = value;
		float green = value;
		float blue = value;
		switch (face) {
			case 1 -> { red = midLow; blue = low; }
			case 2 -> { red = low; blue = midHigh; }
			case 3 -> { red = low; green = midLow; }
			case 4 -> { red = midHigh; green = low; }
			case 5 -> { green = low; blue = midLow; }
			default -> { green = midHigh; blue = low; }
		}
		return alpha & 0xFF000000 | Math.round(red * 255) << 16 | Math.round(green * 255) << 8 | Math.round(blue * 255);
	}

	private int layerListTop() {
		return 218;
	}

	private int layerListBottom() {
		return height - 54;
	}

	private int layerContentHeight() {
		return document.layers().size() * 24;
	}

	private int colorScrollTop() {
		return 78 + svSize + 6;
	}

	private int colorScrollBottom() {
		return height - 104;
	}

	private int colorContentHeight() {
		return 144;
	}

	private boolean colorGridVisible() {
		return colorScrollBottom() > colorScrollTop();
	}

	private int colorOrigin() {
		int top = colorScrollTop();
		int bottom = colorScrollBottom();
		int flow = bottom - colorContentHeight();
		if (flow >= top) {
			return flow;
		}
		scrollColor = clampScroll(scrollColor, colorContentHeight(), Math.max(1, bottom - top));
		return top - scrollColor;
	}

	private boolean colorScrolling() {
		return colorGridVisible() && colorScrollTop() > height - 104 - colorContentHeight();
	}

	private int clampScroll(int offset, int content, int visible) {
		return Math.clamp(offset, 0, Math.max(0, content - visible));
	}

	private void select(Tool selected) {
		endInteraction();
		tool = selected;
		rebuildWidgets();
	}

	private void refresh() {
		pixels = document.flatten();
		preview.texture(pixels, document.slim());
	}

	private void change(Runnable operation) {
		endInteraction();
		try {
			operation.run();
			refresh();
		} catch (RuntimeException exception) {
			failure(exception);
		}
	}

	private void strokeOp(Runnable operation) {
		try {
			operation.run();
			refresh();
		} catch (RuntimeException exception) {
			endInteraction();
			failure(exception);
		}
	}

	private boolean save() {
		endInteraction();
		try {
			repository.save(document);
			status = "Saved " + document.name() + " locally.";
			return true;
		} catch (IOException | RuntimeException exception) {
			failure(exception);
			return false;
		}
	}

	private void endInteraction() {
		painting = false;
		cameraButton = -1;
		svDrag = false;
		hueDrag = false;
		layerDrag = false;
		colorDrag = false;
		document.endStroke();
		preview.freeze(false);
	}

	private boolean onCanvas(double x, double y) {
		return x >= canvasX && y >= canvasY && x < canvasX + canvasWidth && y < canvasY + canvasHeight;
	}

	private boolean onLayers(double x, double y) {
		return x >= 6 && y >= layerListTop() && x < 111 && y < layerListBottom();
	}

	private boolean onColorScroll(double x, double y) {
		return colorGridVisible() && x >= dockRight && y >= colorScrollTop() && x < width - 4 && y < colorScrollBottom();
	}

	private void apply(SkinModelPreview.Hit hit) {
		if (hit == null) {
			return;
		}
		switch (tool) {
			case PAINT -> document.paint(hit.x(), hit.y(), color);
			case ERASE -> document.paint(hit.x(), hit.y(), 0);
			case PICK -> setColor(document.sample(hit.x(), hit.y()));
			case FILL -> fillFace(hit);
		}
	}

	private void fillFace(SkinModelPreview.Hit hit) {
		var layer = document.layers().get(document.activeLayer());
		int[] local = layer.pixels();
		int[] image = new int[4096];
		for (int row = 0; row < 64; row++) {
			for (int column = 0; column < 64; column++) {
				int x = column - layer.offsetX();
				int row2 = row - layer.offsetY();
				image[row * 64 + column] = x >= 0 && x < 64 && row2 >= 0 && row2 < 64 ? local[row2 * 64 + x] : 0;
			}
		}
		int target = image[hit.y() * 64 + hit.x()];
		if (target == color) {
			return;
		}
		boolean[] visited = new boolean[4096];
		var queue = new ArrayDeque<Integer>();
		queue.add(hit.y() * 64 + hit.x());
		while (!queue.isEmpty()) {
			int index = queue.removeFirst();
			int x = index % 64;
			int row = index / 64;
			if (visited[index] || image[index] != target || x < hit.x0() || x >= hit.x0() + hit.w()
				|| row < hit.y0() || row >= hit.y0() + hit.h()) {
				continue;
			}
			visited[index] = true;
			document.paint(x, row, color);
			if (x > 0) {
				queue.add(index - 1);
			}
			if (x < 63) {
				queue.add(index + 1);
			}
			if (row > 0) {
				queue.add(index - 64);
			}
			if (row < 63) {
				queue.add(index + 64);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (pressColor(event.x(), event.y())) {
			return true;
		}
		if (pressLayers(event.x(), event.y())) {
			return true;
		}
		if (!onCanvas(event.x(), event.y())) {
			endInteraction();
			return super.mouseClicked(event, doubleClick);
		}
		clearFocus();
		if (event.button() == InputCompat.MOUSE_BUTTON_RIGHT || event.button() == InputCompat.MOUSE_BUTTON_MIDDLE) {
			cameraButton = event.button();
			preview.hover(Double.NaN, Double.NaN);
			return true;
		}
		if (event.button() != InputCompat.MOUSE_BUTTON_LEFT) {
			return false;
		}
		SkinModelPreview.Hit target = preview.pick(event.x(), event.y());
		if (target == null) {
			status = "No editable pixel on this shell here.";
			return true;
		}
		if (tool != Tool.PICK && !document.layers().get(document.activeLayer()).visible()) {
			status = "Selected layer is hidden. Enable its eye in Layers.";
			return true;
		}
		previousColor = color;
		document.beginStroke();
		painting = tool == Tool.PAINT || tool == Tool.ERASE;
		preview.freeze(painting);
		previousMouseX = event.x();
		previousMouseY = event.y();
		try {
			apply(target);
			refresh();
		} catch (RuntimeException exception) {
			endInteraction();
			failure(exception);
		}
		if (!painting) {
			document.endStroke();
		}
		return true;
	}

	private boolean pressColor(double x, double y) {
		int inner = dockRight + 8;
		if (x >= inner && y >= 78 && x < inner + svSize && y < 78 + svSize) {
			hueDrag = false;
			svDrag = true;
			previousColor = color;
			dragSaturation(x, y);
			return true;
		}
		if (x >= inner + svSize + 4 && y >= 78 && x < inner + svSize + 18 && y < 78 + svSize) {
			svDrag = false;
			hueDrag = true;
			previousColor = color;
			dragHue(y);
			return true;
		}
		if (!colorGridVisible() || y < colorScrollTop() || y >= colorScrollBottom() || x < dockRight || x >= width - 4) {
			return false;
		}
		int origin = colorOrigin();
		int rel = (int) (y - origin);
		if (rel >= 12 && rel < 96 && x >= inner) {
			int column = (int) Math.floor((x - inner) / 14.0);
			int gridRow = (rel - 12) / 14;
			if (column >= 0 && column < 8 && (x - inner) % 14 < 12 && (rel - 12) % 14 < 12) {
				previousColor = color;
				setColor(BASIC[gridRow * 8 + column]);
				return true;
			}
		}
		if (rel >= 112 && rel < 140 && x >= inner) {
			int column = (int) Math.floor((x - inner) / 14.0);
			int gridRow = (rel - 112) / 14;
			if (column >= 0 && column < 8 && (x - inner) % 14 < 12 && (rel - 112) % 14 < 12) {
				previousColor = color;
				setColor(custom[gridRow * 8 + column]);
				return true;
			}
		}
		int track = width - 10;
		if (x >= track - 2 && x < width - 4 && colorScrolling()) {
			colorDrag = true;
			colorGrab = (int) y - thumbTop(colorScrollTop(), colorScrollBottom() - colorScrollTop(), colorContentHeight(), scrollColor);
			return true;
		}
		return onColorScroll(x, y);
	}

	private boolean pressLayers(double x, double y) {
		if (!onLayers(x, y)) {
			return false;
		}
		int visible = layerListBottom() - layerListTop();
		if (x >= 103 && x < 111 && layerContentHeight() > visible) {
			layerDrag = true;
			layerGrab = (int) y - thumbTop(layerListTop(), visible, layerContentHeight(), scrollLayers);
			return true;
		}
		int index = document.layers().size() - 1 - ((int) (y - layerListTop()) + scrollLayers) / 24;
		if (index < 0 || index >= document.layers().size()) {
			return true;
		}
		if (x < 24) {
			change(() -> document.setLayerVisible(index, !document.layers().get(index).visible()));
		} else {
			change(() -> document.selectLayer(index));
		}
		rebuildWidgets();
		return true;
	}

	private int thumbTop(int top, int visible, int content, int offset) {
		int track = visible - 8;
		int thumb = Math.max(12, track * visible / content);
		return top + 4 + (track - thumb) * offset / Math.max(1, content - visible);
	}

	private void dragSaturation(double x, double y) {
		int inner = dockRight + 8;
		saturation = (float) Math.clamp((x - inner) / Math.max(1, svSize - 1), 0, 1);
		value = (float) Math.clamp(1 - (y - 78) / Math.max(1, svSize - 1), 0, 1);
		color = hsvToRgb(hue, saturation, value, color);
		syncFields();
	}

	private void dragHue(double y) {
		hue = (float) Math.clamp((y - 78) / Math.max(1, svSize - 2), 0, 1);
		color = hsvToRgb(hue, saturation, value, color);
		syncFields();
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (svDrag) {
			dragSaturation(event.x(), event.y());
			return true;
		}
		if (hueDrag) {
			dragHue(event.y());
			return true;
		}
		if (layerDrag) {
			int visible = layerListBottom() - layerListTop();
			int track = visible - 8;
			int thumb = Math.max(12, track * visible / layerContentHeight());
			scrollLayers = clampScroll((int) ((event.y() - layerGrab - layerListTop() - 4) * (layerContentHeight() - visible) / Math.max(1, track - thumb)), layerContentHeight(), visible);
			return true;
		}
		if (colorDrag) {
			int visible = colorScrollBottom() - colorScrollTop();
			int track = visible - 8;
			int thumb = Math.max(12, track * visible / colorContentHeight());
			scrollColor = clampScroll((int) ((event.y() - colorGrab - colorScrollTop() - 4) * (colorContentHeight() - visible) / Math.max(1, track - thumb)), colorContentHeight(), visible);
			return true;
		}
		if (event.button() == cameraButton && cameraButton > 0) {
			if (cameraButton == InputCompat.MOUSE_BUTTON_RIGHT) {
				preview.orbit(dx, dy);
			} else {
				preview.pan(dx, dy);
			}
			preview.hover(Double.NaN, Double.NaN);
			return true;
		}
		if (!painting || event.button() != InputCompat.MOUSE_BUTTON_LEFT) {
			return super.mouseDragged(event, dx, dy);
		}
		int steps = Math.max(1, Math.min(1024, (int) Math.ceil(Math.max(Math.abs(event.x() - previousMouseX), Math.abs(event.y() - previousMouseY)))));
		strokeOp(() -> {
			for (int step = 1; step <= steps; step++) {
				double fraction = step / (double) steps;
				SkinModelPreview.Hit hit = preview.pick(previousMouseX + (event.x() - previousMouseX) * fraction,
					previousMouseY + (event.y() - previousMouseY) * fraction);
				if (hit != null) {
					apply(hit);
				}
			}
		});
		previousMouseX = event.x();
		previousMouseY = event.y();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = painting || cameraButton > 0 || svDrag || hueDrag || layerDrag || colorDrag;
		endInteraction();
		if (handled) {
			rebuildWidgets();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (onLayers(x, y)) {
			scrollLayers = clampScroll((int) (scrollLayers - vertical * 24), layerContentHeight(), layerListBottom() - layerListTop());
			return true;
		}
		if (onColorScroll(x, y)) {
			scrollColor = clampScroll((int) (scrollColor - vertical * 24), colorContentHeight(), colorScrollBottom() - colorScrollTop());
			return true;
		}
		if (onCanvas(x, y) && !painting) {
			preview.zoom(vertical);
			return true;
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() instanceof EditBox) {
			return super.keyPressed(event);
		}
		if (event.key() == InputCompat.KEY_B) {
			select(Tool.PAINT);
		} else if (event.key() == InputCompat.KEY_E) {
			select(Tool.ERASE);
		} else if (event.key() == InputCompat.KEY_I) {
			select(Tool.PICK);
		} else if (event.key() == InputCompat.KEY_F) {
			select(Tool.FILL);
		} else if (event.key() == InputCompat.KEY_R) {
			preview.reset();
		} else {
			return super.keyPressed(event);
		}
		return true;
	}

	@Override
	public void tick() {
		if (document.dirty() && System.nanoTime() - lastAutosave > 120_000_000_000L) {
			lastAutosave = System.nanoTime();
			try {
				repository.save(document);
				status = "Autosaved " + document.name() + " locally.";
			} catch (IOException | RuntimeException exception) {
				failure(exception);
			}
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
		super.extractBackground(graphics, x, y, delta);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.enableScissor(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight);
		preview.hover(mouseX, mouseY);
		preview.render(graphics);
		graphics.disableScissor();
		graphics.outline(canvasX, canvasY, canvasWidth, canvasHeight, 0xFF8B8B8B);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		drawDocks(graphics);
		text(graphics, "Left paint | Right orbit | Middle pan | Wheel zoom | Arrows undo/redo", canvasX + 5, canvasY + 5, 0xFFA0A0A0, canvasWidth - 10);
		text(graphics, document.name() + (document.dirty() ? " *" : "") + " | " + document.layers().get(document.activeLayer()).name()
			+ " | " + preview.shell() + " | " + preview.pose(), 4, height - 21, 0xFFFFFFFF, width - 8);
	}

	private void drawDocks(GuiGraphicsExtractor graphics) {
		text(graphics, "Tools", 6, 34, 0xFFA0A0A0, 106);
		text(graphics, "File", 6, 122, 0xFFA0A0A0, 106);
		text(graphics, "Layers " + document.layers().size() + "/32", 6, 206, 0xFFA0A0A0, 106);
		drawLayerList(graphics);
		int inner = dockRight + 8;
		text(graphics, "Color", inner, 34, 0xFFA0A0A0, 144);
		graphics.fill(inner, 46, inner + 60, 72, previousColor);
		graphics.fill(inner + 64, 46, inner + 124, 72, color);
		graphics.outline(inner, 46, 60, 26, 0xFF8B8B8B);
		graphics.outline(inner + 64, 46, 60, 26, 0xFFFFFFFF);
		int svCells = Math.max(2, svSize / 4);
		for (int row = 0; row < svCells; row++) {
			for (int column = 0; column < svCells; column++) {
				graphics.fill(inner + column * 4, 78 + row * 4, inner + column * 4 + 4, 78 + row * 4 + 4,
					hsvToRgb(hue, Math.min(1, column / (float) (svCells - 1)), 1 - Math.min(1, row / (float) (svCells - 1)), 0xFF000000));
			}
		}
		int hueCells = Math.max(2, svSize / 2);
		for (int row = 0; row < hueCells; row++) {
			graphics.fill(inner + svSize + 4, 78 + row * 2, inner + svSize + 18, 78 + row * 2 + 2,
				hsvToRgb(Math.min(1, row / (float) (hueCells - 1)), 1, 1, 0xFF000000));
		}
		graphics.outline(inner, 78, svSize, svSize, 0xFF8B8B8B);
		graphics.outline(inner + svSize + 4, 78, 14, svSize, 0xFF8B8B8B);
		graphics.fill(inner + (int) (saturation * (svSize - 1)) - 1, 78 + (int) ((1 - value) * (svSize - 1)) - 1,
			inner + (int) (saturation * (svSize - 1)) + 2, 78 + (int) ((1 - value) * (svSize - 1)) + 2, 0xFFFFFFFF);
		graphics.fill(inner + svSize + 3, 78 + (int) (hue * (svSize - 2)), inner + svSize + 15, 78 + (int) (hue * (svSize - 2)) + 2, 0xFFFFFFFF);
		if (!colorGridVisible()) {
			return;
		}
		graphics.enableScissor(dockRight, colorScrollTop(), width - 4, colorScrollBottom());
		int origin = colorOrigin();
		text(graphics, "Basic colors", inner, origin, 0xFFA0A0A0, 144);
		for (int index = 0; index < BASIC.length; index++) {
			int cellX = inner + index % 8 * 14;
			int cellY = origin + 12 + index / 8 * 14;
			graphics.fill(cellX, cellY, cellX + 12, cellY + 12, BASIC[index]);
		}
		text(graphics, "Custom colors", inner, origin + 100, 0xFFA0A0A0, 144);
		for (int index = 0; index < custom.length; index++) {
			int cellX = inner + index % 8 * 14;
			int cellY = origin + 112 + index / 8 * 14;
			graphics.fill(cellX, cellY, cellX + 12, cellY + 12, custom[index]);
		}
		graphics.disableScissor();
		int visible = colorScrollBottom() - colorScrollTop();
		if (colorScrolling()) {
			int track = width - 10;
			graphics.fill(track - 2, colorScrollTop(), track + 4, colorScrollBottom(), 0xFF000000);
			graphics.fill(track - 2, thumbTop(colorScrollTop(), visible, colorContentHeight(), scrollColor), track + 4,
				thumbTop(colorScrollTop(), visible, colorContentHeight(), scrollColor) + Math.max(12, (visible - 8) * visible / colorContentHeight()), 0xFF808080);
		}
		if (height >= 400) {
			text(graphics, "R", inner, height - 92, 0xFFA0A0A0, 10);
			text(graphics, "G", inner + 51, height - 92, 0xFFA0A0A0, 10);
			text(graphics, "B", inner + 102, height - 92, 0xFFA0A0A0, 10);
			text(graphics, "Hex", inner, height - 68, 0xFFA0A0A0, 30);
		}
	}

	private void drawLayerList(GuiGraphicsExtractor graphics) {
		int top = layerListTop();
		int bottom = layerListBottom();
		if (bottom <= top) {
			return;
		}
		scrollLayers = clampScroll(scrollLayers, layerContentHeight(), bottom - top);
		graphics.enableScissor(6, top, 111, bottom);
		var layers = document.layers();
		for (int row = 0; row < layers.size(); row++) {
			int index = layers.size() - 1 - row;
			var layer = layers.get(index);
			int rowTop = top - scrollLayers + row * 24;
			if (rowTop + 22 < top || rowTop > bottom) {
				continue;
			}
			if (index == document.activeLayer()) {
				graphics.fill(6, rowTop, 102, rowTop + 22, 0xFF3A3A3A);
			}
			if (layer.visible()) {
				graphics.fill(8, rowTop + 5, 20, rowTop + 17, 0xFFFFFFFF);
			} else {
				graphics.outline(8, rowTop + 5, 12, 12, 0xFF808080);
			}
			SkinPixels.head(graphics, layer.pixels(), 24, rowTop + 3, 2);
			text(graphics, layer.name(), 42, rowTop + 7, index == document.activeLayer() ? 0xFFFFFFFF : 0xFFA0A0A0, 58);
		}
		graphics.disableScissor();
		int visible = bottom - top;
		if (layerContentHeight() > visible) {
			graphics.fill(103, top, 109, bottom, 0xFF000000);
			int thumb = Math.max(12, (visible - 8) * visible / layerContentHeight());
			graphics.fill(103, thumbTop(top, visible, layerContentHeight(), scrollLayers), 109,
				thumbTop(top, visible, layerContentHeight(), scrollLayers) + thumb, 0xFF808080);
		}
	}

	@Override
	public void removed() {
		endInteraction();
		preview.close();
		super.removed();
	}

	@Override
	public void onClose() {
		endInteraction();
		if (!document.dirty()) {
			super.onClose();
			return;
		}
		GameCompat.setScreen(minecraft, new SkinScreen("Unsaved changes", this) {
			@Override
			protected void init() {
				int x = (width - 240) / 2;
				button("Save and leave", x, 72, 240, () -> {
					if (SkinEditorScreen.this.save()) {
						GameCompat.setScreen(minecraft, SkinEditorScreen.this.parent);
					} else {
						status = SkinEditorScreen.this.status;
					}
				});
				button("Discard changes", x, 98, 240, () -> GameCompat.setScreen(minecraft, SkinEditorScreen.this.parent));
				button("Keep editing", x, 124, 240, this::onClose);
			}
		});
	}

	private final class ViewPanel extends SkinScreen {
		ViewPanel(Screen parent) {
			super("View", parent);
		}

		@Override
		protected void init() {
			int x = (width - 264) / 2;
			button("Done", x, 36, 264, this::onClose);
			button("Shell: " + preview.shell(), x, 64, 264, () -> {
				preview.shell(preview.shell() == SkinModelPreview.Shell.BASE ? SkinModelPreview.Shell.OUTER : SkinModelPreview.Shell.BASE);
				rebuildWidgets();
			});
			button("Part: " + preview.part(), x, 88, 264, () -> {
				var parts = SkinModelPreview.Part.values();
				preview.part(parts[(preview.part().ordinal() + 1) % parts.length]);
				rebuildWidgets();
			});
			String[] views = {"Front", "Back", "Left", "Right", "Top", "Bottom"};
			for (int index = 0; index < views.length; index++) {
				int selected = index;
				button(views[index], x + index % 3 * 90, 112 + index / 3 * 24, 84, () -> {
					preview.view(selected);
					onClose();
				});
			}
			button("Reset view", x, 164, 264, () -> {
				preview.reset();
				onClose();
			});
		}
	}
}
