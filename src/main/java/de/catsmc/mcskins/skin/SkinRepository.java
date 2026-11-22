package de.catsmc.mcskins.skin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkinRepository {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-Skins");
	private static final int MAGIC = 0x4D43534B;
	/**
	 * Version 1 stored no layer opacity; version 2 adds one opacity byte per layer; version 3 adds per layer a blend
	 * mode byte, an alpha-lock flag and a group id, followed by the layer groups after the layers.
	 */
	static final int VERSION = 3;
	private static final int MAX_PROJECT_BYTES = 1024 * 1024;
	private static final int MAX_PROJECTS = 128;
	private static final long MAX_LIBRARY_BYTES = 64L * 1024 * 1024;
	private static final String EXTENSION = ".mcskin";
	private static final String DEFAULT_LAYER = "Base";
	private final Path root;
	private final AtomicLong saveSequence = new AtomicLong();
	private final AtomicLong modifications = new AtomicLong();
	/** Sequence of the newest save written per project, so a queued older save never replaces a newer one. */
	private final Map<UUID, Long> writtenSequence = new HashMap<>();
	private final SnapshotStore snapshots;

	/**
	 * Projects that loaded, the names of files that were moved aside because they could not be read, and the names of
	 * files that were left untouched because a newer MC-Skins version wrote them.
	 */
	public record Library(List<SkinDocument> documents, List<String> quarantined, List<String> newer,
		Map<UUID, Long> modified, Map<UUID, Long> created) {
		public Library {
			documents = List.copyOf(documents);
			quarantined = List.copyOf(quarantined);
			newer = List.copyOf(newer);
			modified = Map.copyOf(modified);
			created = Map.copyOf(created);
		}

		public Library(List<SkinDocument> documents, List<String> quarantined, List<String> newer) {
			this(documents, quarantined, newer, Map.of(), Map.of());
		}

		public Library(List<SkinDocument> documents, List<String> quarantined) {
			this(documents, quarantined, List.of());
		}
	}

	/**
	 * Encoded project bytes taken on the owning thread so the write can happen elsewhere. {@code sequence} orders saves
	 * of the same repository: a save prepared earlier is never written over one prepared later.
	 */
	public record PreparedSave(UUID id, long revision, byte[] bytes, long sequence) {
	}

	/** A project written by a newer MC-Skins version; it is skipped, never moved aside. */
	public static final class NewerFormatException extends IOException {
		private final int version;

		NewerFormatException(int version) {
			super("Project format " + version + " needs a newer MC-Skins version.");
			this.version = version;
		}

		public int version() {
			return version;
		}
	}

	public SkinRepository(Path root) {
		this.root = Objects.requireNonNull(root, "A local storage directory is required.").toAbsolutePath().normalize();
		snapshots = new SnapshotStore(this.root);
	}

	/** The version history of every project, in {@code <root>/history}. */
	public SnapshotStore snapshots() {
		return snapshots;
	}

	/** Takes a manual snapshot of a project's current content; null if it equals the newest snapshot. */
	public SnapshotStore.Snapshot snapshot(UUID id, byte[] projectBytes) throws IOException {
		return snapshots.add(id, projectBytes, true, System.currentTimeMillis(), true);
	}

	/**
	 * Makes a snapshot the current version of its project. The version it replaces is kept as an automatic snapshot,
	 * so a restore can itself be undone. Returns the restored document (already saved).
	 */
	public synchronized SkinDocument restore(SnapshotStore.Snapshot snapshot) throws IOException {
		Path current = directory("projects").resolve(snapshot.id() + EXTENSION);
		if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
			snapshots.add(snapshot.id(), SafeFiles.read(current, MAX_PROJECT_BYTES), false, System.currentTimeMillis(), true);
		}
		SkinDocument document = decode(snapshots.read(snapshot), snapshot.id());
		writtenSequence.remove(snapshot.id());
		save(document);
		return document;
	}

	public Path root() {
		return root;
	}

	/** Counts writes and deletes; a screen can compare it with the value from its last scan to skip needless rescans. */
	public long modifications() {
		return modifications.get();
	}

	public SkinDocument create(String name, boolean slim, String template) throws IOException {
		return create(name, slim, template, DEFAULT_LAYER);
	}

	/** Creates a blank project whose single layer gets {@code layerName} (for example a translated "Base"). */
	public SkinDocument create(String name, boolean slim, String template, String layerName) throws IOException {
		String validName = SkinLayer.validateName(name);
		if (!"custom".equals(template)) {
			throw new IllegalArgumentException("Vanilla templates load from the game client.");
		}
		String layer;
		try {
			layer = SkinLayer.validateName(layerName == null ? DEFAULT_LAYER : layerName);
		} catch (IllegalArgumentException exception) {
			layer = DEFAULT_LAYER;
		}
		return new SkinDocument(UUID.randomUUID(), validName, slim, List.of(new SkinLayer(layer, new int[4096])), 0);
	}

	public List<SkinDocument> list() throws IOException {
		return scan().documents();
	}

	/**
	 * Loads every project. Unreadable ones are moved to {@code <root>/broken} (normally {@code config/mcskins/broken})
	 * instead of failing the whole library; projects from a newer MC-Skins version are skipped and left in place.
	 */
	public synchronized Library scan() throws IOException {
		Path directory = directory("projects");
		List<Path> paths = projectFiles(directory);
		var documents = new ArrayList<SkinDocument>(paths.size());
		var quarantined = new ArrayList<String>();
		var newer = new ArrayList<String>();
		var modified = new HashMap<UUID, Long>();
		var created = new HashMap<UUID, Long>();
		long total = 0;
		for (Path path : paths) {
			long size;
			try {
				SafeFiles.requireRegularFile(path);
				size = Files.size(path);
				if (size > MAX_PROJECT_BYTES) {
					throw new IOException("Project exceeds the 1 MiB storage limit: " + path.getFileName());
				}
			} catch (IOException exception) {
				moveAside(path, exception, quarantined);
				continue;
			}
			if (total + size > MAX_LIBRARY_BYTES) {
				throw new IOException("The local project library exceeds 64 MiB.");
			}
			try {
				SkinDocument document = read(path, projectId(path));
				documents.add(document);
				total += size;
				try {
					var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					modified.put(document.id(), attributes.lastModifiedTime().toMillis());
					long born = attributes.creationTime().toMillis();
					created.put(document.id(), born > 0 ? born : attributes.lastModifiedTime().toMillis());
				} catch (IOException ignored) {
					// times are only used for sorting
				}
			} catch (NewerFormatException exception) {
				LOGGER.warn("MC-Skins: skipping project from a newer MC-Skins version: {} ({})", path.getFileName(), exception.getMessage());
				newer.add(path.getFileName().toString());
				total += size;
			} catch (IOException exception) {
				moveAside(path, exception, quarantined);
			}
		}
		documents.sort(Comparator.comparing(SkinDocument::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(document -> document.id().toString()));
		return new Library(documents, quarantined, newer, modified, created);
	}

	private void moveAside(Path path, IOException exception, List<String> quarantined) {
		LOGGER.warn("MC-Skins: moving unreadable project aside: " + path.getFileName(), exception);
		quarantine(path);
		quarantined.add(path.getFileName().toString());
	}

	private void quarantine(Path path) {
		try {
			Path broken = directory("broken");
			Path target = broken.resolve(path.getFileName() + "." + System.currentTimeMillis() + ".broken");
			Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("MC-Skins: could not move unreadable project aside: " + path.getFileName(), exception);
		}
	}

	synchronized SkinDocument load(UUID id) throws IOException {
		Objects.requireNonNull(id, "A project ID is required.");
		return read(directory("projects").resolve(id + EXTENSION), id);
	}

	public void save(SkinDocument document) throws IOException {
		PreparedSave prepared = prepare(document);
		write(prepared);
		document.endStroke();
		document.markSaved(prepared.revision(), prepared.sequence());
	}

	/** Encodes the document; call on the thread that owns it. Pair with {@link #write(PreparedSave)}. */
	public PreparedSave prepare(SkinDocument document) throws IOException {
		Objects.requireNonNull(document, "A document is required.");
		byte[] bytes = encode(document);
		return new PreparedSave(document.id(), document.revision(), bytes, saveSequence.incrementAndGet());
	}

	/**
	 * Writes prepared bytes; safe to call from a background thread. Returns false without touching the file when a
	 * save of the same project that was prepared later has already been written.
	 */
	public synchronized boolean write(PreparedSave prepared) throws IOException {
		Long newest = writtenSequence.get(prepared.id());
		if (newest != null && newest > prepared.sequence()) {
			return false;
		}
		Path directory = directory("projects");
		Path target = directory.resolve(prepared.id() + EXTENSION);
		List<Path> paths = projectFiles(directory);
		if (!paths.contains(target) && paths.size() >= MAX_PROJECTS) {
			throw new IOException("The local library can contain at most 128 projects.");
		}
		long total = prepared.bytes().length;
		for (Path path : paths) {
			if (!path.equals(target)) {
				total += Files.size(path);
			}
		}
		if (total > MAX_LIBRARY_BYTES) {
			throw new IOException("The local project library exceeds 64 MiB.");
		}
		SafeFiles.atomicWrite(target, prepared.bytes());
		writtenSequence.put(prepared.id(), prepared.sequence());
		modifications.incrementAndGet();
		try {
			snapshots.add(prepared.id(), prepared.bytes(), false, System.currentTimeMillis(), false);
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("MC-Skins: could not store an automatic snapshot: {}", exception.getMessage());
		}
		return true;
	}

	public synchronized void delete(SkinDocument document) throws IOException {
		Objects.requireNonNull(document, "A document is required.");
		Path directory = directory("projects");
		Path target = directory.resolve(document.id() + EXTENSION);
		if (!document.id().equals(projectId(target))) {
			throw new IOException("Refusing to delete an untracked project file.");
		}
		SafeFiles.requireRegularFile(target);
		Files.delete(target);
		// A save that was still queued for this project must not bring the file back.
		writtenSequence.put(document.id(), Long.MAX_VALUE);
		modifications.incrementAndGet();
		try {
			deleteExports(directory("exports"), document.id(), null);
		} catch (IOException exception) {
			LOGGER.debug("MC-Skins: could not delete an old export", exception);
		}
		try {
			snapshots.deleteAll(document.id());
		} catch (IOException exception) {
			LOGGER.debug("MC-Skins: could not delete the version history", exception);
		}
	}

	Path export(SkinDocument document) throws IOException {
		Objects.requireNonNull(document, "A document is required.");
		return export(document.id(), document.name(), document.flatten());
	}

	/** Writes already flattened pixels to the export folder; safe to call from a background thread. */
	public Path export(UUID id, int[] flattened) throws IOException {
		return export(id, null, flattened);
	}

	/**
	 * Writes already flattened pixels to the export folder as {@code <name>_<first 8 ID characters>.png}, replacing
	 * older exports of the same project; safe to call from a background thread.
	 */
	public synchronized Path export(UUID id, String name, int[] flattened) throws IOException {
		Objects.requireNonNull(id, "A project ID is required.");
		Path directory = directory("exports");
		Path target = directory.resolve(exportName(id, name));
		SafeFiles.atomicWrite(target, PngCodec.encode(flattened, 64, 64));
		deleteExports(directory, id, target);
		return target;
	}

	/** A file-system safe export filename; the ID suffix keeps skins with the same name apart. */
	static String exportName(UUID id, String name) {
		var cleaned = new StringBuilder();
		if (name != null) {
			name.codePoints().forEach(codePoint -> {
				boolean safe = Character.isLetterOrDigit(codePoint) || codePoint == ' ' || codePoint == '-' || codePoint == '_';
				cleaned.appendCodePoint(safe ? codePoint : '_');
			});
		}
		String base = cleaned.toString().strip();
		if (base.length() > 48) {
			base = base.substring(0, Character.isHighSurrogate(base.charAt(47)) ? 47 : 48).strip();
		}
		if (base.codePoints().noneMatch(Character::isLetterOrDigit)) {
			base = "skin";
		}
		return base + "_" + shortId(id) + ".png";
	}

	private static String shortId(UUID id) {
		return id.toString().substring(0, 8).toLowerCase(Locale.ROOT);
	}

	/** Removes exports of a project (the old {@code <uuid>.png} and {@code *_<id>.png}), except {@code keep}. */
	private static void deleteExports(Path directory, UUID id, Path keep) throws IOException {
		String legacy = id + ".png";
		String suffix = "_" + shortId(id) + ".png";
		var stale = new ArrayList<Path>();
		try (var entries = Files.newDirectoryStream(directory)) {
			int scanned = 0;
			for (Path path : entries) {
				if (++scanned > 4096) {
					break;
				}
				String filename = path.getFileName().toString();
				if ((filename.equals(legacy) || filename.endsWith(suffix)) && !path.equals(keep)
					&& Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					stale.add(path);
				}
			}
		} catch (DirectoryIteratorException exception) {
			throw exception.getCause();
		}
		for (Path path : stale) {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * Writes a generated file into {@code renders/} or {@code recordings/} as
	 * {@code <name>_<yyyyMMdd-HHmmss>[-n].<extension>} without replacing an existing file; safe from any thread.
	 */
	public synchronized Path writeOutput(String folder, String name, String extension, byte[] bytes) throws IOException {
		if (!folder.equals("renders") && !folder.equals("recordings")) {
			throw new IllegalArgumentException("Unknown output folder.");
		}
		if (!extension.matches("[a-z]{3,4}")) {
			throw new IllegalArgumentException("Invalid file extension.");
		}
		Path directory = directory(folder);
		String base = exportName(new UUID(0, 0), name);
		base = base.substring(0, base.length() - "_00000000.png".length());
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
		Path target = directory.resolve(base + "_" + stamp + "." + extension);
		for (int attempt = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); attempt++) {
			if (attempt > 99) {
				throw new IOException("Too many files with the same name.");
			}
			target = directory.resolve(base + "_" + stamp + "-" + attempt + "." + extension);
		}
		SafeFiles.atomicWrite(target, bytes);
		return target;
	}

	public Path outputDirectory(String folder) throws IOException {
		if (!folder.equals("renders") && !folder.equals("recordings")) {
			throw new IllegalArgumentException("Unknown output folder.");
		}
		return directory(folder);
	}

	public Path exportsDirectory() throws IOException {
		return directory("exports");
	}

	public static byte[] encode(SkinDocument document) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(VERSION);
			output.writeLong(document.id().getMostSignificantBits());
			output.writeLong(document.id().getLeastSignificantBits());
			output.writeUTF(document.name());
			output.writeBoolean(document.slim());
			output.writeInt(document.activeLayer());
			output.writeInt(document.layers().size());
			for (SkinLayer layer : document.layers()) {
				output.writeUTF(layer.name());
				output.writeBoolean(layer.visible());
				output.writeByte(layer.opacity());
				output.writeByte(layer.blend().id());
				output.writeBoolean(layer.alphaLock());
				output.writeInt(layer.group());
				output.writeInt(layer.offsetX());
				output.writeInt(layer.offsetY());
				for (int pixel : layer.pixels()) {
					output.writeInt(pixel);
				}
			}
			output.writeInt(document.groups().size());
			for (LayerGroup group : document.groups()) {
				output.writeInt(group.id());
				output.writeUTF(group.name());
				output.writeBoolean(group.visible());
				output.writeByte(group.opacity());
				output.writeBoolean(group.collapsed());
			}
			output.flush();
			CRC32 checksum = new CRC32();
			checksum.update(bytes.toByteArray());
			output.writeLong(checksum.getValue());
		}
		if (bytes.size() > MAX_PROJECT_BYTES) {
			throw new IOException("Project exceeds the 1 MiB storage limit.");
		}
		return bytes.toByteArray();
	}

	private SkinDocument read(Path path, UUID expectedId) throws IOException {
		byte[] bytes = SafeFiles.read(path, MAX_PROJECT_BYTES);
		SkinDocument document = decode(bytes, expectedId);
		document.markSaved();
		return document;
	}

	static SkinDocument decode(byte[] bytes, UUID expectedId) throws IOException {
		if (bytes.length < 8) {
			throw new IOException("Invalid project size: " + expectedId);
		}
		ByteBuffer header = ByteBuffer.wrap(bytes, 0, 8);
		if (header.getInt() == MAGIC) {
			int version = header.getInt();
			if (version > VERSION) {
				// Checked before the checksum: a future format may change everything after the header.
				throw new NewerFormatException(version);
			}
		}
		int contentLength = bytes.length - Long.BYTES;
		CRC32 checksum = new CRC32();
		checksum.update(bytes, 0, contentLength);
		if (checksum.getValue() != ByteBuffer.wrap(bytes, contentLength, Long.BYTES).getLong()) {
			throw new IOException("Project checksum mismatch: " + expectedId);
		}
		try (var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, contentLength))) {
			if (input.readInt() != MAGIC) {
				throw new IOException("Not an MC-Skins project.");
			}
			int version = input.readInt();
			if (version > VERSION) {
				throw new NewerFormatException(version);
			}
			if (version < 1) {
				throw new IOException("Invalid project format " + version + ".");
			}
			UUID id = new UUID(input.readLong(), input.readLong());
			if (!id.equals(expectedId)) {
				throw new IOException("Project ID does not match its filename.");
			}
			String name = readName(input);
			boolean slim = readBoolean(input);
			int active = input.readInt();
			int count = input.readInt();
			if (count < 1 || count > SkinDocument.MAX_LAYERS || active < 0 || active >= count) {
				throw new IOException("Invalid project layer count or selection.");
			}
			var layers = new ArrayList<SkinLayer>(count);
			for (int index = 0; index < count; index++) {
				String layerName = readName(input);
				boolean visible = readBoolean(input);
				int opacity = version >= 2 ? input.readUnsignedByte() : 255;
				BlendMode blend = BlendMode.NORMAL;
				boolean alphaLock = false;
				int group = 0;
				if (version >= 3) {
					blend = BlendMode.byId(input.readUnsignedByte());
					alphaLock = readBoolean(input);
					group = input.readInt();
					if (group < 0) {
						throw new IOException("Invalid layer group.");
					}
				}
				int x = input.readInt();
				int y = input.readInt();
				if (x < -64 || x > 64 || y < -64 || y > 64) {
					throw new IOException("Invalid layer offset.");
				}
				int[] pixels = new int[4096];
				for (int pixel = 0; pixel < pixels.length; pixel++) {
					pixels[pixel] = input.readInt();
				}
				layers.add(new SkinLayer(layerName, visible, opacity, pixels, x, y, blend, alphaLock, group));
			}
			var groups = new ArrayList<LayerGroup>();
			if (version >= 3) {
				int groupCount = input.readInt();
				if (groupCount < 0 || groupCount > LayerGroup.MAX_GROUPS) {
					throw new IOException("Invalid layer group count.");
				}
				for (int index = 0; index < groupCount; index++) {
					int groupId = input.readInt();
					String groupName = readName(input);
					boolean groupVisible = readBoolean(input);
					int groupOpacity = input.readUnsignedByte();
					boolean collapsed = readBoolean(input);
					groups.add(new LayerGroup(groupId, groupName, groupVisible, groupOpacity, collapsed));
				}
			}
			if (input.read() != -1) {
				throw new IOException("Unexpected data after project layers.");
			}
			return new SkinDocument(id, name, slim, layers, active, groups);
		} catch (NewerFormatException exception) {
			throw exception;
		} catch (IOException | IllegalArgumentException exception) {
			throw new IOException("Cannot read project " + expectedId + ": " + exception.getMessage(), exception);
		}
	}

	private static String readName(DataInputStream input) throws IOException {
		int length = input.readUnsignedShort();
		if (length < 1 || length > 192) {
			throw new IOException("Invalid project or layer name length.");
		}
		byte[] encoded = new byte[length + 2];
		encoded[0] = (byte) (length >>> 8);
		encoded[1] = (byte) length;
		input.readFully(encoded, 2, length);
		try (var nameInput = new DataInputStream(new ByteArrayInputStream(encoded))) {
			return SkinLayer.validateName(nameInput.readUTF());
		}
	}

	private static boolean readBoolean(DataInputStream input) throws IOException {
		int value = input.readUnsignedByte();
		if (value > 1) {
			throw new IOException("Invalid boolean in project.");
		}
		return value == 1;
	}

	private List<Path> projectFiles(Path directory) throws IOException {
		var paths = new ArrayList<Path>();
		int scanned = 0;
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path path : entries) {
				if (++scanned > 4096) {
					throw new IOException("Too many entries in the project directory.");
				}
				if (projectId(path) != null && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					paths.add(path);
					if (paths.size() > MAX_PROJECTS) {
						throw new IOException("The local library can contain at most 128 projects.");
					}
				}
			}
		} catch (DirectoryIteratorException exception) {
			throw exception.getCause();
		}
		paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return paths;
	}

	private static UUID projectId(Path path) {
		String filename = path.getFileName().toString();
		if (filename.length() != 36 + EXTENSION.length() || !filename.endsWith(EXTENSION)) {
			return null;
		}
		String value = filename.substring(0, 36);
		try {
			UUID id = UUID.fromString(value);
			return id.toString().equals(value) ? id : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private Path directory(String name) throws IOException {
		Path directory = root.resolve(name);
		SafeFiles.ensureDirectory(directory);
		return directory;
	}
}
