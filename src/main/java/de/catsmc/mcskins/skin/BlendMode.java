package de.catsmc.mcskins.skin;

/**
 * How a layer's colors combine with the layers below it. Blending follows the W3C compositing model: where the
 * backdrop is transparent the layer's own color shows; where it is opaque the blended color shows; the result is then
 * placed over the backdrop with the layer's alpha.
 */
public enum BlendMode {
	NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD;

	/** Stable file id; the order of the enum may change, these numbers may not. */
	public int id() {
		return ordinal();
	}

	public static BlendMode byId(int id) {
		BlendMode[] values = values();
		if (id < 0 || id >= values.length) {
			throw new IllegalArgumentException("Unknown blend mode " + id + ".");
		}
		return values[id];
	}

	public BlendMode next() {
		return values()[(ordinal() + 1) % values().length];
	}

	/** Composites {@code foreground} (ARGB, layer opacity already applied) onto {@code background}. */
	public int composite(int foreground, int background) {
		int alpha = foreground >>> 24;
		if (this == NORMAL || alpha == 0 || background >>> 24 == 0) {
			return SkinDocument.over(foreground, background);
		}
		int backdrop = background >>> 24;
		int mixed = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			int source = (foreground >>> shift) & 255;
			int below = (background >>> shift) & 255;
			int blended = channel(source, below);
			// Where the backdrop is only partly opaque, part of the layer's own color still shows through.
			int channel = (source * (255 - backdrop) + blended * backdrop + 127) / 255;
			mixed |= channel << shift;
		}
		return SkinDocument.over(alpha << 24 | mixed, background);
	}

	int channel(int source, int backdrop) {
		return switch (this) {
			case NORMAL -> source;
			case MULTIPLY -> (source * backdrop + 127) / 255;
			case SCREEN -> 255 - ((255 - source) * (255 - backdrop) + 127) / 255;
			case OVERLAY -> backdrop < 128
				? (2 * source * backdrop + 127) / 255
				: 255 - (2 * (255 - source) * (255 - backdrop) + 127) / 255;
			case ADD -> Math.min(255, source + backdrop);
		};
	}
}
