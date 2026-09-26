package de.catsmc.mcskins.skin;

import java.util.Objects;

public final class SkinLayer {
	private final String name;
	private final boolean visible;
	private final int[] pixels;
	private final int offsetX;
	private final int offsetY;

	public SkinLayer(String name, int[] pixels) {
		this(name, true, pixels, 0, 0);
	}

	SkinLayer(String name, boolean visible, int[] pixels, int offsetX, int offsetY) {
		this(name, visible, pixels, offsetX, offsetY, false);
	}

	static SkinLayer adopting(String name, boolean visible, int[] pixels, int offsetX, int offsetY) {
		return new SkinLayer(name, visible, pixels, offsetX, offsetY, true);
	}

	private SkinLayer(String name, boolean visible, int[] pixels, int offsetX, int offsetY, boolean owned) {
		this.name = validateName(name);
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
