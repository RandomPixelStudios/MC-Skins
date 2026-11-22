package de.catsmc.mcskins.skin;

import java.util.BitSet;

/**
 * Finds pixels the game will not show the way the editor does: the base shell is drawn fully opaque (transparent
 * pixels turn black, semi-transparent ones lose their transparency), and paint outside the model's UV areas never
 * appears at all.
 */
public final class SkinCheck {
	private SkinCheck() {
	}

	public record Report(BitSet transparentBase, BitSet translucentBase, BitSet outsideUv) {
		public Report {
			transparentBase = (BitSet) transparentBase.clone();
			translucentBase = (BitSet) translucentBase.clone();
			outsideUv = (BitSet) outsideUv.clone();
		}

		@Override
		public BitSet transparentBase() {
			return (BitSet) transparentBase.clone();
		}

		@Override
		public BitSet translucentBase() {
			return (BitSet) translucentBase.clone();
		}

		@Override
		public BitSet outsideUv() {
			return (BitSet) outsideUv.clone();
		}

		/** Every problem pixel, for highlighting. */
		public BitSet all() {
			BitSet result = transparentBase();
			result.or(translucentBase);
			result.or(outsideUv);
			return result;
		}

		public boolean clean() {
			return transparentBase.isEmpty() && translucentBase.isEmpty() && outsideUv.isEmpty();
		}
	}

	public static Report check(int[] flattened, boolean slim) {
		if (flattened == null || flattened.length != 4096) {
			throw new IllegalArgumentException("Skin must contain 64 x 64 pixels.");
		}
		SkinGeometry geometry = SkinGeometry.of(slim);
		var transparent = new BitSet(4096);
		var translucent = new BitSet(4096);
		var outside = new BitSet(4096);
		for (int index = 0; index < 4096; index++) {
			int alpha = flattened[index] >>> 24;
			SkinGeometry.Face face = geometry.faceAt(index & 63, index >>> 6);
			if (face == null) {
				if (alpha != 0) {
					outside.set(index);
				}
			} else if (!face.box().outer()) {
				if (alpha == 0) {
					transparent.set(index);
				} else if (alpha < 255) {
					translucent.set(index);
				}
			}
		}
		return new Report(transparent, translucent, outside);
	}

	/**
	 * A layer to put under all others that makes the base shell opaque: semi-transparent pixels get their own color
	 * at full opacity below them, holes get the nearest opaque color of the same face (black if the face has none).
	 */
	public static int[] baseFixLayer(int[] flattened, boolean slim, Report report) {
		SkinGeometry geometry = SkinGeometry.of(slim);
		int[] layer = new int[4096];
		BitSet translucent = report.translucentBase();
		for (int index = translucent.nextSetBit(0); index >= 0; index = translucent.nextSetBit(index + 1)) {
			layer[index] = flattened[index] | 0xFF000000;
		}
		BitSet transparent = report.transparentBase();
		for (int index = transparent.nextSetBit(0); index >= 0; index = transparent.nextSetBit(index + 1)) {
			layer[index] = nearestOpaque(flattened, geometry.faceAt(index & 63, index >>> 6), index);
		}
		return layer;
	}

	private static int nearestOpaque(int[] pixels, SkinGeometry.Face face, int index) {
		int x = index & 63;
		int y = index >>> 6;
		int best = 0xFF000000;
		int bestDistance = Integer.MAX_VALUE;
		for (int row = face.v(); row < face.v() + face.rows(); row++) {
			for (int column = face.u(); column < face.u() + face.columns(); column++) {
				int color = pixels[row * 64 + column];
				if (color >>> 24 == 0) {
					continue;
				}
				int distance = (column - x) * (column - x) + (row - y) * (row - y);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = color | 0xFF000000;
				}
			}
		}
		return best;
	}
}
