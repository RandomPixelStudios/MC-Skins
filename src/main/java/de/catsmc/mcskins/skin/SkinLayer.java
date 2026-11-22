package de.catsmc.mcskins.skin;

import java.util.Objects;

public final class SkinLayer {
	private final String name;
	private final boolean visible;
	private final int opacity;
	private final int[] pixels;
	private final int offsetX;
	private final int offsetY;
	private final BlendMode blend;
	private final boolean alphaLock;
	private final int group;

	public SkinLayer(String name, int[] pixels) {
		this(name, true, pixels, 0, 0);
	}

	SkinLayer(String name, boolean visible, int[] pixels, int offsetX, int offsetY) {
		this(name, visible, 255, pixels, offsetX, offsetY, false);
	}

	SkinLayer(String name, boolean visible, int opacity, int[] pixels, int offsetX, int offsetY) {
		this(name, visible, opacity, pixels, offsetX, offsetY, false);
	}

	SkinLayer(String name, boolean visible, int opacity, int[] pixels, int offsetX, int offsetY, BlendMode blend,
		boolean alphaLock, int group) {
		this(name, visible, opacity, pixels, offsetX, offsetY, false, blend, alphaLock, group);
	}

	static SkinLayer adopting(SkinLayer source, int[] pixels, int offsetX, int offsetY) {
		return new SkinLayer(source.name, source.visible, source.opacity, pixels, offsetX, offsetY, true, source.blend,
			source.alphaLock, source.group);
	}

	private SkinLayer(String name, boolean visible, int opacity, int[] pixels, int offsetX, int offsetY, boolean owned) {
		this(name, visible, opacity, pixels, offsetX, offsetY, owned, BlendMode.NORMAL, false, 0);
	}

	private SkinLayer(String name, boolean visible, int opacity, int[] pixels, int offsetX, int offsetY, boolean owned,
		BlendMode blend, boolean alphaLock, int group) {
		this.name = validateName(name);
		this.blend = Objects.requireNonNull(blend, "A blend mode is required.");
		this.alphaLock = alphaLock;
		if (group < 0) {
			throw new IllegalArgumentException("Layer group ids cannot be negative.");
		}
		this.group = group;
		if (opacity < 0 || opacity > 255) {
			throw new IllegalArgumentException("Layer opacity must be between 0 and 255.");
		}
		this.opacity = opacity;
		Objects.requireNonNull(pixels, "Pixels are required.");
		if (pixels.length != SkinDocument.SIZE * SkinDocument.SIZE) {
			throw new IllegalArgumentException("Layers must contain exactly 4096 pixels.");
		}
		if (offsetX < -64 || offsetX > 64 || offsetY < -64 || offsetY > 64) {
			throw new IllegalArgumentException("Layer offsets must be between -64 and 64.");
		}
		this.visible = visible;
		this.pixels = owned ? pixels : pixels.clone();
		this.offsetX = offsetX;
		this.offsetY = offsetY;
	}

	public String name() {
		return name;
	}

	public boolean visible() {
		return visible;
	}

	public int opacity() {
		return opacity;
	}

	public BlendMode blend() {
		return blend;
	}

	/** With alpha lock on, painting keeps every pixel's transparency: empty pixels stay empty. */
	public boolean alphaLock() {
		return alphaLock;
	}

	/** The id of the layer group this layer belongs to, or 0 for none. */
	public int group() {
		return group;
	}

	SkinLayer withName(String value) {
		return new SkinLayer(value, visible, opacity, pixels, offsetX, offsetY, true, blend, alphaLock, group);
	}

	SkinLayer withVisible(boolean value) {
		return new SkinLayer(name, value, opacity, pixels, offsetX, offsetY, true, blend, alphaLock, group);
	}

	SkinLayer withOpacity(int value) {
		return new SkinLayer(name, visible, value, pixels, offsetX, offsetY, true, blend, alphaLock, group);
	}

	SkinLayer withOffset(int x, int y) {
		return new SkinLayer(name, visible, opacity, pixels, x, y, true, blend, alphaLock, group);
	}

	SkinLayer withBlend(BlendMode value) {
		return new SkinLayer(name, visible, opacity, pixels, offsetX, offsetY, true, value, alphaLock, group);
	}

	SkinLayer withAlphaLock(boolean value) {
		return new SkinLayer(name, visible, opacity, pixels, offsetX, offsetY, true, blend, value, group);
	}

	SkinLayer withGroup(int value) {
		return new SkinLayer(name, visible, opacity, pixels, offsetX, offsetY, true, blend, alphaLock, value);
	}

	/** The pixel at canvas coordinates with this layer's opacity scaled by {@code groupOpacity} (0-255). */
	int composite(int x, int y, int groupOpacity) {
		int color = pixel(x, y);
		int combined = groupOpacity >= 255 ? opacity : (opacity * groupOpacity + 127) / 255;
		if (combined == 255 || color >>> 24 == 0) {
			return color;
		}
		int alpha = ((color >>> 24) * combined + 127) / 255;
		return alpha == 0 ? 0 : alpha << 24 | color & 0xFFFFFF;
	}

	/** The pixel at canvas coordinates with this layer's opacity applied. */
	int composite(int x, int y) {
		int color = pixel(x, y);
		if (opacity == 255 || color >>> 24 == 0) {
			return color;
		}
		int alpha = ((color >>> 24) * opacity + 127) / 255;
		return alpha << 24 | color & 0xFFFFFF;
	}

	public int[] pixels() {
		return pixels.clone();
	}

	public int pixelAt(int x, int y) {
		Objects.checkIndex(x, SkinDocument.SIZE);
		Objects.checkIndex(y, SkinDocument.SIZE);
		return pixels[y * SkinDocument.SIZE + x];
	}

	public int offsetX() {
		return offsetX;
	}

	public int offsetY() {
		return offsetY;
	}

	int pixel(int x, int y) {
		x -= offsetX;
		y -= offsetY;
		return x < 0 || x >= 64 || y < 0 || y >= 64 ? 0 : pixels[y * 64 + x];
	}

	void write(int index, int color) {
		Objects.checkIndex(index, pixels.length);
		pixels[index] = color;
	}

	int[] canvas() {
		int[] result = new int[4096];
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				result[y * 64 + x] = pixel(x, y);
			}
		}
		return result;
	}

	static String validateName(String name) {
		Objects.requireNonNull(name, "A name is required.");
		if (name.length() > 64) {
			throw new IllegalArgumentException("Names must be at most 64 characters.");
		}
		String value = name.strip();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("Enter a nonempty name.");
		}
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isISOControl(character)) {
				throw new IllegalArgumentException("Names cannot contain control characters.");
			}
			if (Character.isHighSurrogate(character)) {
				if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
					throw new IllegalArgumentException("Names must contain valid Unicode.");
				}
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException("Names must contain valid Unicode.");
			}
		}
		return value;
	}
}
