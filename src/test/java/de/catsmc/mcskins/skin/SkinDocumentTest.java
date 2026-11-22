package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkinDocumentTest {
	private static SkinDocument blank() {
		return new SkinDocument(UUID.randomUUID(), "Test", false, List.of(new SkinLayer("Base", new int[4096])), 0);
	}

	@Test
	void strokeIsOneUndoStep() {
		SkinDocument document = blank();
		document.beginStroke();
		document.paint(1, 1, 0xFFFF0000);
		document.paint(2, 1, 0xFFFF0000);
		document.paint(3, 1, 0xFFFF0000);
		document.endStroke();
		assertEquals(0xFFFF0000, document.sample(2, 1));
		document.undo();
		assertEquals(0, document.sample(1, 1));
		assertEquals(0, document.sample(3, 1));
		assertFalse(document.canUndo());
		document.redo();
		assertEquals(0xFFFF0000, document.sample(3, 1));
	}

	@Test
	void undoDoesNotLeakIntoRedoAfterNewEdit() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFF00FF00);
		document.undo();
		document.paint(1, 0, 0xFF0000FF);
		assertFalse(document.canRedo());
	}

	@Test
	void flattenIsCachedButReflectsChanges() {
		SkinDocument document = blank();
		int[] first = document.flatten();
		first[0] = 0xFFFFFFFF;
		assertEquals(0, document.flatten()[0], "callers must not be able to mutate the cache");
		document.paint(0, 0, 0xFF123456);
		assertEquals(0xFF123456, document.flatten()[0]);
	}

	@Test
	void opacityAffectsCompositeAndMergeDown() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFF000000);
		document.addLayer("Top");
		document.paint(0, 0, 0xFFFFFFFF);
		document.setLayerOpacity(1, 128);
		int mixed = document.sample(0, 0);
		assertEquals(0xFF, mixed >>> 24);
		assertEquals(128, mixed & 255, 1);
		document.mergeDown();
		assertEquals(1, document.layers().size());
		assertEquals(mixed, document.sample(0, 0));
		assertEquals("Base", document.layers().getFirst().name());
	}

	@Test
	void duplicateRenameAndClear() {
		SkinDocument document = blank();
		document.paint(5, 5, 0xFFABCDEF);
		document.duplicateLayer();
		assertEquals(2, document.layers().size());
		assertEquals("Base copy", document.layers().get(1).name());
		document.renameLayer(1, "Shadow");
		assertEquals("Shadow", document.layers().get(1).name());
		document.clearLayer();
		assertEquals(0, document.activeCanvas()[5 * 64 + 5]);
		assertEquals(0xFFABCDEF, document.sample(5, 5));
	}

	@Test
	void savedRevisionTracksAsyncSaves() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFF000001);
		long revision = document.revision();
		document.paint(1, 0, 0xFF000001);
		document.markSaved(revision);
		assertTrue(document.dirty(), "edits made after the snapshot keep the document dirty");
	}

	@Test
	void rejectsBadInput() {
		assertThrows(IllegalArgumentException.class, () -> new SkinLayer(" ", new int[4096]));
		assertThrows(IllegalArgumentException.class, () -> new SkinLayer("x", new int[10]));
		SkinDocument document = blank();
		assertThrows(IndexOutOfBoundsException.class, () -> document.paint(64, 0, 0));
		int[] before = document.flatten();
		document.removeLayer();
		assertArrayEquals(before, document.flatten());
	}

	@Test
	void duplicateDoesNotSplitSurrogatePairs() {
		SkinDocument document = blank();
		// 64 UTF-16 units; cutting to 59 for " copy" would split the emoji's surrogate pair.
		String name = "a".repeat(58) + "😀" + "aaaa";
		document.renameLayer(0, name);
		document.duplicateLayer();
		String copy = document.layers().get(1).name();
		assertEquals("a".repeat(58) + " copy", copy);
		document.selectLayer(0);
		document.duplicateLayer(" Kopie");
		assertTrue(document.layers().get(1).name().endsWith(" Kopie"));
		assertEquals("ab", SkinDocument.truncate("ab😀", 3));
	}

	@Test
	void mergeDownNeedsBothLayersVisible() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFF000000);
		document.addLayer("Top");
		document.paint(1, 0, 0xFFFFFFFF);
		document.setLayerVisible(0, false);
		assertFalse(document.canMergeDown());
		assertThrows(IllegalStateException.class, document::mergeDown);
		assertEquals(2, document.layers().size());
		document.setLayerVisible(0, true);
		document.setLayerVisible(1, false);
		assertFalse(document.canMergeDown());
		document.setLayerVisible(1, true);
		assertTrue(document.canMergeDown());
		document.mergeDown();
		assertTrue(document.layers().getFirst().visible());
		assertEquals(0xFFFFFFFF, document.sample(1, 0));
	}

	@Test
	void olderSaveSequenceDoesNotOverrideNewerSave() {
		SkinDocument document = blank();
		document.paint(0, 0, 0xFF000001);
		long older = document.revision();
		document.paint(1, 0, 0xFF000001);
		long newer = document.revision();
		document.markSaved(newer, 2);
		document.markSaved(older, 1);
		assertFalse(document.dirty());
	}
}
