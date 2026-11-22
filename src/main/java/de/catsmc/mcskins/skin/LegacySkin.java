package de.catsmc.mcskins.skin;

/** Converts old 64 x 32 skins to the 64 x 64 layout, the same way the game does when it loads one. */
public final class LegacySkin {
	private LegacySkin() {
	}

	/**
	 * Takes the 64 x 32 pixels (row-major, 2048 values, or a 4096-value canvas whose lower half is ignored) and returns
	 * a 64 x 64 skin: the left arm and leg are mirrored copies of the right ones, and a hat layer that is completely
	 * opaque (old skins often filled it with a solid color) is cleared, as vanilla does.
	 */
	public static int[] toModern(int[] legacy) {
		if (legacy == null || legacy.length != 2048 && legacy.length != 4096) {
			throw new IllegalArgumentException("Expected a 64 x 32 skin.");
		}
		int[] pixels = new int[4096];
		System.arraycopy(legacy, 0, pixels, 0, 2048);
		pixels = PartCopy.copyPart(pixels, false, SkinGeometry.Part.RIGHT_LEG, SkinGeometry.Part.LEFT_LEG, true, PartCopy.Shells.BASE);
		pixels = PartCopy.copyPart(pixels, false, SkinGeometry.Part.RIGHT_ARM, SkinGeometry.Part.LEFT_ARM, true, PartCopy.Shells.BASE);
		clearOpaqueHat(pixels);
		return pixels;
	}

	/** Vanilla's "Notch transparency hack": a hat area without a single transparent pixel is treated as empty. */
	private static void clearOpaqueHat(int[] pixels) {
		for (int y = 0; y < 16; y++) {
			for (int x = 32; x < 64; x++) {
				if (pixels[y * 64 + x] >>> 24 < 128) {
					return;
				}
			}
		}
		for (int y = 0; y < 16; y++) {
			for (int x = 32; x < 64; x++) {
				pixels[y * 64 + x] = 0;
			}
		}
	}
}
