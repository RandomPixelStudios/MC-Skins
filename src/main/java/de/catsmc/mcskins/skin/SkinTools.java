package de.catsmc.mcskins.skin;

import java.util.BitSet;
import java.util.random.RandomGenerator;

/** Texture-space helpers for brushes, mirroring, shapes and color tweaks. */
public final class SkinTools {
	private SkinTools() {
	}

	/**
	 * A square brush of {@code size} pixels centered on a pixel. On the model it stays inside the face that owns
	 * the pixel so strokes never bleed into unrelated texture regions.
	 */
	public static BitSet brush(SkinGeometry geometry, int pixel, int size, boolean clampToFace) {
		var result = new BitSet(4096);
		int x = pixel & 63;
		int y = pixel >>> 6;
		SkinGeometry.Face face = clampToFace ? geometry.faceAt(x, y) : null;
		int start = -(size - 1) / 2;
		for (int dy = start; dy < start + size; dy++) {
			for (int dx = start; dx < start + size; dx++) {
				int px = x + dx;
				int py = y + dy;
				if (px < 0 || py < 0 || px >= 64 || py >= 64 || face != null && !face.contains(px, py)) {
					continue;
				}
				result.set(py * 64 + px);
			}
		}
		return result;
	}

	/** Adds the mirrored counterpart of every pixel that lies on the model. */
	public static BitSet mirrored(SkinGeometry geometry, BitSet pixels) {
		var result = (BitSet) pixels.clone();
		for (int pixel = pixels.nextSetBit(0); pixel >= 0; pixel = pixels.nextSetBit(pixel + 1)) {
			int mirror = geometry.mirror(pixel);
			if (mirror >= 0) {
				result.set(mirror);
			}
		}
		return result;
	}

	/** Pixels on a straight line between two texture pixels. */
	public static BitSet line(int from, int to) {
		var result = new BitSet(4096);
		int x0 = from & 63;
		int y0 = from >>> 6;
		int x1 = to & 63;
		int y1 = to >>> 6;
		int dx = Math.abs(x1 - x0);
		int dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int error = dx + dy;
		while (true) {
			result.set(y0 * 64 + x0);
			if (x0 == x1 && y0 == y1) {
				return result;
			}
			int doubled = 2 * error;
			if (doubled >= dy) {
				error += dy;
				x0 += sx;
			}
			if (doubled <= dx) {
				error += dx;
				y0 += sy;
			}
		}
	}

	/** A rectangle between two corner pixels, outlined or filled. */
	public static BitSet rectangle(int from, int to, boolean filled) {
		var result = new BitSet(4096);
		int left = Math.min(from & 63, to & 63);
		int right = Math.max(from & 63, to & 63);
		int top = Math.min(from >>> 6, to >>> 6);
		int bottom = Math.max(from >>> 6, to >>> 6);
		for (int y = top; y <= bottom; y++) {
			for (int x = left; x <= right; x++) {
				if (filled || x == left || x == right || y == top || y == bottom) {
					result.set(y * 64 + x);
				}
			}
		}
		return result;
	}

	/** Thickens a set of pixels by a square brush, e.g. for lines drawn with a larger brush. */
	public static BitSet thicken(BitSet pixels, int size) {
		if (size <= 1) {
			return pixels;
		}
		var result = new BitSet(4096);
		int start = -(size - 1) / 2;
		for (int pixel = pixels.nextSetBit(0); pixel >= 0; pixel = pixels.nextSetBit(pixel + 1)) {
			int x = pixel & 63;
			int y = pixel >>> 6;
			for (int dy = start; dy < start + size; dy++) {
				for (int dx = start; dx < start + size; dx++) {
					int px = x + dx;
					int py = y + dy;
					if (px >= 0 && py >= 0 && px < 64 && py < 64) {
						result.set(py * 64 + px);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Flood fill of equal colors starting at a pixel. With a face, the fill stays inside it; otherwise it stays
	 * inside the texture.
	 */
	public static BitSet fill(int[] image, int start, SkinGeometry.Face bounds) {
		var result = new BitSet(4096);
		int target = image[start];
		if (bounds != null && !bounds.contains(start & 63, start >>> 6)) {
			return result;
		}
		// Each pixel is marked when queued, so the queue never holds more than the 4096 texture pixels.
		int[] queue = new int[4096];
		int head = 0;
		int tail = 0;
		queue[tail++] = start;
		result.set(start);
		while (head < tail) {
			int index = queue[head++];
			int x = index & 63;
			int y = index >>> 6;
			if (x > 0) {
				tail = visit(image, target, bounds, result, queue, tail, index - 1);
			}
			if (x < 63) {
				tail = visit(image, target, bounds, result, queue, tail, index + 1);
			}
			if (y > 0) {
				tail = visit(image, target, bounds, result, queue, tail, index - 64);
			}
			if (y < 63) {
				tail = visit(image, target, bounds, result, queue, tail, index + 64);
			}
		}
		return result;
	}

	private static int visit(int[] image, int target, SkinGeometry.Face bounds, BitSet result, int[] queue, int tail, int index) {
		if (result.get(index) || image[index] != target || bounds != null && !bounds.contains(index & 63, index >>> 6)) {
			return tail;
		}
		result.set(index);
		queue[tail] = index;
		return tail + 1;
	}

	/** Moves a color toward white (positive amount) or black (negative), keeping alpha. */
	public static int shade(int color, double amount) {
		if (color >>> 24 == 0) {
			return color;
		}
		int target = amount >= 0 ? 255 : 0;
		double weight = Math.min(1, Math.abs(amount));
		int red = (int) Math.round(((color >>> 16) & 255) + (target - ((color >>> 16) & 255)) * weight);
		int green = (int) Math.round(((color >>> 8) & 255) + (target - ((color >>> 8) & 255)) * weight);
		int blue = (int) Math.round((color & 255) + (target - (color & 255)) * weight);
		return color & 0xFF000000 | red << 16 | green << 8 | blue;
	}

	/** A random brightness variation of a color, for natural-looking texture noise. */
	public static int noise(int color, double strength, RandomGenerator random) {
		return shade(color, (random.nextDouble() * 2 - 1) * strength);
	}
}
