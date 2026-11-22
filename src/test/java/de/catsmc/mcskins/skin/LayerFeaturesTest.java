package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayerFeaturesTest {
	@TempDir
	Path root;

	private static SkinDocument blank() {
		return new SkinDocument(UUID.randomUUID(), "Test", false, List.of(new SkinLayer("Base", new int[4096])), 0);
	}

	@Test
	void blendModesFollowTheirFormulas() {
		int below = 0xFF804020;
		int above = 0xFF408080;
		assertEquals(above, BlendMode.NORMAL.composite(above, below));
		assertEquals(0xFF202010, BlendMode.MULTIPLY.composite(above, below));
		assertEquals(0xFFA0A090, BlendMode.SCREEN.composite(above, below));
		assertEquals(0xFFC0C0A0, BlendMode.ADD.composite(above, below));
		// Overlay: dark backdrop channels multiply (doubled), light ones screen.
		assertEquals(0xFF804020, BlendMode.OVERLAY.composite(0xFF808080, below));
		assertEquals(0xFFFF8000, BlendMode.ADD.composite(0xFFFF8000, 0));
	}

	@Test
	void blendModesApplyOnlyOverOpaqueBackdropAndRespectAlpha() {
		assertEquals(0xFF408080, BlendMode.MULTIPLY.composite(0xFF408080, 0), "nothing below: the layer shows as is");
		int half = BlendMode.MULTIPLY.composite(0x80000000, 0xFFFFFFFF);
		assertEquals(0xFF7F7F7F, half);
	}

	@Test
	void documentCompositesWithLayerBlendMode() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFF808080);
		document.addLayer("Shade");
		document.paint(0, 0, 0xFF808080);
		document.setLayerBlend(1, BlendMode.MULTIPLY);
		assertEquals(0xFF404040, document.sample(0, 0));
		document.mergeDown();
		assertEquals(1, document.layers().size());
		assertEquals(0xFF404040, document.sample(0, 0));
	}

	@Test
	void alphaLockKeepsTransparencyWhilePainting() {
		SkinDocument document = blank();
		document.paint(0, 0, 0x80FF0000);
		document.setLayerAlphaLock(0, true);
		document.paint(0, 0, 0xFF00FF00);
		document.paint(1, 0, 0xFF00FF00);
		document.paint(0, 0, 0);
		assertEquals(0x8000FF00, document.layers().getFirst().pixelAt(0, 0));
		assertEquals(0, document.layers().getFirst().pixelAt(1, 0));
	}

	@Test
	void groupsHideAndFadeTheirLayers() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFFFF0000);
		document.addLayer("Top");
		document.paint(0, 0, 0xFF0000FF);
		int group = document.createGroup("Clothes");
		assertEquals(group, document.layers().get(0).group(), "the ungrouped layer below joins the new group");
		assertEquals(group, document.layers().get(1).group());
		document.setGroupVisible(group, false);
		assertEquals(0, document.sample(0, 0));
		assertFalse(document.effectivelyVisible(1));
		document.setGroupVisible(group, true);
		document.setLayerGroup(0, 0);
		document.setGroupOpacity(group, 0);
		assertEquals(0xFFFF0000, document.sample(0, 0));
		document.dissolveGroup(group);
		assertTrue(document.groups().isEmpty());
		assertNull(document.group(group));
	}

	@Test
	void collapsingAGroupIsNotAnUndoStep() {
		SkinDocument document = blank();
		int group = document.createGroup("G");
		document.markSaved();
		document.setGroupCollapsed(group, true);
		assertTrue(document.group(group).collapsed());
		assertFalse(document.dirty());
		document.undo();
		assertTrue(document.groups().isEmpty());
	}

	@Test
	void historyListsLabelledStepsAndJumps() {
		SkinDocument document = blank();
		document.label("Brush");
		document.beginStroke();
		document.paint(0, 0, 0xFF000001);
		document.paint(1, 0, 0xFF000001);
		document.endStroke();
		document.label("Fill");
		document.paint(2, 0, 0xFF000002);
		document.paint(3, 0, 0xFF000003);
		assertEquals(List.of("Opened", "Brush", "Fill", "Edit"), document.history());
		assertEquals(3, document.historyPosition());
		document.jumpTo(1);
		assertEquals(1, document.historyPosition());
		assertEquals(0xFF000001, document.sample(1, 0));
		assertEquals(0, document.sample(2, 0));
		assertEquals(List.of("Opened", "Brush", "Fill", "Edit"), document.history(), "redo steps stay listed");
		document.jumpTo(3);
		assertEquals(0xFF000003, document.sample(3, 0));
		document.jumpTo(0);
		assertEquals(0, document.sample(0, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> document.jumpTo(4));
	}

	@Test
	void applyCanvasAndClearEverywhereAreSingleSteps() {
		SkinDocument document = blank();
		int[] canvas = new int[4096];
		canvas[5] = 0xFF112233;
		document.applyCanvas(canvas);
		assertEquals(0xFF112233, document.sample(5, 0));
		document.addLayer("Top");
		document.paint(5, 0, 0xFF445566);
		var pixels = new java.util.BitSet();
		pixels.set(5);
		assertTrue(document.clearEverywhere(pixels));
		assertEquals(0, document.sample(5, 0));
		document.undo();
		assertEquals(0xFF445566, document.sample(5, 0));
	}

	@Test
	void flattenWithPreviewsAnActiveCanvasWithoutChangingTheDocument() {
		SkinDocument document = blank();
		int[] canvas = new int[4096];
		canvas[0] = 0xFFFFFFFF;
		assertEquals(0xFFFFFFFF, document.flattenWith(canvas)[0]);
		assertEquals(0, document.sample(0, 0));
		assertFalse(document.canUndo());
	}

	@Test
	void versionThreeRoundTripKeepsBlendLockAndGroups() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Groups", true, "custom");
		document.addLayer("Shadow");
		document.setLayerBlend(1, BlendMode.SCREEN);
		document.setLayerAlphaLock(1, true);
		int group = document.createGroup("Outfit");
		document.setGroupOpacity(group, 100);
		document.setGroupCollapsed(group, true);
		repository.save(document);
		SkinDocument loaded = repository.load(document.id());
		assertEquals(BlendMode.SCREEN, loaded.layers().get(1).blend());
		assertTrue(loaded.layers().get(1).alphaLock());
		assertEquals(group, loaded.layers().get(0).group());
		assertEquals(new LayerGroup(group, "Outfit", true, 100, true), loaded.group(group));
	}

	@Test
	void readsVersionTwoProjectsWithDefaults() throws Exception {
		UUID id = UUID.randomUUID();
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(0x4D43534B);
			output.writeInt(2);
			output.writeLong(id.getMostSignificantBits());
			output.writeLong(id.getLeastSignificantBits());
			output.writeUTF("Two");
			output.writeBoolean(true);
			output.writeInt(0);
			output.writeInt(1);
			output.writeUTF("Base");
			output.writeBoolean(true);
			output.writeByte(200);
			output.writeInt(0);
			output.writeInt(0);
			for (int pixel = 0; pixel < 4096; pixel++) {
				output.writeInt(pixel == 1 ? 0xFF00AA00 : 0);
			}
			output.flush();
			CRC32 checksum = new CRC32();
			checksum.update(bytes.toByteArray());
			output.writeLong(checksum.getValue());
		}
		Files.createDirectories(root.resolve("projects"));
		Files.write(root.resolve("projects").resolve(id + ".mcskin"), bytes.toByteArray());
		SkinDocument loaded = new SkinRepository(root).load(id);
		SkinLayer layer = loaded.layers().getFirst();
		assertEquals(200, layer.opacity());
		assertEquals(BlendMode.NORMAL, layer.blend());
		assertFalse(layer.alphaLock());
		assertEquals(0, layer.group());
		assertTrue(loaded.groups().isEmpty());
		assertTrue(loaded.slim());
		// Saving migrates the project to the current format.
		new SkinRepository(root).save(loaded);
		byte[] saved = Files.readAllBytes(root.resolve("projects").resolve(id + ".mcskin"));
		assertEquals(SkinRepository.VERSION, java.nio.ByteBuffer.wrap(saved, 4, 4).getInt());
	}
}
