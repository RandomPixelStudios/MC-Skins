package de.catsmc.mcskins.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryLogicTest {
	@TempDir
	Path root;

	// ---- library index ------------------------------------------------------------------------------------

	@Test
	void indexStoresFavouritesTagsAndCreationTimes() throws Exception {
		var index = new LibraryIndex();
		UUID id = UUID.randomUUID();
		index.favourite(id, true);
		index.tags(id, List.of(" Knight ", "knight", "medieval", ""));
		assertTrue(index.created(id, 1234));
		assertFalse(index.created(id, 9999), "a known creation time is never replaced");
		var reloaded = new LibraryIndex();
		reloaded.read(index.encode());
		assertEquals(new LibraryIndex.Entry(true, List.of("Knight", "medieval"), 1234), reloaded.entry(id));
		assertEquals(List.of("Knight", "medieval"), reloaded.allTags());
		reloaded.remove(id);
		assertEquals(new LibraryIndex.Entry(false, List.of(), 0), reloaded.entry(id));
	}

	@Test
	void tagsAreBounded() {
		var many = new ArrayList<String>();
		for (int index = 0; index < 20; index++) {
			many.add("tag" + index);
		}
		assertEquals(LibraryIndex.MAX_TAGS, LibraryIndex.parseTags(String.join(",", many)).size());
		assertEquals(LibraryIndex.MAX_TAG_LENGTH, LibraryIndex.parseTags("x".repeat(80)).getFirst().length());
		assertEquals(List.of(), LibraryIndex.parseTags(" , ,"));
	}

	@Test
	void damagedIndexFileLoadsEmpty() throws Exception {
		Path file = root.resolve("library.properties");
		Files.writeString(file, "not-a-uuid.favourite=true\n" + UUID.randomUUID() + ".created=abc\n");
		assertTrue(LibraryIndex.load(file).allTags().isEmpty());
		assertTrue(LibraryIndex.load(root.resolve("missing.properties")).allTags().isEmpty());
	}

	@Test
	void queryFiltersAndSorts() {
		UUID a = new UUID(0, 1);
		UUID b = new UUID(0, 2);
		UUID c = new UUID(0, 3);
		var items = List.of(
			new LibraryIndex.Item(a, "Zombie", 300, 100, false, List.of("mob")),
			new LibraryIndex.Item(b, "alex edit", 100, 300, true, List.of()),
			new LibraryIndex.Item(c, "Knight", 200, 200, true, List.of("Medieval")));
		assertEquals(List.of(b, c, a), ids(LibraryIndex.query(items, "", false, null, LibraryIndex.Sort.NAME)));
		assertEquals(List.of(b, c, a), ids(LibraryIndex.query(items, null, false, null, LibraryIndex.Sort.EDITED)));
		assertEquals(List.of(a, c, b), ids(LibraryIndex.query(items, null, false, null, LibraryIndex.Sort.CREATED)));
		assertEquals(List.of(b, c), ids(LibraryIndex.query(items, "", true, null, LibraryIndex.Sort.NAME)));
		assertEquals(List.of(c), ids(LibraryIndex.query(items, "", false, "medieval", LibraryIndex.Sort.NAME)));
		assertEquals(List.of(c), ids(LibraryIndex.query(items, "MEDI", false, null, LibraryIndex.Sort.NAME)), "search finds tags");
		assertEquals(List.of(b), ids(LibraryIndex.query(items, "edit", false, null, LibraryIndex.Sort.NAME)));
	}

	private static List<UUID> ids(List<LibraryIndex.Item> items) {
		return items.stream().map(LibraryIndex.Item::id).toList();
	}

	// ---- snapshots ----------------------------------------------------------------------------------------

	@Test
	void automaticSnapshotsAreThrottledAndCapped() throws Exception {
		var store = new SnapshotStore(root);
		UUID id = UUID.randomUUID();
		assertNotNull(store.add(id, new byte[]{1}, false, 1_000, false));
		assertNull(store.add(id, new byte[]{2}, false, 2_000, false), "too soon after the last automatic snapshot");
		assertNull(store.add(id, new byte[]{1}, true, 3_000, false), "unchanged content is not stored twice");
		assertNotNull(store.add(id, new byte[]{3}, true, 3_000, false), "manual snapshots ignore the interval");
		long time = 10_000_000;
		for (int index = 0; index < SnapshotStore.MAX_AUTOMATIC + 5; index++) {
			time += SnapshotStore.AUTO_INTERVAL_MILLIS;
			store.add(id, new byte[]{(byte) (10 + index)}, false, time, false);
		}
		List<SnapshotStore.Snapshot> list = store.list(id);
		assertEquals(SnapshotStore.MAX_AUTOMATIC, list.stream().filter(snapshot -> !snapshot.manual()).count());
		assertEquals(1, list.stream().filter(SnapshotStore.Snapshot::manual).count());
		assertArrayEquals(new byte[]{(byte) (10 + SnapshotStore.MAX_AUTOMATIC + 4)}, store.read(list.getFirst()), "newest first");
		store.deleteAll(id);
		assertTrue(store.list(id).isEmpty());
	}

	@Test
	void savesCreateSnapshotsThatCanBeRestored() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("History", false, "custom");
		document.paint(0, 0, 0xFF111111);
		repository.save(document);
		List<SnapshotStore.Snapshot> first = repository.snapshots().list(document.id());
		assertEquals(1, first.size(), "the first save is snapshotted automatically");
		document.paint(0, 0, 0xFF222222);
		repository.save(document);
		assertEquals(1, repository.snapshots().list(document.id()).size(), "the next save within the interval is not");
		assertNotNull(repository.snapshot(document.id(), SkinRepository.encode(document)));
		SkinDocument restored = repository.restore(first.getFirst());
		assertEquals(0xFF111111, restored.sample(0, 0));
		assertEquals(0xFF111111, repository.load(document.id()).sample(0, 0));
		assertFalse(restored.dirty());
		repository.delete(restored);
		assertTrue(repository.snapshots().list(document.id()).isEmpty(), "deleting a skin removes its history");
	}

	@Test
	void scanReportsFileTimes() throws Exception {
		var repository = new SkinRepository(root);
		SkinDocument document = repository.create("Timed", false, "custom");
		repository.save(document);
		SkinRepository.Library library = repository.scan();
		assertTrue(library.modified().get(document.id()) > 0);
		assertTrue(library.created().get(document.id()) > 0);
	}

	// ---- palettes -----------------------------------------------------------------------------------------

	@Test
	void palettesSaveLoadAndDelete() throws Exception {
		var store = new PaletteStore(root);
		store.save(new PaletteStore.Palette("Forest / Night", List.of(0xFF102030, 0x80FFFFFF)));
		store.save(new PaletteStore.Palette("alpha", List.of(0xFF000000)));
		store.save(new PaletteStore.Palette("Forest / Night", List.of(0xFF405060)));
		List<PaletteStore.Palette> palettes = store.list();
		assertEquals(List.of("alpha", "Forest / Night"), palettes.stream().map(PaletteStore.Palette::name).toList());
		assertEquals(List.of(0xFF405060), palettes.get(1).colors(), "saving under the same name replaces it");
		store.delete("alpha");
		assertEquals(1, store.list().size());
		assertThrows(IllegalArgumentException.class, () -> new PaletteStore.Palette("empty", List.of()));
		assertFalse(PaletteStore.fileName("../evil").contains("/"));
	}

	// ---- GIF ----------------------------------------------------------------------------------------------

	@Test
	void gifHasEveryFrame() throws Exception {
		var frames = new ArrayList<int[]>();
		for (int frame = 0; frame < 3; frame++) {
			int[] pixels = new int[20 * 10];
			java.util.Arrays.fill(pixels, 0xFF000000 | frame * 0x404040);
			frames.add(pixels);
		}
		byte[] gif = GifEncoder.encode(frames, 20, 10, 100);
		assertEquals('G', gif[0]);
		try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
			var reader = ImageIO.getImageReadersByFormatName("gif").next();
			reader.setInput(stream);
			assertEquals(3, reader.getNumImages(true));
			assertEquals(20, reader.read(2).getWidth());
			assertEquals(0xFF808080, reader.read(2).getRGB(5, 5));
			reader.dispose();
		}
		int[][] small = GifEncoder.downscale(new int[400 * 200], 400, 200, 100);
		assertArrayEquals(new int[]{100, 50}, small[0]);
	}

	// ---- Mojang profiles ----------------------------------------------------------------------------------

	@Test
	void profileLookupParsesNameAndTextures() throws Exception {
		UUID id = MojangProfiles.parseNameLookup("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}"
			.getBytes(StandardCharsets.UTF_8));
		assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), id);
		assertEquals("069a79f444e94726a5befca90e38aaf5", MojangProfiles.compact(id));
		String textures = "{\"timestamp\":1,\"profileName\":\"Alex\",\"textures\":{\"SKIN\":{\"url\":"
			+ "\"http://textures.minecraft.net/texture/3b60a1f6d562f52aaebbf1434f1de147933a3affe0e764fa49ea057536623cd3\","
			+ "\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\"http://textures.minecraft.net/texture/"
			+ "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6\"}}}";
		String profile = "{\"id\":\"" + MojangProfiles.compact(id) + "\",\"name\":\"Alex\",\"properties\":[{\"name\":\"textures\","
			+ "\"value\":\"" + Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
		MojangProfiles.Textures parsed = MojangProfiles.parseProfile(profile.getBytes(StandardCharsets.UTF_8));
		assertEquals("Alex", parsed.name());
		assertTrue(parsed.slim());
		assertEquals("https", parsed.skin().getScheme(), "texture downloads are upgraded to https");
		assertNotNull(parsed.cape());
		String classic = textures.replace(",\"metadata\":{\"model\":\"slim\"}", "");
		assertFalse(MojangProfiles.parseTextures("x", classic.getBytes(StandardCharsets.UTF_8)).slim());
	}

	@Test
	void profileLookupRejectsBadInput() {
		assertTrue(MojangProfiles.validName("Steve_123"));
		assertFalse(MojangProfiles.validName("bad name"));
		assertFalse(MojangProfiles.validName("x".repeat(17)));
		assertThrows(MojangProfiles.LookupException.class, () -> MojangProfiles.parseNameLookup("{}".getBytes()));
		assertThrows(MojangProfiles.LookupException.class, () -> MojangProfiles.parseNameLookup("nope".getBytes()));
		assertThrows(MojangProfiles.LookupException.class, () -> MojangProfiles.textureUri("https://evil.example/texture/abc"));
		assertThrows(MojangProfiles.LookupException.class, () -> MojangProfiles.imageUrl("file:///etc/passwd"));
		assertThrows(MojangProfiles.LookupException.class, () -> MojangProfiles.imageUrl("https://user:pw@example.com/a.png"));
		assertEquals(URI.create("https://example.com/skin.png"), assertDoesNotThrowUrl("https://example.com/skin.png"));
	}

	private static URI assertDoesNotThrowUrl(String value) {
		try {
			return MojangProfiles.imageUrl(value);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	@Test
	void outputsGetTimestampedNamesWithoutOverwriting() throws Exception {
		var repository = new SkinRepository(root);
		Path first = repository.writeOutput("renders", "My Skin", "png", new byte[]{1});
		Path second = repository.writeOutput("renders", "My Skin", "png", new byte[]{2});
		assertTrue(first.getFileName().toString().startsWith("My Skin_"));
		assertTrue(second.getFileName().toString().endsWith("-2.png"));
		assertEquals(root.resolve("renders"), first.getParent());
		assertThrows(IllegalArgumentException.class, () -> repository.writeOutput("../x", "a", "png", new byte[0]));
	}

	@Test
	void legacyImagesAreConvertedInsteadOfStretched() {
		var image = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		image.setRGB(44, 20, 0xFFABCDEF); // right arm front, first column
		int[] pixels = SkinDocument.scaled(image, 64, 64);
		assertEquals(0xFFABCDEF, pixels[20 * 64 + 44]);
		assertEquals(0xFFABCDEF, pixels[52 * 64 + 39], "mirrored onto the left arm front's last column");
		var square = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		square.setRGB(2, 2, 0xFF00FF00);
		assertEquals(0xFF00FF00, SkinDocument.layerFromImage(square, "Pasted").pixelAt(1, 1));
	}

	// ---- render export ------------------------------------------------------------------------------------

	@Test
	void renderExportHasTransparentBackgroundAndRequestedSize() {
		int[] skin = new int[4096];
		java.util.Arrays.fill(skin, 0, 32 * 64, 0xFF8899AA);
		var renderer = new ModelRenderer();
		renderer.skin(skin, false);
		renderer.editing(true);
		renderer.grid(true);
		ModelRenderer copy = renderer.copyView();
		int[] image = copy.renderImage(256);
		assertEquals(256 * 256, image.length);
		assertEquals(0, image[0] >>> 24, "corners are transparent");
		long opaque = java.util.Arrays.stream(image).filter(pixel -> pixel >>> 24 == 255).count();
		assertTrue(opaque > 1000);
		// Transparent base pixels (the lower half of this skin) are drawn black like in game, never as a checkerboard.
		assertTrue(java.util.Arrays.stream(image).noneMatch(pixel -> (pixel & 0xFFFFFF) == 0x4A4A4A));
		assertEquals(2048 * 2048, copy.renderImage(2048).length);
		renderer.render(100, 100);
		assertEquals(100, renderer.width(), "the editor's renderer keeps its own size cap");
	}
}
