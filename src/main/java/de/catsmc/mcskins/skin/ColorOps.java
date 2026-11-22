package de.catsmc.mcskins.skin;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Color math for the editor: HSV, gradients, hue-shifted shading, color replacement and palette extraction. */
public final class ColorOps {
	/** 4 x 4 Bayer matrix, thresholds in sixteenths. */
	private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
	/** Highlights drift toward warm yellow-orange, shadows toward cool blue. */
	private static final float WARM_HUE = 50 / 360F;
	private static final float COOL_HUE = 235 / 360F;

	private ColorOps() {
	}

	/** {hue, saturation, value}, each 0-1. Gray colors report hue 0. */
	public static float[] hsv(int argb) {
		float red = ((argb >>> 16) & 255) / 255F;
		float green = ((argb >>> 8) & 255) / 255F;
		float blue = (argb & 255) / 255F;
		float maximum = Math.max(red, Math.max(green, blue));
		float minimum = Math.min(red, Math.min(green, blue));
		float delta = maximum - minimum;
		float hue = 0;
		if (delta != 0) {
			if (maximum == red) {
				hue = (green - blue) / delta / 6;
			} else if (maximum == green) {
				hue = (blue - red) / delta / 6 + 1 / 3F;
			} else {
				hue = (red - green) / delta / 6 + 2 / 3F;
			}
			if (hue < 0) {
				hue += 1;
			}
		}
		return new float[]{hue, maximum == 0 ? 0 : delta / maximum, maximum};
	}

	/** An ARGB color from HSV (each 0-1; hue wraps) with the alpha of {@code alpha}'s top byte. */
	public static int fromHsv(float hue, float saturation, float value, int alpha) {
		float s = Math.clamp(saturation, 0F, 1F);
		float v = Math.clamp(value, 0F, 1F);
		float sector = (hue - (float) Math.floor(hue)) * 6;
		int face = Math.min(5, (int) sector);
		float fraction = sector - face;
		float low = v * (1 - s);
		float falling = v * (1 - s * fraction);
		float rising = v * (1 - s * (1 - fraction));
		float red;
		float green;
		float blue;
		switch (face) {
			case 1 -> { red = falling; green = v; blue = low; }
			case 2 -> { red = low; green = v; blue = rising; }
			case 3 -> { red = low; green = falling; blue = v; }
			case 4 -> { red = rising; green = low; blue = v; }
			case 5 -> { red = v; green = low; blue = falling; }
			default -> { red = v; green = rising; blue = low; }
		}
		return alpha & 0xFF000000 | Math.round(red * 255) << 16 | Math.round(green * 255) << 8 | Math.round(blue * 255);
	}

