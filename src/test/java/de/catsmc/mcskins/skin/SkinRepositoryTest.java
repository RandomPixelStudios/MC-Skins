package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class SkinRepositoryTest {
	@TempDir
	Path root;

	@Test
	void roundTripKeepsLayersAndOpacity() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Round trip", true, "custom");
		document.paint(3, 4, 0xFF112233);
		document.addLayer("Overlay");
		document.paint(9, 9, 0x80FFFFFF);
		document.setLayerOpacity(1, 77);
		document.setLayerVisible(0, false);
		repository.save(document);
		assertFalse(document.dirty());

		SkinDocument loaded = repository.load(document.id());
		assertEquals("Round trip", loaded.name());
		assertTrue(loaded.slim());
		assertEquals(2, loaded.layers().size());
		assertEquals(77, loaded.layers().get(1).opacity());
		assertFalse(loaded.layers().get(0).visible());
		assertArrayEquals(document.flatten(), loaded.flatten());
	}

	@Test
	void corruptProjectIsQuarantinedInsteadOfHidingTheLibrary() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument good = repository.create("Good", false, "custom");
		repository.save(good);
		Path projects = root.resolve("projects");
		UUID brokenId = UUID.randomUUID();
		Files.write(projects.resolve(brokenId + ".mcskin"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});

		SkinRepository.Library library = repository.scan();
		assertEquals(List.of(good.id()), library.documents().stream().map(SkinDocument::id).toList());
		assertEquals(1, library.quarantined().size());
		assertFalse(Files.exists(projects.resolve(brokenId + ".mcskin")));
		try (var broken = Files.list(root.resolve("broken"))) {
			assertEquals(1, broken.count());
		}
	}

	@Test
	void readsVersionOneProjects() throws Exception {
		UUID id = UUID.randomUUID();
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(0x4D43534B);
			output.writeInt(1);
			output.writeLong(id.getMostSignificantBits());
			output.writeLong(id.getLeastSignificantBits());
			output.writeUTF("Legacy");
			output.writeBoolean(false);
			output.writeInt(0);
			output.writeInt(1);
			output.writeUTF("Base");
			output.writeBoolean(true);
			output.writeInt(0);
			output.writeInt(0);
			for (int pixel = 0; pixel < 4096; pixel++) {
				output.writeInt(pixel == 0 ? 0xFFAA0000 : 0);
			}
			output.flush();
			CRC32 checksum = new CRC32();
			checksum.update(bytes.toByteArray());
			output.writeLong(checksum.getValue());
		}
		Files.createDirectories(root.resolve("projects"));
		Files.write(root.resolve("projects").resolve(id + ".mcskin"), bytes.toByteArray());

		SkinDocument legacy = new SkinRepository(root).load(id);
		assertEquals("Legacy", legacy.name());
		assertEquals(255, legacy.layers().getFirst().opacity());
		assertEquals(0xFFAA0000, legacy.sample(0, 0));
	}

	@Test
	void preparedSaveWrittenLaterMarksOnlyThatRevision() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Async", false, "custom");
		document.paint(0, 0, 0xFF000000);
		SkinRepository.PreparedSave prepared = repository.prepare(document);
		document.paint(1, 0, 0xFF000000);
		repository.write(prepared);
		document.markSaved(prepared.revision());
		assertTrue(document.dirty());
		assertEquals(0, repository.load(document.id()).sample(1, 0));
	}

	@Test
	void exportWritesPng() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Export", false, "custom");
		document.paint(0, 0, 0xFF00FF00);
		Path png = repository.export(document);
		int[] pixels = PngCodec.argb(PngCodec.decode(Files.readAllBytes(png), 64, 4096), 64, 64);
		assertEquals(0xFF00FF00, pixels[0]);
	}

	private static byte[] futureProject(UUID id) throws Exception {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(0x4D43534B);
			output.writeInt(SkinRepository.VERSION + 1);
			output.writeLong(id.getMostSignificantBits());
			output.writeLong(id.getLeastSignificantBits());
			output.writeUTF("From the future");
			output.writeInt(12345);
		}
		return bytes.toByteArray();
	}

	@Test
	void newerFormatIsSkippedAndKeptInsteadOfQuarantined() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument good = repository.create("Good", false, "custom");
		repository.save(good);
		UUID futureId = UUID.randomUUID();
		Path future = root.resolve("projects").resolve(futureId + ".mcskin");
		Files.write(future, futureProject(futureId));

		SkinRepository.Library library = repository.scan();
		assertEquals(List.of(good.id()), library.documents().stream().map(SkinDocument::id).toList());
		assertEquals(List.of(futureId + ".mcskin"), library.newer());
		assertTrue(library.quarantined().isEmpty());
		assertTrue(Files.exists(future), "a project from a newer version must stay where it is");
		assertFalse(Files.exists(root.resolve("broken")) && Files.list(root.resolve("broken")).findAny().isPresent());
		assertThrows(SkinRepository.NewerFormatException.class, () -> repository.load(futureId));
	}

	@Test
	void oversizedUnreadableFileIsQuarantinedWithoutBlockingTheLibrary() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument good = repository.create("Good", false, "custom");
		repository.save(good);
		UUID hugeId = new UUID(0, 1); // sorts before the real project
		Files.write(root.resolve("projects").resolve(hugeId + ".mcskin"), new byte[2 * 1024 * 1024]);

		SkinRepository.Library library = repository.scan();
		assertEquals(List.of(good.id()), library.documents().stream().map(SkinDocument::id).toList());
		assertEquals(1, library.quarantined().size());
	}

	@Test
	void olderQueuedSaveNeverOverwritesANewerOne() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Race", false, "custom");
		document.paint(0, 0, 0xFF000001);
		SkinRepository.PreparedSave older = repository.prepare(document);
		document.paint(1, 0, 0xFF000002);
		SkinRepository.PreparedSave newer = repository.prepare(document);

		assertTrue(repository.write(newer));
		document.markSaved(newer.revision(), newer.sequence());
		assertFalse(repository.write(older), "the stale save is skipped");
		document.markSaved(older.revision(), older.sequence());

		assertFalse(document.dirty(), "a late older save must not make the document dirty again");
		assertEquals(0xFF000002, repository.load(document.id()).sample(1, 0));
	}

	@Test
	void deletedProjectIsNotRecreatedByAQueuedSave() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Gone", false, "custom");
		repository.save(document);
		SkinRepository.PreparedSave queued = repository.prepare(document);
		repository.delete(document);
		assertFalse(repository.write(queued));
		assertTrue(repository.scan().documents().isEmpty());
	}

	@Test
	void exportUsesSkinNameAndReplacesOlderExports() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("My/Skin", false, "custom");
		Path exports = repository.exportsDirectory();
		Files.write(exports.resolve(document.id() + ".png"), new byte[]{1});
		String shortId = document.id().toString().substring(0, 8);

		Path first = repository.export(document.id(), document.name(), document.flatten());
		assertEquals("My_Skin_" + shortId + ".png", first.getFileName().toString());
		assertFalse(Files.exists(exports.resolve(document.id() + ".png")), "the old UUID export is replaced");

		document.rename("Renamed");
		Path second = repository.export(document.id(), document.name(), document.flatten());
		assertEquals("Renamed_" + shortId + ".png", second.getFileName().toString());
		assertFalse(Files.exists(first));

		repository.save(document);
		repository.delete(document);
		assertFalse(Files.exists(second));
		assertEquals("skin_" + shortId + ".png", SkinRepository.exportName(document.id(), " / "));
	}

	@Test
	void modificationsCountWritesAndDeletes() throws Exception {
		var repository = new SkinRepository(root);
		long start = repository.modifications();
		SkinDocument document = repository.create("Count", false, "custom", "Basis");
		assertEquals("Basis", document.layers().getFirst().name());
		repository.save(document);
		repository.scan();
		assertEquals(start + 1, repository.modifications(), "scans do not count");
		repository.delete(document);
		assertEquals(start + 2, repository.modifications());
	}
}
