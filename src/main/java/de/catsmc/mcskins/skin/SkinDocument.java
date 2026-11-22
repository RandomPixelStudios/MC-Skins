package de.catsmc.mcskins.skin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SkinDocument {
	public static final int SIZE = 64;
	public static final int MAX_LAYERS = 32;
	private static final int MAX_HISTORY = 128;
	private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
	private final UUID id;
	private final ArrayDeque<State> undo = new ArrayDeque<>();
	private final ArrayDeque<State> redo = new ArrayDeque<>();
	private State state;
	private long revision;
	private long savedRevision = -1;
	private long savedSequence = Long.MIN_VALUE;
	private boolean strokeActive;
	private boolean strokeChanged;
	private SkinLayer workingLayer;
	private State flattenedState;
	private int[] flattened;
	private String pendingLabel;
	private static final String DEFAULT_LABEL = "Edit";

	/** One undoable state; {@code label} names the action that produced it, for the history panel. */
	private record State(String name, boolean slim, List<SkinLayer> layers, int active, List<LayerGroup> groups,
		long revision, String label) {
	}

	public SkinDocument(UUID id, String name, boolean slim, List<SkinLayer> layers, int activeLayer) {
		this(id, name, slim, layers, activeLayer, List.of());
	}

	public SkinDocument(UUID id, String name, boolean slim, List<SkinLayer> layers, int activeLayer, List<LayerGroup> groups) {
		this.id = Objects.requireNonNull(id, "A project ID is required.");
		String validName = SkinLayer.validateName(name);
		List<SkinLayer> copy = List.copyOf(layers);
		if (copy.isEmpty() || copy.size() > MAX_LAYERS) {
			throw new IllegalArgumentException("Projects must contain between 1 and 32 layers.");
		}
		Objects.checkIndex(activeLayer, copy.size());
		List<LayerGroup> groupCopy = List.copyOf(groups);
		validateGroups(copy, groupCopy);
		state = new State(validName, slim, copy, activeLayer, prune(copy, groupCopy), 0, "Opened");
	}

	private static void validateGroups(List<SkinLayer> layers, List<LayerGroup> groups) {
		if (groups.size() > LayerGroup.MAX_GROUPS) {
			throw new IllegalArgumentException("Projects can contain at most 32 layer groups.");
		}
		var ids = new java.util.HashSet<Integer>();
		for (LayerGroup group : groups) {
			if (!ids.add(group.id())) {
				throw new IllegalArgumentException("Layer group ids must be unique.");
			}
		}
		for (SkinLayer layer : layers) {
			if (layer.group() != 0 && !ids.contains(layer.group())) {
				throw new IllegalArgumentException("A layer refers to a missing group.");
			}
		}
	}

	/** Drops groups that no layer belongs to any more. */
	private static List<LayerGroup> prune(List<SkinLayer> layers, List<LayerGroup> groups) {
		if (groups.isEmpty()) {
			return groups;
		}
		var used = new java.util.HashSet<Integer>();
		for (SkinLayer layer : layers) {
			used.add(layer.group());
		}
		return groups.stream().filter(group -> used.contains(group.id())).toList();
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return state.name();
	}

	public boolean slim() {
		return state.slim();
	}

	public List<SkinLayer> layers() {
		return state.layers();
	}

	public int activeLayer() {
		return state.active();
	}

	public List<LayerGroup> groups() {
		return state.groups();
	}

	/** The group with this id, or null. */
	public LayerGroup group(int groupId) {
		for (LayerGroup group : state.groups()) {
			if (group.id() == groupId) {
				return group;
			}
		}
		return null;
	}

	/** Names the next undo step (for example "Brush" or "Fill"); it applies until the step is finished. */
	public void label(String label) {
		pendingLabel = label == null || label.isBlank() ? null : truncate(label.strip(), 48);
	}

	/**
	 * Labels of every reachable state, oldest first: past steps, then the current state at {@link #historyPosition()},
	 * then the steps that redo would bring back.
	 */
	public List<String> history() {
		var result = new ArrayList<String>(undo.size() + redo.size() + 1);
		for (State past : undo) {
			result.add(past.label());
		}
		result.add(state.label());
		for (Iterator<State> iterator = redo.descendingIterator(); iterator.hasNext(); ) {
			result.add(iterator.next().label());
		}
		return result;
	}

	public int historyPosition() {
		return undo.size();
	}

	/** Undoes or redoes until the state at {@code index} of {@link #history()} is current. */
	public void jumpTo(int index) {
		Objects.checkIndex(index, undo.size() + redo.size() + 1);
		endStroke();
		while (undo.size() > index) {
			undo();
		}
		while (undo.size() < index && !redo.isEmpty()) {
			redo();
		}
	}

	public boolean dirty() {
		return state.revision() != savedRevision;
	}

	/** Identifies the current content; pass it to {@link #markSaved(long)} after an asynchronous save. */
	public long revision() {
		return state.revision();
	}

	public void beginStroke() {
		if (!strokeActive) {
			strokeActive = true;
			strokeChanged = false;
		}
	}

	public void endStroke() {
		strokeActive = false;
		strokeChanged = false;
		workingLayer = null;
		pendingLabel = null;
	}

	public void markSaved() {
		endStroke();
		savedRevision = state.revision();
	}

	public void markSaved(long revision) {
		savedRevision = revision;
	}

	/**
	 * Marks {@code revision} as saved unless a save that was prepared later (higher {@code sequence}) already
	 * completed, so a slow older save cannot make the document look dirty or clean by mistake.
	 */
	public void markSaved(long revision, long sequence) {
		if (sequence >= savedSequence) {
			savedSequence = sequence;
			savedRevision = revision;
		}
	}

	public void rename(String name) {
		String value = SkinLayer.validateName(name);
		if (!value.equals(name())) {
			change(value, slim(), layers(), activeLayer());
		}
	}

	public void setSlim(boolean slim) {
		if (slim != slim()) {
			change(name(), slim, layers(), activeLayer());
		}
	}

	public void selectLayer(int index) {
		Objects.checkIndex(index, layers().size());
		endStroke();
		state = new State(name(), slim(), layers(), index, groups(), state.revision(), state.label());
	}

	/** Adds an empty layer above the active one, in the active layer's group. */
	public void addLayer(String name) {
		addLayer(new SkinLayer(name, new int[4096]).withGroup(layers().get(activeLayer()).group()));
	}

	private void addLayer(SkinLayer layer) {
		insertLayer(layer, activeLayer() + 1);
	}

	/** Inserts a layer at {@code index} (0 is the bottom) and selects it. */
	public void insertLayer(SkinLayer layer, int index) {
		Objects.requireNonNull(layer, "A layer is required.");
		if (layers().size() >= MAX_LAYERS) {
			throw new IllegalStateException("A project can contain at most 32 layers.");
		}
		if (layer.group() != 0 && group(layer.group()) == null) {
			layer = layer.withGroup(0);
		}
		var updated = new ArrayList<>(layers());
		int position = Math.clamp(index, 0, updated.size());
		updated.add(position, layer);
		change(name(), slim(), updated, position);
	}

	public void removeLayer() {
		if (layers().size() == 1) {
			return;
		}
		var updated = new ArrayList<>(layers());
		updated.remove(activeLayer());
		change(name(), slim(), updated, Math.min(activeLayer(), updated.size() - 1));
	}

	public void setLayerBlend(int index, BlendMode mode) {
		Objects.checkIndex(index, layers().size());
		SkinLayer layer = layers().get(index);
		if (layer.blend() != Objects.requireNonNull(mode)) {
			var updated = new ArrayList<>(layers());
			updated.set(index, layer.withBlend(mode));
			change(name(), slim(), updated, activeLayer());
		}
	}

	public void setLayerAlphaLock(int index, boolean locked) {
		Objects.checkIndex(index, layers().size());
		SkinLayer layer = layers().get(index);
		if (layer.alphaLock() != locked) {
			var updated = new ArrayList<>(layers());
			updated.set(index, layer.withAlphaLock(locked));
			change(name(), slim(), updated, activeLayer());
		}
	}

	// ---- groups ------------------------------------------------------------------------------------------

	/**
	 * Puts the active layer into a new group named {@code name}. If the layer directly below is not grouped, it joins
	 * the new group too, so grouping two layers is a single step.
	 */
	public int createGroup(String name) {
		if (groups().size() >= LayerGroup.MAX_GROUPS) {
			throw new IllegalStateException("A project can contain at most 32 layer groups.");
		}
		int id = 1;
		for (LayerGroup group : groups()) {
			id = Math.max(id, group.id() + 1);
		}
		var groupList = new ArrayList<>(groups());
		groupList.add(new LayerGroup(id, name, true, 255, false));
		var updated = new ArrayList<>(layers());
		int index = activeLayer();
		updated.set(index, updated.get(index).withGroup(id));
		if (index > 0 && updated.get(index - 1).group() == 0) {
			updated.set(index - 1, updated.get(index - 1).withGroup(id));
		}
		change(name(), slim(), updated, index, groupList);
		return id;
	}

	/** Moves the active layer into the group of the layer below it; false if that layer is not grouped. */
	public boolean joinGroupBelow() {
		int index = activeLayer();
		if (index == 0 || layers().get(index - 1).group() == 0 || layers().get(index - 1).group() == layers().get(index).group()) {
			return false;
		}
		setLayerGroup(index, layers().get(index - 1).group());
		return true;
	}

	/** Sets a layer's group (0 removes it from its group). Groups left empty are removed. */
	public void setLayerGroup(int index, int groupId) {
		Objects.checkIndex(index, layers().size());
		if (groupId != 0 && group(groupId) == null) {
			throw new IllegalArgumentException("Unknown layer group.");
		}
		SkinLayer layer = layers().get(index);
		if (layer.group() != groupId) {
			var updated = new ArrayList<>(layers());
			updated.set(index, layer.withGroup(groupId));
			change(name(), slim(), updated, activeLayer());
		}
	}

	/** Removes a group; its layers stay and become ungrouped. */
	public void dissolveGroup(int groupId) {
		if (group(groupId) == null) {
			return;
		}
		var updated = new ArrayList<SkinLayer>(layers().size());
		for (SkinLayer layer : layers()) {
			updated.add(layer.group() == groupId ? layer.withGroup(0) : layer);
		}
		change(name(), slim(), updated, activeLayer());
	}

	public void renameGroup(int groupId, String name) {
		LayerGroup group = requireGroup(groupId);
		String value = SkinLayer.validateName(name);
		if (!value.equals(group.name())) {
			replaceGroup(group.withName(value));
		}
	}

	public void setGroupVisible(int groupId, boolean visible) {
		LayerGroup group = requireGroup(groupId);
		if (group.visible() != visible) {
			replaceGroup(group.withVisible(visible));
		}
	}

	/** Changes a group's opacity; wrap slider drags in begin/endStroke to keep one undo step. */
	public void setGroupOpacity(int groupId, int opacity) {
		LayerGroup group = requireGroup(groupId);
		int value = Math.clamp(opacity, 0, 255);
		if (group.opacity() != value) {
			replaceGroup(group.withOpacity(value));
		}
	}

	/** Collapsing only changes how the list looks, so it is not an undo step and does not dirty the document. */
	public void setGroupCollapsed(int groupId, boolean collapsed) {
		LayerGroup group = requireGroup(groupId);
		if (group.collapsed() != collapsed) {
			var list = new ArrayList<>(groups());
			list.replaceAll(entry -> entry.id() == groupId ? entry.withCollapsed(collapsed) : entry);
			state = new State(name(), slim(), layers(), activeLayer(), List.copyOf(list), state.revision(), state.label());
		}
	}

	private LayerGroup requireGroup(int groupId) {
		LayerGroup group = group(groupId);
		if (group == null) {
			throw new IllegalArgumentException("Unknown layer group.");
		}
		return group;
	}

	private void replaceGroup(LayerGroup group) {
		var list = new ArrayList<>(groups());
		list.replaceAll(entry -> entry.id() == group.id() ? group : entry);
		change(name(), slim(), layers(), activeLayer(), list);
	}

	/** Whether a layer is shown, taking its group into account. */
	public boolean effectivelyVisible(int index) {
		SkinLayer layer = layers().get(index);
		if (!layer.visible()) {
			return false;
		}
		LayerGroup group = layer.group() == 0 ? null : group(layer.group());
		return group == null || group.visible();
	}

	public void toggleLayer() {
		setLayerVisible(activeLayer(), !layers().get(activeLayer()).visible());
	}

	public void setLayerVisible(int index, boolean visible) {
		Objects.checkIndex(index, layers().size());
		SkinLayer layer = layers().get(index);
		if (layer.visible() == visible) {
			return;
		}
		var updated = new ArrayList<>(layers());
		updated.set(index, layer.withVisible(visible));
		change(name(), slim(), updated, activeLayer());
	}

	public void renameLayer(int index, String name) {
		Objects.checkIndex(index, layers().size());
		SkinLayer layer = layers().get(index);
		String value = SkinLayer.validateName(name);
		if (!value.equals(layer.name())) {
			var updated = new ArrayList<>(layers());
			updated.set(index, layer.withName(value));
			change(name(), slim(), updated, activeLayer());
		}
	}

	/** Changes opacity; wrap continuous slider drags in begin/endStroke to keep one undo step. */
	public void setLayerOpacity(int index, int opacity) {
		Objects.checkIndex(index, layers().size());
		SkinLayer layer = layers().get(index);
		int value = Math.clamp(opacity, 0, 255);
		if (value != layer.opacity()) {
			var updated = new ArrayList<>(layers());
			updated.set(index, layer.withOpacity(value));
			change(name(), slim(), updated, activeLayer());
		}
	}

	public void duplicateLayer() {
		duplicateLayer(" copy");
	}

	/** Duplicates the active layer, appending {@code suffix} (for example a translated " copy") to its name. */
	public void duplicateLayer(String suffix) {
		SkinLayer layer = layers().get(activeLayer());
		String ending = suffix == null || suffix.length() > 16 ? " copy" : suffix;
		addLayer(new SkinLayer(truncate(layer.name(), 64 - ending.length()) + ending, layer.visible(), layer.opacity(),
			layer.pixels(), layer.offsetX(), layer.offsetY(), layer.blend(), layer.alphaLock(), layer.group()));
	}

	/** Shortens to at most {@code length} UTF-16 units without splitting a surrogate pair. */
	static String truncate(String value, int length) {
		if (value.length() <= length) {
			return value;
		}
		int end = length > 0 && Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length;
		return value.substring(0, end).strip();
	}

	/** Whether {@link #mergeDown()} can run: there is a layer below and both layers are visible. */
	public boolean canMergeDown() {
		int index = activeLayer();
		return index > 0 && layers().get(index).visible() && layers().get(index - 1).visible();
	}

	/**
	 * Composites the active layer onto the one below it, keeping the lower layer's name. Both layers must be visible,
	 * so no hidden pixels are silently dropped and no visible pixels disappear into a hidden result.
	 */
	public void mergeDown() {
		int index = activeLayer();
		if (index == 0) {
			return;
		}
		SkinLayer upper = layers().get(index);
		SkinLayer lower = layers().get(index - 1);
		if (!upper.visible() || !lower.visible()) {
			throw new IllegalStateException("Show both layers before merging them.");
		}
		int[] pixels = new int[4096];
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				pixels[y * 64 + x] = upper.blend().composite(upper.composite(x, y), lower.composite(x, y));
			}
		}
		var updated = new ArrayList<>(layers());
		updated.remove(index);
		updated.set(index - 1, new SkinLayer(lower.name(), true, 255, pixels, 0, 0, lower.blend(), lower.alphaLock(), lower.group()));
		change(name(), slim(), updated, index - 1);
	}

	public void clearLayer() {
		SkinLayer layer = layers().get(activeLayer());
		if (Arrays.stream(layer.pixels()).anyMatch(pixel -> pixel != 0)) {
			replace(SkinLayer.adopting(layer, new int[4096], 0, 0));
		}
	}

	public void moveLayer(int direction) {
		if (direction < -1 || direction > 1) {
			throw new IllegalArgumentException("Move a layer one position at a time.");
		}
		int target = activeLayer() + direction;
		if (direction == 0 || target < 0 || target >= layers().size()) {
			return;
		}
		var updated = new ArrayList<>(layers());
		SkinLayer layer = updated.remove(activeLayer());
		updated.add(target, layer);
		change(name(), slim(), updated, target);
	}

	public void moveImage(int dx, int dy) {
		SkinLayer layer = layers().get(activeLayer());
		long x = (long) layer.offsetX() + dx;
		long y = (long) layer.offsetY() + dy;
		if (x < -64 || x > 64 || y < -64 || y > 64) {
			throw new IllegalArgumentException("Layer offsets must stay between -64 and 64.");
		}
		if (dx != 0 || dy != 0) {
			replace(layer.withOffset((int) x, (int) y));
		}
	}

	/**
	 * Paints one canvas pixel on the active layer. If a {@link #moveImage moved} layer does not cover that pixel, the
	 * layer is re-based to offset 0 first: its pixels that were moved outside the 64 x 64 canvas are dropped then
	 * (undo restores them). Offsets only come from {@link #moveImage}, which the editor does not expose.
	 */
	public void paint(int x, int y, int color) {
		checkPixel(x, y);
		SkinLayer layer = layers().get(activeLayer());
		if (layer.alphaLock()) {
			int old = layer.pixel(x, y);
			if (old >>> 24 == 0 || color >>> 24 == 0) {
				return;
			}
			color = old & 0xFF000000 | color & 0xFFFFFF;
		}
		if (layer.pixel(x, y) == color) {
			return;
		}
		int localX = x - layer.offsetX();
		int localY = y - layer.offsetY();
		boolean inside = localX >= 0 && localX < 64 && localY >= 0 && localY < 64;
		if (inside && layer == workingLayer) {
			layer.write(localY * 64 + localX, color);
			touch();
			return;
		}
		int offsetX = layer.offsetX();
		int offsetY = layer.offsetY();
		int[] pixels;
		if (inside) {
			pixels = layer.pixels();
			pixels[localY * 64 + localX] = color;
		} else {
			pixels = layer.canvas();
			pixels[y * 64 + x] = color;
			offsetX = 0;
			offsetY = 0;
		}
		SkinLayer next = SkinLayer.adopting(layer, pixels, offsetX, offsetY);
		replace(next);
		workingLayer = strokeActive ? next : null;
	}

	private void touch() {
		state = new State(name(), slim(), layers(), activeLayer(), groups(), ++revision, state.label());
	}

	/**
	 * Replaces the active layer's pixels with a full 64 x 64 canvas (offset 0) in one undo step, for selection edits,
	 * part copies and similar whole-layer operations. Alpha lock is not applied here.
	 */
	public void applyCanvas(int[] canvas) {
		if (canvas.length != 4096) {
			throw new IllegalArgumentException("Canvas must contain 4096 pixels.");
		}
		SkinLayer layer = layers().get(activeLayer());
		if (layer.offsetX() == 0 && layer.offsetY() == 0 && Arrays.equals(layer.pixels(), canvas)) {
			return;
		}
		replace(SkinLayer.adopting(layer, canvas.clone(), 0, 0));
	}

	/** Clears the given pixels on every layer in one undo step; false if nothing was there. */
	public boolean clearEverywhere(BitSet pixels) {
		var updated = new ArrayList<SkinLayer>(layers().size());
		boolean changed = false;
		for (SkinLayer layer : layers()) {
			int[] canvas = layer.canvas();
			boolean touched = false;
			for (int pixel = pixels.nextSetBit(0); pixel >= 0 && pixel < 4096; pixel = pixels.nextSetBit(pixel + 1)) {
				if (canvas[pixel] != 0) {
					canvas[pixel] = 0;
					touched = true;
				}
			}
			updated.add(touched ? SkinLayer.adopting(layer, canvas, 0, 0) : layer);
			changed |= touched;
		}
		if (changed) {
			change(name(), slim(), updated, activeLayer());
		}
		return changed;
	}

	/** The active layer's own pixels in canvas coordinates, ignoring its visibility and opacity. */
	public int[] activeCanvas() {
		return layers().get(activeLayer()).canvas();
	}

	public int sample(int x, int y) {
		checkPixel(x, y);
		return cachedFlatten()[y * 64 + x];
	}

	/** The composited 64 x 64 image; cached until the document changes. */
	public int[] flatten() {
		return cachedFlatten().clone();
	}

	private int[] cachedFlatten() {
		if (flattenedState != state) {
			flattened = compose();
			flattenedState = state;
		}
		return flattened;
	}

	private int[] compose() {
		return compose(-1, null);
	}

	/**
	 * The composited image as if the active layer held {@code activeCanvas} (canvas coordinates, offset 0), for
	 * previews such as a selection being moved; nothing is changed.
	 */
	public int[] flattenWith(int[] activeCanvas) {
		if (activeCanvas == null) {
			return flatten();
		}
		if (activeCanvas.length != 4096) {
			throw new IllegalArgumentException("Canvas must contain 4096 pixels.");
		}
		return compose(activeLayer(), activeCanvas);
	}

	private int[] compose(int overrideIndex, int[] override) {
		int[] result = new int[4096];
		List<SkinLayer> layers = layers();
		for (int index = 0; index < layers.size(); index++) {
			SkinLayer layer = layers.get(index);
			LayerGroup group = layer.group() == 0 ? null : group(layer.group());
			if (!layer.visible() || group != null && !group.visible()) {
				continue;
			}
			int groupOpacity = group == null ? 255 : group.opacity();
			BlendMode blend = layer.blend();
			SkinLayer source = index == overrideIndex ? SkinLayer.adopting(layer, override, 0, 0) : layer;
			for (int y = 0; y < 64; y++) {
				for (int x = 0; x < 64; x++) {
					int pixel = y * 64 + x;
					result[pixel] = blend.composite(source.composite(x, y, groupOpacity), result[pixel]);
				}
			}
		}
		return result;
	}

	public void importLayer(Path file, int width, int height) throws IOException {
		importLayer(file, width, height, "Imported image");
	}

	/** Imports a PNG as a new layer named after the file, or {@code fallbackName} if the filename is not usable. */
	public void importLayer(Path file, int width, int height, String fallbackName) throws IOException {
		if (layers().size() >= MAX_LAYERS) {
			throw new IllegalStateException("A project can contain at most 32 layers.");
		}
		addLayer(decodeLayer(file, width, height, fallbackName));
	}

	/**
	 * Reads and scales a PNG into a layer without touching the document, so it can run on a background thread;
	 * add the result with {@link #addImportedLayer(SkinLayer)}.
	 */
	public static SkinLayer decodeLayer(Path file, int width, int height, String fallbackName) throws IOException {
		if (width < 1 || width > 64 || height < 1 || height > 64) {
			throw new IllegalArgumentException("Target dimensions must be between 1 and 64.");
		}
		BufferedImage image = PngCodec.decode(SafeFiles.read(file, MAX_IMAGE_BYTES), 4096, 4_194_304);
		int[] pixels = scaled(image, width, height);
		String name = file.getFileName().toString();
		int extension = name.lastIndexOf('.');
		if (extension > 0) {
			name = name.substring(0, extension);
		}
		name = truncate(name, 64);
		try {
			name = SkinLayer.validateName(name);
		} catch (IllegalArgumentException exception) {
			try {
				name = SkinLayer.validateName(fallbackName == null ? "Imported image" : truncate(fallbackName, 64));
			} catch (IllegalArgumentException invalid) {
				name = "Imported image";
			}
		}
		return new SkinLayer(name, pixels);
	}

	/**
	 * Scales an image into a {@code width} x {@code height} area of a 64 x 64 canvas (nearest neighbor). An old
	 * 64 x 32 skin is converted to the 64 x 64 layout instead of being stretched.
	 */
	public static int[] scaled(BufferedImage image, int width, int height) {
		if (image.getWidth() == 64 && image.getHeight() == 32 && width == 64 && height == 64) {
			return LegacySkin.toModern(PngCodec.argb(image, 64, 32));
		}
		int[] pixels = new int[4096];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int color = image.getRGB(x * image.getWidth() / width, y * image.getHeight() / height);
				pixels[y * 64 + x] = color >>> 24 == 0 ? 0 : color;
			}
		}
		return pixels;
	}

	/** A layer from an already decoded image (for example from the clipboard), scaled like {@link #decodeLayer}. */
	public static SkinLayer layerFromImage(BufferedImage image, String name) {
		String value;
		try {
			value = SkinLayer.validateName(name == null ? "Pasted image" : truncate(name, 64));
		} catch (IllegalArgumentException exception) {
			value = "Pasted image";
		}
		return new SkinLayer(value, scaled(image, 64, 64));
	}

	/** Adds a layer made by {@link #decodeLayer} above the active one. */
	public void addImportedLayer(SkinLayer layer) {
		addLayer(Objects.requireNonNull(layer, "A layer is required."));
	}

	public void undo() {
		endStroke();
		if (!undo.isEmpty()) {
			redo.addLast(state);
			state = undo.removeLast();
		}
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public void redo() {
		endStroke();
		if (!redo.isEmpty()) {
			undo.addLast(state);
			state = redo.removeLast();
		}
	}

	private void replace(SkinLayer layer) {
		var updated = new ArrayList<>(layers());
		updated.set(activeLayer(), layer);
		change(name(), slim(), updated, activeLayer());
	}

	private void change(String name, boolean slim, List<SkinLayer> layers, int active) {
		change(name, slim, layers, active, groups());
	}

	private void change(String name, boolean slim, List<SkinLayer> layers, int active, List<LayerGroup> groups) {
		List<SkinLayer> layerCopy = List.copyOf(layers);
		boolean continuing = strokeActive && strokeChanged;
		String label = continuing ? state.label() : pendingLabel == null ? DEFAULT_LABEL : pendingLabel;
		State next = new State(name, slim, layerCopy, active, prune(layerCopy, List.copyOf(groups)), ++revision, label);
		if (!continuing && !strokeActive) {
			pendingLabel = null;
		}
		if (!strokeActive || !strokeChanged) {
			undo.addLast(state);
			if (undo.size() > MAX_HISTORY) {
				undo.removeFirst();
			}
			redo.clear();
			workingLayer = null;
			strokeChanged = strokeActive;
		}
		state = next;
	}

	private static void checkPixel(int x, int y) {
		Objects.checkIndex(x, SIZE);
		Objects.checkIndex(y, SIZE);
	}

	static int over(int foreground, int background) {
		int alpha = foreground >>> 24;
		if (alpha == 0) {
			return background;
		}
		if (alpha == 255 || background >>> 24 == 0) {
			return foreground;
		}
		int backgroundWeight = (background >>> 24) * (255 - alpha);
		int foregroundWeight = alpha * 255;
		int weight = foregroundWeight + backgroundWeight;
		int result = ((weight + 127) / 255) << 24;
		for (int shift = 0; shift <= 16; shift += 8) {
			int channel = (((foreground >>> shift) & 255) * foregroundWeight
				+ ((background >>> shift) & 255) * backgroundWeight + weight / 2) / weight;
			result |= channel << shift;
		}
		return result;
	}
}
