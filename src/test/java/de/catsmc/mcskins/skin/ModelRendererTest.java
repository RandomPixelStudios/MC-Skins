package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class ModelRendererTest {
	private static int[] debugSkin() {
		int[] pixels = new int[4096];
		for (SkinGeometry.Face face : SkinGeometry.of(false).faces()) {
			int color = 0xFF000000 | (face.side().ordinal() * 40 + 30) << 16 | (face.box().part().ordinal() * 35 + 20) << 8
				| (face.box().outer() ? 0 : 200);
			for (int row = 0; row < face.rows(); row++) {
				for (int column = 0; column < face.columns(); column++) {
					pixels[(face.v() + row) * 64 + face.u() + column] = face.box().outer() ? 0 : color;
				}
			}
		}
		return pixels;
	}

	@Test
	void frontViewPicksHeadFrontInCenterOfFace() {
		var renderer = new ModelRenderer();
		renderer.skin(debugSkin(), false);
		renderer.view(0);
		renderer.shadow(false);
		renderer.render(400, 400);
		int found = -1;
		for (int y = 0; y < 400 && found < 0; y++) {
			int pixel = renderer.target(200, y);
			if (pixel >= 0) {
				found = pixel;
			}
		}
		// Topmost model pixel in the center column of a front view belongs to the head front, row 0.
		SkinGeometry.Face face = SkinGeometry.of(false).faceAt(found & 63, found >>> 6);
		assertEquals(SkinGeometry.Part.HEAD, face.box().part());
		assertEquals(SkinGeometry.Side.FRONT, face.side());
		assertEquals(8, found >>> 6);
	}

	@Test
	void outerTargetPicksTransparentOverlayPixels() {
		var renderer = new ModelRenderer();
		renderer.skin(debugSkin(), false);
		renderer.view(0);
		renderer.target(ModelRenderer.Shell.OUTER);
		renderer.render(400, 400);
		int pixel = renderer.target(200, 100);
		assertTrue(pixel >= 0);
		assertTrue(SkinGeometry.of(false).faceAt(pixel & 63, pixel >>> 6).box().outer());
	}

	@Test
	void fitDoesNotChangeWhileRotating() {
		var renderer = new ModelRenderer();
		renderer.skin(debugSkin(), false);
		renderer.render(300, 300);
		double before = renderer.texelSize();
		renderer.orbit(1.3, 0.4);
		renderer.render(300, 300);
		assertEquals(before, renderer.texelSize(), 1e-9);
	}

	/** Writes preview PNGs for manual inspection when -Dmcskins.renderSkin points at a skin file. */
	@Test
	void renderSamples() throws Exception {
		String skinPath = System.getProperty("mcskins.renderSkin", "");
		if (skinPath.isEmpty()) {
			return;
		}
		var image = PngCodec.decode(Files.readAllBytes(Path.of(skinPath)), 64, 4096);
		int[] skin = PngCodec.argb(image, 64, 64);
		Path out = Path.of(System.getProperty("mcskins.renderOut"));
		Files.createDirectories(out);
		var renderer = new ModelRenderer();
		renderer.skin(skin, skinPath.contains("slim"));
		int[] cape = new int[2048];
		for (int index = 0; index < cape.length; index++) {
			cape[index] = 0xFFB02020 | ((index >> 6) * 4) << 8;
		}
		Object[][] shots = {
			{"default", -0.45, -0.18, ModelRenderer.Pose.DEFAULT, false, null},
			{"back-cape", Math.PI - 0.5, -0.2, ModelRenderer.Pose.DEFAULT, false, cape},
			{"tpose-grid", 0.6, -0.35, ModelRenderer.Pose.T_POSE, true, null},
			{"crouch", 1.2, -0.1, ModelRenderer.Pose.CROUCH, false, null},
		};
		for (Object[] shot : shots) {
			renderer.reset();
			renderer.orbit((double) shot[1] + 0.45, (double) shot[2] + 0.18);
			renderer.pose((ModelRenderer.Pose) shot[3]);
			renderer.grid((boolean) shot[4]);
			renderer.cape((int[]) shot[5]);
			renderer.editing(true);
			renderer.render(600, 600);
			int[] pixels = new int[600 * 600];
			var highlight = new BitSet();
			highlight.set(12 * 64 + 12);
			renderer.compose(pixels, highlight, 0x5551C8FF);
			for (int index = 0; index < pixels.length; index++) {
				pixels[index] = SkinDocument.over(pixels[index], ((index % 600 / 20 + index / 600 / 20) & 1) == 0 ? 0xFF262A30 : 0xFF2E333A);
			}
			Files.write(out.resolve(shot[0] + ".png"), PngCodec.encode(pixels, 600, 600));
		}
	}

	@Test
	void regionComposeMatchesFullComposeForHighlightChanges() {
		var renderer = new ModelRenderer();
		renderer.skin(debugSkin(), false);
		renderer.view(0);
		renderer.render(300, 300);
		int size = renderer.width() * renderer.height();
		var before = new BitSet();
		before.set(8 * 64 + 9);
		var after = new BitSet();
		after.set(8 * 64 + 10);

		int[] full = new int[size];
		renderer.compose(full, after, 0x60FFFFFF);
		int[] partial = new int[size];
		renderer.compose(partial, before, 0x60FFFFFF);
		var changed = (BitSet) before.clone();
		changed.xor(after);
		int[] region = renderer.bounds(changed);
		assertTrue(region != null && region[2] > region[0] && region[3] > region[1]);
		renderer.compose(partial, after, 0x60FFFFFF, region[0] - 1, region[1] - 1, region[2] + 1, region[3] + 1);
		assertArrayEquals(full, partial);
		var hidden = new BitSet();
		hidden.set(0);
		assertNull(renderer.bounds(hidden), "pixels outside the model are never on screen");
	}

	@Test
	void renderSizeIsCappedAtOneMillionPixels() {
		var renderer = new ModelRenderer();
		renderer.skin(debugSkin(), false);
		renderer.render(3840, 2160);
		assertTrue((long) renderer.width() * renderer.height() <= ModelRenderer.MAX_PIXELS);
	}
}
