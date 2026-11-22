package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditingLogicTest {
	private static int at(int x, int y) {
		return y * 64 + x;
	}

	// ---- selection ----------------------------------------------------------------------------------------

	private static Clip sample() {
		// A 3 x 2 clip at (10, 20):  A B C / D E F, where F is not selected.
		int[] canvas = new int[4096];
		int[] colors = {0xFF00000A, 0xFF00000B, 0xFF00000C, 0xFF00000D, 0xFF00000E, 0xFF00000F};
		var selection = new BitSet();
		for (int index = 0; index < 6; index++) {
			int pixel = at(10 + index % 3, 20 + index / 3);
			canvas[pixel] = colors[index];
			if (index != 5) {
				selection.set(pixel);
			}
		}
		return Clip.of(canvas, selection);
	}

	@Test
	void clipCapturesBoundsAndMask() {
		Clip clip = sample();
		assertEquals(10, clip.x());
		assertEquals(20, clip.y());
		assertEquals(3, clip.width());
		assertEquals(2, clip.height());
		assertEquals(0xFF00000E, clip.pixel(1, 1));
		assertFalse(clip.selected(2, 1));
		assertEquals(0, clip.pixel(2, 1));
		assertNull(Clip.of(new int[4096], new BitSet()));
	}

	@Test
	void flipsMirrorTheClip() {
		Clip horizontal = sample().flipHorizontal();
		assertEquals(0xFF00000C, horizontal.pixel(0, 0));
		assertEquals(0xFF00000A, horizontal.pixel(2, 0));
		assertFalse(horizontal.selected(0, 1), "the unselected corner moves with the flip");
		Clip vertical = sample().flipVertical();
		assertEquals(0xFF00000D, vertical.pixel(0, 0));
		assertEquals(0xFF00000A, vertical.pixel(0, 1));
		assertFalse(vertical.selected(2, 0));
	}

	@Test
	void rotateTurnsClockwiseAroundTheCenter() {
		Clip rotated = sample().rotateClockwise();
		assertEquals(2, rotated.width());
		assertEquals(3, rotated.height());
		// Clockwise: the old bottom-left (D) is now top-left, the old top-left (A) top-right.
		assertEquals(0xFF00000D, rotated.pixel(0, 0));
		assertEquals(0xFF00000A, rotated.pixel(1, 0));
		assertEquals(0xFF00000C, rotated.pixel(1, 2));
		assertFalse(rotated.selected(0, 2));
		assertEquals(10, rotated.x());
		assertEquals(20, rotated.y());
		Clip full = rotated.rotateClockwise().rotateClockwise().rotateClockwise();
		assertEquals(0xFF00000A, full.pixel(0, 0));
		assertEquals(0xFF00000E, full.pixel(1, 1));
	}

	@Test
	void stampAndMoveWriteOnlySelectedPixelsInsideTheCanvas() {
		Clip clip = sample().moved(5, -3);
		int[] base = new int[4096];
		base[at(17, 18)] = 0xFF123456;
		int[] stamped = clip.stampOnto(base);
		assertEquals(0xFF00000A, stamped[at(15, 17)]);
		assertEquals(0xFF123456, stamped[at(17, 18)], "the unselected corner keeps what was below");
		Clip offCanvas = sample().moved(-100, 0);
		assertEquals(-2, offCanvas.x(), "at least one column stays on the canvas");
		assertEquals(1, offCanvas.canvasMask().cardinality());
		int[] cleared = Clip.cleared(stamped, clip.canvasMask());
		assertEquals(0, cleared[at(15, 17)]);
	}

	// ---- colors -------------------------------------------------------------------------------------------

	@Test
	void hsvRoundTrips() {
		for (int color : new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF808080, 0xFFB98868, 0x80123456}) {
			float[] hsv = ColorOps.hsv(color);
			assertEquals(color, ColorOps.fromHsv(hsv[0], hsv[1], hsv[2], color));
		}
	}

	@Test
	void replaceMatchesWithinTolerance() {
		int[] image = new int[4096];
		image[0] = 0xFF808080;
		image[1] = 0xFF848080;
		image[2] = 0xFF908080;
		image[3] = 0x00FFFFFF;
		BitSet exact = ColorOps.matching(image, 0xFF808080, 0, null);
		assertEquals(1, exact.cardinality());
		BitSet near = ColorOps.matching(image, 0xFF808080, 4, null);
		assertTrue(near.get(0) && near.get(1) && !near.get(2));
		BitSet transparent = ColorOps.matching(image, 0, 0, null);
		assertTrue(transparent.get(3), "all fully transparent pixels count as the same color");
		var mask = new BitSet();
		mask.set(1);
		assertEquals(1, ColorOps.matching(image, 0xFF808080, 4, mask).cardinality());
	}

	@Test
	void gradientBlendsAlongTheLine() {
		assertEquals(0xFF000000, ColorOps.gradient(0, 5, 0, 0, 10, 0, 0xFF000000, 0xFFFFFFFF, false));
		assertEquals(0xFFFFFFFF, ColorOps.gradient(12, 0, 0, 0, 10, 0, 0xFF000000, 0xFFFFFFFF, false));
		assertEquals(0xFF808080, ColorOps.gradient(5, 9, 0, 0, 10, 0, 0xFF000000, 0xFFFFFFFF, false));
		assertEquals(0.5, ColorOps.gradientPosition(5, 5, 0, 0, 10, 10), 1e-9);
	}

	@Test
	void ditheredGradientUsesOnlyTheTwoColorsInProportion() {
		int from = 0xFFFF0000;
		int to = 0xFF0000FF;
		int second = 0;
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				// The whole 4 x 4 block sits at the same position (the gradient runs along x far away).
				int color = ColorOps.gradient(x, y, -1000, 0, 1000, 0, from, to, true);
				assertTrue(color == from || color == to);
				if (color == to) {
					second++;
				}
			}
		}
		assertEquals(8, second, "half way along the gradient, half of a Bayer block uses the second color");
	}

	@Test
	void shadingShiftsHueWarmForHighlightsAndCoolForShadows() {
		int skin = 0xFFB98868;
		int light = ColorOps.shadeShifted(skin, 0.3, true);
		int dark = ColorOps.shadeShifted(skin, 0.3, false);
		assertTrue(ColorOps.hsv(light)[2] > ColorOps.hsv(skin)[2]);
		assertTrue(ColorOps.hsv(dark)[2] < ColorOps.hsv(skin)[2]);
		float hue = ColorOps.hsv(skin)[0];
		assertTrue(ColorOps.hsv(light)[0] > hue, "skin tones (orange) move toward yellow when lit");
		assertTrue(ColorOps.hsv(dark)[0] < hue || ColorOps.hsv(dark)[0] > 0.5F, "and toward blue in shadow");
		assertEquals(0, ColorOps.shadeShifted(0, 1, true));
		int gray = ColorOps.shadeShifted(0xFF808080, 0.5, false);
		assertEquals(0, ColorOps.hsv(gray)[1], 1e-6, "grays stay gray");
		assertEquals(0x80, ColorOps.shadeShifted(0x80B98868, 0.2, true) >>> 24);
	}

	@Test
	void paletteExtractionCountsAndMerges() {
		int[] image = new int[4096];
		for (int index = 0; index < 100; index++) {
			image[index] = 0xFFFF0000;
		}
		for (int index = 100; index < 150; index++) {
			image[index] = 0xFF00FF00;
		}
		image[150] = 0xFFFE0000;
		image[151] = 0x400000FF;
		List<Integer> palette = ColorOps.extractPalette(image, 16, 4);
		assertEquals(List.of(0xFFFF0000, 0xFF00FF00, 0xFF0000FF), palette);
		assertEquals(1, ColorOps.extractPalette(image, 1, 0).size());
	}

	// ---- body parts ---------------------------------------------------------------------------------------

	@Test
	void mirroredArmCopyMatchesVanillaLegacyLayout() {
		int[] canvas = new int[4096];
		// Right arm front (44..47, 20..31): a gradient so flips are visible.
		for (int x = 44; x < 48; x++) {
			canvas[at(x, 20)] = 0xFF000000 | x;
		}
		// Right arm right side (40..43) and left side (48..51).
		canvas[at(40, 20)] = 0xFFAA0000;
		canvas[at(48, 20)] = 0xFF00AA00;
		int[] copied = PartCopy.copyPart(canvas, false, SkinGeometry.Part.RIGHT_ARM, SkinGeometry.Part.LEFT_ARM, true,
			PartCopy.Shells.BASE);
		// Left arm front is 36..39 at row 52, flipped.
		assertEquals(0xFF000000 | 47, copied[at(36, 52)]);
		assertEquals(0xFF000000 | 44, copied[at(39, 52)]);
		// Right side of the right arm becomes the left side (u + d + w = 32 + 8 = 40) of the left arm, flipped.
		assertEquals(0xFFAA0000, copied[at(43, 52)]);
		assertEquals(0xFF00AA00, copied[at(35, 52)]);
		assertEquals(canvas[at(44, 20)], copied[at(44, 20)], "the source stays");
	}

	@Test
	void unmirroredCopyAndSlimArmsUseTheirOwnLayout() {
		int[] canvas = new int[4096];
		// Slim right arm front: 3 columns at 44..46.
		canvas[at(44, 20)] = 0xFF111111;
		canvas[at(46, 20)] = 0xFF333333;
		int[] copied = PartCopy.copyPart(canvas, true, SkinGeometry.Part.RIGHT_ARM, SkinGeometry.Part.LEFT_ARM, false,
			PartCopy.Shells.BOTH);
		assertEquals(0xFF111111, copied[at(36, 52)]);
		assertEquals(0xFF333333, copied[at(38, 52)]);
		assertEquals(0, copied[at(39, 52)], "slim left arm front is only three pixels wide");
		int[] mirrored = PartCopy.copyPart(canvas, true, SkinGeometry.Part.RIGHT_ARM, SkinGeometry.Part.LEFT_ARM, true,
			PartCopy.Shells.BASE);
		assertEquals(0xFF333333, mirrored[at(36, 52)]);
		assertEquals(0xFF111111, mirrored[at(38, 52)]);
		// A 4-wide leg onto a 3-wide slim arm is scaled, not cut off.
		canvas[at(4, 20)] = 0xFF444444;
		canvas[at(7, 20)] = 0xFF777777;
		int[] scaled = PartCopy.copyPart(canvas, true, SkinGeometry.Part.RIGHT_LEG, SkinGeometry.Part.RIGHT_ARM, false,
			PartCopy.Shells.BASE);
		assertEquals(0xFF444444, scaled[at(44, 20)]);
		assertEquals(0xFF777777, scaled[at(46, 20)]);
	}

	@Test
	void shellCopiesBaseToOverlayAndOverlayOntoBase() {
		int[] canvas = new int[4096];
		canvas[at(8, 8)] = 0xFFAA0000; // head front base
		canvas[at(9, 8)] = 0xFFBB0000;
		canvas[at(41, 8)] = 0xFF0000CC; // head front overlay over (9, 8)
		int[] toOverlay = PartCopy.copyShell(canvas, false, SkinGeometry.Part.HEAD, true);
		assertEquals(0xFFAA0000, toOverlay[at(40, 8)]);
		assertEquals(0xFFBB0000, toOverlay[at(41, 8)], "the overlay is replaced");
		int[] toBase = PartCopy.copyShell(canvas, false, null, false);
		assertEquals(0xFFAA0000, toBase[at(8, 8)], "empty overlay pixels keep the base");
		assertEquals(0xFF0000CC, toBase[at(9, 8)]);
		int[] bodyOnly = PartCopy.copyShell(canvas, false, SkinGeometry.Part.BODY, true);
		assertEquals(0, bodyOnly[at(40, 8)]);
	}

	@Test
	void legacySkinsGetMirroredLimbsLikeVanilla() {
		int[] legacy = new int[2048];
		for (int index = 0; index < 2048; index++) {
			legacy[index] = 0xFF000000 | (index * 37 & 0xFFFFFF);
		}
		for (int y = 0; y < 16; y++) {
			legacy[y * 64 + 40] = 0; // a transparent pixel keeps the hat
		}
		int[] modern = LegacySkin.toModern(legacy);
		int[] expected = new int[4096];
		System.arraycopy(legacy, 0, expected, 0, 2048);
		// The copies vanilla makes in SkinTextureDownloader (source x, y, target x, y, width, height), each flipped.
		int[][] copies = {
			{4, 16, 20, 48, 4, 4}, {8, 16, 24, 48, 4, 4}, {0, 20, 24, 52, 4, 12}, {4, 20, 20, 52, 4, 12},
			{8, 20, 16, 52, 4, 12}, {12, 20, 28, 52, 4, 12}, {44, 16, 36, 48, 4, 4}, {48, 16, 40, 48, 4, 4},
			{40, 20, 40, 52, 4, 12}, {44, 20, 36, 52, 4, 12}, {48, 20, 32, 52, 4, 12}, {52, 20, 44, 52, 4, 12}};
		for (int[] copy : copies) {
			for (int y = 0; y < copy[5]; y++) {
				for (int x = 0; x < copy[4]; x++) {
					expected[(copy[3] + y) * 64 + copy[2] + x] = legacy[(copy[1] + y) * 64 + copy[0] + copy[4] - x - 1];
				}
			}
		}
		assertArrayEquals(expected, modern);
	}

	@Test
	void legacyOpaqueHatIsCleared() {
		int[] legacy = new int[2048];
		java.util.Arrays.fill(legacy, 0xFF336699);
		int[] modern = LegacySkin.toModern(legacy);
		assertEquals(0, modern[at(40, 8)]);
		assertEquals(0xFF336699, modern[at(8, 8)]);
	}

	// ---- skin check ---------------------------------------------------------------------------------------

	@Test
	void skinCheckFindsHolesTranslucencyAndStrayPaint() {
		int[] skin = new int[4096];
		SkinGeometry geometry = SkinGeometry.of(false);
		for (SkinGeometry.Face face : geometry.faces()) {
			if (!face.box().outer()) {
				for (int row = 0; row < face.rows(); row++) {
					for (int column = 0; column < face.columns(); column++) {
						skin[(face.v() + row) * 64 + face.u() + column] = 0xFF405060;
					}
				}
			}
		}
		assertTrue(SkinCheck.check(skin, false).clean());
		skin[at(9, 9)] = 0;
		skin[at(10, 9)] = 0x80FF0000;
		skin[at(0, 0)] = 0xFF00FF00; // unused corner of the head area
		skin[at(41, 9)] = 0x80FFFFFF; // translucent overlay is fine
		SkinCheck.Report report = SkinCheck.check(skin, false);
		assertFalse(report.clean());
		assertEquals(List.of(at(9, 9)), report.transparentBase().stream().boxed().toList());
		assertEquals(List.of(at(10, 9)), report.translucentBase().stream().boxed().toList());
		assertEquals(List.of(at(0, 0)), report.outsideUv().stream().boxed().toList());
		assertEquals(3, report.all().cardinality());
		int[] fix = SkinCheck.baseFixLayer(skin, false, report);
		assertEquals(0xFF405060, fix[at(9, 9)], "a hole takes the nearest opaque color of its face");
		assertEquals(0xFFFF0000, fix[at(10, 9)]);
		assertEquals(0, fix[at(0, 0)]);
		assertEquals(0xFFFF0000, SkinDocument.over(skin[at(10, 9)], fix[at(10, 9)]));
	}

	@Test
	void slimArmsLeaveUnusedColumns() {
		int[] skin = new int[4096];
		skin[at(54, 20)] = 0xFFFFFFFF;
		assertTrue(SkinCheck.check(skin, true).outsideUv().get(at(54, 20)));
		assertFalse(SkinCheck.check(skin, false).outsideUv().get(at(54, 20)));
		assertNotEquals(SkinCheck.check(skin, true).outsideUv(), SkinCheck.check(skin, false).outsideUv());
	}
}