	/** Linear interpolation of all four ARGB channels; {@code t} is clamped to 0-1. */
	public static int lerp(int from, int to, double t) {
		double weight = Math.clamp(t, 0, 1);
		int result = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int a = (from >>> shift) & 255;
			int b = (to >>> shift) & 255;
			result |= (int) Math.round(a + (b - a) * weight) << shift;
		}
		return result;
	}

	/** Position 0-1 of pixel (x, y) along the line from (x0, y0) to (x1, y1), clamped at both ends. */
	public static double gradientPosition(int x, int y, int x0, int y0, int x1, int y1) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double length = dx * dx + dy * dy;
		if (length == 0) {
			return 0;
		}
		return Math.clamp(((x - x0) * dx + (y - y0) * dy) / length, 0, 1);
	}

	/**
	 * The gradient color of a pixel. Without dithering colors blend smoothly; with dithering every pixel is either
	 * {@code from} or {@code to}, chosen by an ordered (Bayer) pattern, which suits pixel art.
	 */
	public static int gradient(int x, int y, int x0, int y0, int x1, int y1, int from, int to, boolean dither) {
		double t = gradientPosition(x, y, x0, y0, x1, y1);
		if (!dither) {
			return lerp(from, to, t);
		}
		double threshold = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16;
		return t > threshold ? to : from;
	}

	/**
	 * Lightens or darkens a color with a slight hue shift: highlights get warmer and a bit less saturated, shadows
	 * cooler and a bit more saturated. {@code strength} is 0-1; alpha is kept, transparent pixels stay untouched.
	 */
	public static int shadeShifted(int color, double strength, boolean lighten) {
		if (color >>> 24 == 0) {
			return color;
		}
		float amount = (float) Math.clamp(strength, 0, 1);
		float[] hsv = hsv(color);
		float hue = hsv[0];
		float saturation = hsv[1];
		float value = hsv[2];
		// Gray pixels have no hue to shift; they only get brighter or darker.
		boolean colored = saturation > 0.02F;
		if (colored) {
			hue = towards(hue, lighten ? WARM_HUE : COOL_HUE, amount * 0.12F);
		}
		if (lighten) {
			value += (1 - value) * amount;
			if (colored) {
				saturation = Math.max(0, saturation - saturation * amount * 0.25F);
			}
		} else {
			value -= value * amount;
			if (colored) {
				saturation = Math.min(1, saturation + (1 - saturation) * amount * 0.15F);
			}
		}
		return fromHsv(hue, saturation, value, color);
	}

	/** Moves a hue toward a target along the shorter way round the color wheel, by at most {@code step}. */
	static float towards(float hue, float target, float step) {
		float difference = target - hue;
		if (difference > 0.5F) {
			difference -= 1;
		} else if (difference < -0.5F) {
			difference += 1;
		}
		float moved = Math.abs(difference) <= step ? target : hue + Math.signum(difference) * step;
		return moved - (float) Math.floor(moved);
	}

	/** Whether two ARGB colors differ by at most {@code tolerance} (0-255) in every channel, alpha included. */
	public static boolean similar(int a, int b, int tolerance) {
		if (a == b) {
			return true;
		}
		if (a >>> 24 == 0 && b >>> 24 == 0) {
			return true;
		}
		for (int shift = 0; shift <= 24; shift += 8) {
			if (Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)) > tolerance) {
				return false;
			}
		}
		return true;
	}

	/** Every pixel of {@code image} within {@code tolerance} of {@code target}, optionally limited to {@code mask}. */
	public static BitSet matching(int[] image, int target, int tolerance, BitSet mask) {
		var result = new BitSet(image.length);
		for (int index = 0; index < image.length; index++) {
			if ((mask == null || mask.get(index)) && similar(image[index], target, tolerance)) {
				result.set(index);
			}
		}
		return result;
	}

	/**
	 * The most used visible colors of an image (alpha ignored, so shades of one color count together), most frequent
	 * first; colors within {@code merge} of a more frequent one are folded into it.
	 */
	public static List<Integer> extractPalette(int[] image, int maximum, int merge) {
		Map<Integer, Integer> counts = new HashMap<>();
		for (int pixel : image) {
			if (pixel >>> 24 != 0) {
				counts.merge(pixel | 0xFF000000, 1, Integer::sum);
			}
		}
		var ordered = new ArrayList<>(counts.entrySet());
		ordered.sort(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder())
			.thenComparing(Map.Entry.comparingByKey()));
		var result = new ArrayList<Integer>();
		for (var entry : ordered) {
			int color = entry.getKey();
			if (result.stream().noneMatch(known -> similar(known, color, merge))) {
				result.add(color);
				if (result.size() >= maximum) {
					break;
				}
			}
		}
		return result;
	}

	/** Sorts colors by hue band, then brightness, so an extracted palette reads like a color ramp. */
	public static List<Integer> sortForDisplay(List<Integer> colors) {
		var sorted = new ArrayList<>(colors);
		sorted.sort(Comparator.<Integer>comparingInt(color -> {
			float[] hsv = hsv(color);
			return hsv[1] < 0.12F ? -1 : (int) (hsv[0] * 12);
		}).thenComparingDouble(color -> hsv(color)[2]));
		return sorted;
	}
}
