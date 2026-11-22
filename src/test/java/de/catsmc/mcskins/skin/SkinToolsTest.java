package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class SkinToolsTest {
	private static int index(int x, int y) {
		return y * 64 + x;
	}

	@Test
	void mirrorMapsHeadFrontColumnsAndIsAnInvolution() {
		SkinGeometry geometry = SkinGeometry.of(false);
		assertEquals(index(15, 8), geometry.mirror(index(8, 8)));
		for (boolean slim : new boolean[]{false, true}) {
			SkinGeometry shape = SkinGeometry.of(slim);
			for (int pixel = 0; pixel < 4096; pixel++) {
				int mirror = shape.mirror(pixel);
				if (shape.faceAt(pixel & 63, pixel >>> 6) != null) {
					assertTrue(mirror >= 0, "every model pixel has a mirror: " + pixel + " slim=" + slim);
					assertEquals(pixel, shape.mirror(mirror));
				} else {
					assertEquals(-1, mirror);
				}
			}
		}
	}

	@Test
	void mirrorSwapsArmsAndLegs() {
		SkinGeometry geometry = SkinGeometry.of(false);
		// Right arm front (44..47, 20..31) maps to the left arm front (36..39, 52..63), reversed.
		assertEquals(index(39, 52), geometry.mirror(index(44, 20)));
		// Right leg outer side (0..3, 20..31) maps to the left leg outer side (24..27, 52..63).
		assertEquals(index(27, 52), geometry.mirror(index(0, 20)));
		// Head right side maps to head left side.
		assertEquals(index(23, 8), geometry.mirror(index(0, 8)));
	}

	@Test
	void brushStaysOnFace() {
		SkinGeometry geometry = SkinGeometry.of(false);
		BitSet brush = SkinTools.brush(geometry, index(8, 8), 3, true);
		assertEquals(4, brush.cardinality());
		BitSet free = SkinTools.brush(geometry, index(8, 8), 3, false);
		assertEquals(9, free.cardinality());
	}

	@Test
	void lineAndRectangle() {
		assertEquals(5, SkinTools.line(index(0, 0), index(4, 4)).cardinality());
		assertEquals(8, SkinTools.rectangle(index(0, 0), index(2, 2), false).cardinality());
		assertEquals(9, SkinTools.rectangle(index(2, 2), index(0, 0), true).cardinality());
	}

	@Test
	void fillStaysInsideFace() {
		int[] image = new int[4096];
		SkinGeometry geometry = SkinGeometry.of(false);
		BitSet filled = SkinTools.fill(image, index(10, 10), geometry.faceAt(10, 10));
		assertEquals(64, filled.cardinality());
	}

	@Test
	void shadeKeepsAlphaAndClamps() {
		assertEquals(0x80FFFFFF, SkinTools.shade(0x80808080, 1));
		assertEquals(0xFF000000, SkinTools.shade(0xFF808080, -5));
		assertEquals(0, SkinTools.shade(0, 0.5));
	}

	@Test
	void fillOfAnEmptyTextureReachesEveryPixelOnce() {
		BitSet filled = SkinTools.fill(new int[4096], index(0, 0), null);
		assertEquals(4096, filled.cardinality());
		SkinGeometry.Face face = SkinGeometry.of(false).faceAt(10, 10);
		assertTrue(SkinTools.fill(new int[4096], index(0, 0), face).isEmpty(), "a start outside the face fills nothing");
	}
}
