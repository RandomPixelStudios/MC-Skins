package de.catsmc.mcskins.skin;

import java.util.BitSet;
import java.util.Objects;

/**
 * A rectangular piece of a layer cut out by a selection: its position on the 64 x 64 canvas, its pixels and which
 * of them were selected. Clips are immutable; transforms return new clips.
 */
public final class Clip {
	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final int[] pixels;
	private final BitSet mask;

	public Clip(int x, int y, int width, int height, int[] pixels, BitSet mask) {
		if (width < 1 || height < 1 || width > 64 || height > 64) {
			throw new IllegalArgumentException("Clips must be between 1 x 1 and 64 x 64 pixels.");
		}
		if (pixels.length != width * height) {
			throw new IllegalArgumentException("Pixel count does not match the clip size.");
		}
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.pixels = pixels.clone();
		this.mask = (BitSet) Objects.requireNonNull(mask).clone();
	}

	/** The selected pixels of {@code canvas} (64 x 64) as a clip, or null when nothing is selected. */
	public static Clip of(int[] canvas, BitSet selection) {
		if (selection == null || selection.isEmpty()) {
			return null;
		}
		int left = 64;
		int top = 64;
		int right = -1;
		int bottom = -1;
		for (int pixel = selection.nextSetBit(0); pixel >= 0 && pixel < 4096; pixel = selection.nextSetBit(pixel + 1)) {
			left = Math.min(left, pixel & 63);
			right = Math.max(right, pixel & 63);
			top = Math.min(top, pixel >>> 6);
			bottom = Math.max(bottom, pixel >>> 6);
		}
		if (right < 0) {
			return null;
		}
		int width = right - left + 1;
		int height = bottom - top + 1;
		int[] pixels = new int[width * height];
		var mask = new BitSet(width * height);
		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int source = (top + row) * 64 + left + column;
				if (selection.get(source)) {
					pixels[row * width + column] = canvas[source];
					mask.set(row * width + column);
				}
			}
		}
		return new Clip(left, top, width, height, pixels, mask);
	}

	/** A copy of {@code canvas} with the selected pixels made transparent. */
	public static int[] cleared(int[] canvas, BitSet selection) {
		int[] result = canvas.clone();
		for (int pixel = selection.nextSetBit(0); pixel >= 0 && pixel < 4096; pixel = selection.nextSetBit(pixel + 1)) {
			result[pixel] = 0;
		}
		return result;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The pixel at clip coordinates; 0 for unselected positions. */
	public int pixel(int column, int row) {
		return mask.get(row * width + column) ? pixels[row * width + column] : 0;
	}

	public boolean selected(int column, int row) {
		return mask.get(row * width + column);
	}

	/** Moves the clip, keeping at least one of its columns and rows on the canvas. */
	public Clip moved(int dx, int dy) {
		int nx = Math.clamp((long) x + dx, 1 - width, 63);
		int ny = Math.clamp((long) y + dy, 1 - height, 63);
		return at(nx, ny);
	}

	public Clip at(int nx, int ny) {
		return new Clip(nx, ny, width, height, pixels, mask);
	}

	public Clip flipHorizontal() {
		return remap(width, height, x, y, (column, row) -> row * width + width - 1 - column);
	}

	public Clip flipVertical() {
		return remap(width, height, x, y, (column, row) -> (height - 1 - row) * width + column);
	}

	/** Rotates 90 degrees clockwise around the clip's center. */
	public Clip rotateClockwise() {
		int nx = x + (width - height) / 2;
		int ny = y + (height - width) / 2;
		// New position (column, row) in a height x width clip shows old (row, height - 1 - column).
		return remap(height, width, nx, ny, (column, row) -> (height - 1 - column) * width + row);
	}

	private interface Source {
		int index(int column, int row);
	}

	private Clip remap(int newWidth, int newHeight, int nx, int ny, Source source) {
		int[] result = new int[newWidth * newHeight];
		var resultMask = new BitSet(newWidth * newHeight);
		for (int row = 0; row < newHeight; row++) {
			for (int column = 0; column < newWidth; column++) {
				int from = source.index(column, row);
				result[row * newWidth + column] = pixels[from];
				if (mask.get(from)) {
					resultMask.set(row * newWidth + column);
				}
			}
		}
		return new Clip(nx, ny, newWidth, newHeight, result, resultMask);
	}

	/** A copy of {@code canvas} with this clip's selected pixels written over it; pixels off the canvas are dropped. */
	public int[] stampOnto(int[] canvas) {
		int[] result = canvas.clone();
		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int cx = x + column;
				int cy = y + row;
				if (cx >= 0 && cy >= 0 && cx < 64 && cy < 64 && mask.get(row * width + column)) {
					result[cy * 64 + cx] = pixels[row * width + column];
				}
			}
		}
		return result;
	}

	/** The canvas pixels this clip covers (selected positions only), clipped to the canvas. */
	public BitSet canvasMask() {
		var result = new BitSet(4096);
		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int cx = x + column;
				int cy = y + row;
				if (cx >= 0 && cy >= 0 && cx < 64 && cy < 64 && mask.get(row * width + column)) {
					result.set(cy * 64 + cx);
				}
			}
		}
		return result;
	}
}
