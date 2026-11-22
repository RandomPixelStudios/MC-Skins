package de.catsmc.mcskins.skin;

import java.util.Objects;

/**
 * Copies whole body parts within a 64 x 64 canvas using the model's UV layout: one part onto another (optionally
 * mirrored, the way vanilla builds the left limbs of old 64 x 32 skins) and base onto overlay or back.
 */
public final class PartCopy {
	public enum Shells { BASE, OVERLAY, BOTH }

	private PartCopy() {
	}

	/**
	 * Copies part {@code from} onto part {@code to}. Every face is copied onto the same face of the target; when the
	 * boxes differ in size (an arm onto a leg on a slim model) the face is scaled with nearest-neighbor sampling.
	 * Mirroring swaps the left and right faces and flips every face horizontally, so a right arm becomes a matching
	 * left arm. Target pixels are replaced, transparent ones included.
	 */
	public static int[] copyPart(int[] canvas, boolean slim, SkinGeometry.Part from, SkinGeometry.Part to,
		boolean mirror, Shells shells) {
		check(canvas);
		Objects.requireNonNull(from);
		Objects.requireNonNull(to);
		int[] result = canvas.clone();
		SkinGeometry geometry = SkinGeometry.of(slim);
		for (boolean outer : new boolean[]{false, true}) {
			if (outer ? shells == Shells.BASE : shells == Shells.OVERLAY) {
				continue;
			}
			SkinGeometry.Box source = box(geometry, from, outer);
			SkinGeometry.Box target = box(geometry, to, outer);
			for (SkinGeometry.Side side : SkinGeometry.Side.values()) {
				SkinGeometry.Side sourceSide = mirror ? opposite(side) : side;
				copyFace(canvas, result, SkinGeometry.face(source, sourceSide), SkinGeometry.face(target, side), mirror, false);
			}
		}
		return result;
	}

	/**
	 * Copies the base shell of {@code part} (null for every part) onto its overlay, replacing the overlay, or the
	 * overlay onto the base. Overlay onto base only draws the visible overlay pixels over the base, so the base keeps
	 * its color where the overlay is empty.
	 */
	public static int[] copyShell(int[] canvas, boolean slim, SkinGeometry.Part part, boolean toOverlay) {
		check(canvas);
		int[] result = canvas.clone();
		SkinGeometry geometry = SkinGeometry.of(slim);
		for (SkinGeometry.Part each : SkinGeometry.Part.values()) {
			if (part != null && part != each) {
				continue;
			}
			SkinGeometry.Box base = box(geometry, each, false);
			SkinGeometry.Box overlay = box(geometry, each, true);
			for (SkinGeometry.Side side : SkinGeometry.Side.values()) {
				SkinGeometry.Face baseFace = SkinGeometry.face(base, side);
				SkinGeometry.Face overlayFace = SkinGeometry.face(overlay, side);
				if (toOverlay) {
					copyFace(canvas, result, baseFace, overlayFace, false, false);
				} else {
					copyFace(canvas, result, overlayFace, baseFace, false, true);
				}
			}
		}
		return result;
	}

	private static SkinGeometry.Box box(SkinGeometry geometry, SkinGeometry.Part part, boolean outer) {
		for (SkinGeometry.Box box : geometry.boxes()) {
			if (box.part() == part && box.outer() == outer) {
				return box;
			}
		}
		throw new IllegalStateException("Missing model box " + part);
	}

	static SkinGeometry.Side opposite(SkinGeometry.Side side) {
		return switch (side) {
			case RIGHT -> SkinGeometry.Side.LEFT;
			case LEFT -> SkinGeometry.Side.RIGHT;
			default -> side;
		};
	}

	private static void copyFace(int[] source, int[] target, SkinGeometry.Face from, SkinGeometry.Face to,
		boolean flip, boolean composite) {
		for (int row = 0; row < to.rows(); row++) {
			int sourceRow = (2 * row + 1) * from.rows() / (2 * to.rows());
			for (int column = 0; column < to.columns(); column++) {
				int sourceColumn = (2 * column + 1) * from.columns() / (2 * to.columns());
				if (flip) {
					sourceColumn = from.columns() - 1 - sourceColumn;
				}
				int color = source[(from.v() + sourceRow) * 64 + from.u() + sourceColumn];
				int index = (to.v() + row) * 64 + to.u() + column;
				target[index] = composite ? SkinDocument.over(color, source[index]) : color;
			}
		}
	}

	private static void check(int[] canvas) {
		if (canvas == null || canvas.length != 4096) {
			throw new IllegalArgumentException("Canvas must contain 64 x 64 pixels.");
		}
	}
}
