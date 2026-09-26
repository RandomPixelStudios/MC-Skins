package de.catsmc.mcskins.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

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
	private boolean strokeActive;
	private boolean strokeChanged;
	private SkinLayer workingLayer;

	private record State(String name, boolean slim, List<SkinLayer> layers, int active, long revision) {
	}

	public SkinDocument(UUID id, String name, boolean slim, List<SkinLayer> layers, int activeLayer) {
		this.id = Objects.requireNonNull(id, "A project ID is required.");
		String validName = SkinLayer.validateName(name);
		List<SkinLayer> copy = List.copyOf(layers);
		if (copy.isEmpty() || copy.size() > MAX_LAYERS) {
			throw new IllegalArgumentException("Projects must contain between 1 and 32 layers.");
		}
		Objects.checkIndex(activeLayer, copy.size());
		state = new State(validName, slim, copy, activeLayer, 0);
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

	public boolean dirty() {
		return state.revision() != savedRevision;
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
	}

	public void markSaved() {
		endStroke();
		savedRevision = state.revision();
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
		state = new State(name(), slim(), layers(), index, state.revision());
	}

	public void addLayer(String name) {
		addLayer(new SkinLayer(name, new int[4096]));
	}

	private void addLayer(SkinLayer layer) {
		if (layers().size() >= MAX_LAYERS) {
			throw new IllegalStateException("A project can contain at most 32 layers.");
		}
		var updated = new ArrayList<>(layers());
		int index = activeLayer() + 1;
		updated.add(index, layer);
		change(name(), slim(), updated, index);
	}

	public void removeLayer() {
		if (layers().size() == 1) {
			return;
		}
		var updated = new ArrayList<>(layers());
		updated.remove(activeLayer());
		change(name(), slim(), updated, Math.min(activeLayer(), updated.size() - 1));
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
		updated.set(index, new SkinLayer(layer.name(), visible, layer.pixels(), layer.offsetX(), layer.offsetY()));
		change(name(), slim(), updated, activeLayer());
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
			replace(new SkinLayer(layer.name(), layer.visible(), layer.pixels(), (int) x, (int) y));
		}
	}

	public void paint(int x, int y, int color) {
		checkPixel(x, y);
		SkinLayer layer = layers().get(activeLayer());
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
		SkinLayer next = SkinLayer.adopting(layer.name(), layer.visible(), pixels, offsetX, offsetY);
		replace(next);
		workingLayer = strokeActive ? next : null;
	}

	private void touch() {
		state = new State(name(), slim(), layers(), activeLayer(), ++revision);
	}

	public void fill(int x, int y, int color) {
		checkPixel(x, y);
		SkinLayer layer = layers().get(activeLayer());
		int[] pixels = layer.canvas();
		int target = pixels[y * 64 + x];
		if (target == color) {
			return;
		}
		int[] queue = new int[4096];
		int start = 0;
		int end = 1;
		queue[0] = y * 64 + x;
		pixels[queue[0]] = color;
		while (start < end) {
			int position = queue[start++];
			int column = position % 64;
			if (column > 0 && pixels[position - 1] == target) {
				pixels[position - 1] = color;
				queue[end++] = position - 1;
			}
			if (column < 63 && pixels[position + 1] == target) {
				pixels[position + 1] = color;
				queue[end++] = position + 1;
			}
			if (position >= 64 && pixels[position - 64] == target) {
				pixels[position - 64] = color;
				queue[end++] = position - 64;
			}
			if (position < 4032 && pixels[position + 64] == target) {
				pixels[position + 64] = color;
				queue[end++] = position + 64;
			}
		}
		replace(SkinLayer.adopting(layer.name(), layer.visible(), pixels, 0, 0));
	}

	public int sample(int x, int y) {
		checkPixel(x, y);
		int result = 0;
		for (SkinLayer layer : layers()) {
			if (layer.visible()) {
				result = over(layer.pixel(x, y), result);
			}
		}
		return result;
	}

	public int[] flatten() {
		int[] result = new int[4096];
		for (SkinLayer layer : layers()) {
			if (!layer.visible()) {
				continue;
			}
			for (int y = 0; y < 64; y++) {
				for (int x = 0; x < 64; x++) {
					int index = y * 64 + x;
					result[index] = over(layer.pixel(x, y), result[index]);
				}
			}
		}
		return result;
	}

	public void importLayer(Path file, int width, int height) throws IOException {
		if (width < 1 || width > 64 || height < 1 || height > 64) {
			throw new IllegalArgumentException("Target dimensions must be between 1 and 64.");
		}
		if (layers().size() >= MAX_LAYERS) {
			throw new IllegalStateException("A project can contain at most 32 layers.");
		}
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_IMAGE_BYTES) {
			throw new IOException("Choose a regular PNG file of at most 16 MiB.");
		}
		BufferedImage image;
		try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
			image = readPng(input);
		}
		int[] pixels = new int[4096];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				pixels[y * 64 + x] = image.getRGB(x * image.getWidth() / width, y * image.getHeight() / height);
			}
		}
		String name = file.getFileName().toString();
		int extension = name.lastIndexOf('.');
		if (extension > 0) {
			name = name.substring(0, extension);
		}
		if (name.length() > 64) {
			name = name.substring(0, Character.isHighSurrogate(name.charAt(63)) ? 63 : 64);
		}
		try {
			name = SkinLayer.validateName(name);
		} catch (IllegalArgumentException exception) {
			name = "Imported image";
		}
		addLayer(new SkinLayer(name, pixels));
	}

	static BufferedImage readPng(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
		if (bytes.length > MAX_IMAGE_BYTES) {
			throw new IOException("PNG must be at most 16 MiB.");
		}
		byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
		if (bytes.length < signature.length || !Arrays.equals(signature, Arrays.copyOf(bytes, signature.length))) {
			throw new IOException("Choose a PNG image.");
		}
		try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new IOException("Cannot decode PNG image.");
			}
			var reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (!reader.getFormatName().equalsIgnoreCase("png") || width < 1 || height < 1
					|| width > 4096 || height > 4096 || (long) width * height > 4_194_304) {
					throw new IOException("Use a PNG up to 4096 per side and 4 megapixels total.");
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw new IOException("Cannot decode PNG image.");
				}
				return image;
			} finally {
				reader.dispose();
			}
		}
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
		State next = new State(name, slim, List.copyOf(layers), active, ++revision);
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

	private static int over(int foreground, int background) {
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
