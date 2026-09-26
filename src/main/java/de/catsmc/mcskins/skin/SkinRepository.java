package de.catsmc.mcskins.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class SkinRepository {
	private static final int MAGIC = 0x4D43534B;
	private static final int VERSION = 1;
	private static final int MAX_PROJECT_BYTES = 1024 * 1024;
	private static final int MAX_PROJECTS = 128;
	private static final long MAX_LIBRARY_BYTES = 64L * 1024 * 1024;
	private static final String EXTENSION = ".mcskin";
	private final Path root;

	public SkinRepository(Path root) {
		this.root = Objects.requireNonNull(root, "A local storage directory is required.").toAbsolutePath().normalize();
	}

	public SkinDocument create(String name, boolean slim, String template) throws IOException {
		String validName = SkinLayer.validateName(name);
		if (!"custom".equals(template)) {
			throw new IllegalArgumentException("Vanilla templates load from the game client.");
		}
		return new SkinDocument(UUID.randomUUID(), validName, slim, List.of(new SkinLayer("Base", new int[4096])), 0);
	}

	public synchronized List<SkinDocument> list() throws IOException {
		Path directory = directory("projects");
		List<Path> paths = projectFiles(directory);
		var documents = new ArrayList<SkinDocument>(paths.size());
		long total = 0;
		for (Path path : paths) {
			requireRegularFile(path);
			total += Files.size(path);
			if (total > MAX_LIBRARY_BYTES) {
				throw new IOException("The local project library exceeds 64 MiB.");
			}
			documents.add(read(path, projectId(path)));
		}
		documents.sort(Comparator.comparing(SkinDocument::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(document -> document.id().toString()));
		return List.copyOf(documents);
	}

	public synchronized SkinDocument load(UUID id) throws IOException {
		Objects.requireNonNull(id, "A project ID is required.");
		return read(directory("projects").resolve(id + EXTENSION), id);
	}

	public synchronized void save(SkinDocument document) throws IOException {
		Objects.requireNonNull(document, "A document is required.");
		byte[] bytes = encode(document);
		Path directory = directory("projects");
		Path target = directory.resolve(document.id() + EXTENSION);
		List<Path> paths = projectFiles(directory);
		if (!paths.contains(target) && paths.size() >= MAX_PROJECTS) {
			throw new IOException("The local library can contain at most 128 projects.");
		}
		long total = bytes.length;
		for (Path path : paths) {
			requireRegularFile(path);
			if (!path.equals(target)) {
				total += Files.size(path);
			}
			if (total > MAX_LIBRARY_BYTES) {
				throw new IOException("The local project library exceeds 64 MiB.");
			}
		}
		atomicWrite(target, bytes);
		document.markSaved();
	}

	public synchronized void delete(SkinDocument document) throws IOException {
		Objects.requireNonNull(document, "A document is required.");
		Path directory = directory("projects");
		Path target = directory.resolve(document.id() + EXTENSION);
		if (!document.id().equals(projectId(target))) {
			throw new IOException("Refusing to delete an untracked project file.");
		}
		requireRegularFile(target);
		Files.delete(target);
		try {
			Files.deleteIfExists(directory("exports").resolve(document.id() + ".png"));
		} catch (IOException ignored) {
		}
	}

	public synchronized Path export(SkinDocument document) throws IOException {
		Objects.requireNonNull(document, "A document is required.");
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 64, 64, document.flatten(), 0, 64);
		var bytes = new ByteArrayOutputStream();
		try (var stream = new MemoryCacheImageOutputStream(bytes)) {
			if (!ImageIO.write(image, "png", stream)) {
				throw new IOException("No PNG encoder is available.");
			}
		}
		Path target = directory("exports").resolve(document.id() + ".png");
		atomicWrite(target, bytes.toByteArray());
		return target;
	}

	private byte[] encode(SkinDocument document) throws IOException {
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
				output.writeInt(layer.offsetX());
				output.writeInt(layer.offsetY());
				for (int pixel : layer.pixels()) {
					output.writeInt(pixel);
				}
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
		requireRegularFile(path);
		if (Files.size(path) > MAX_PROJECT_BYTES) {
			throw new IOException("Project exceeds the 1 MiB storage limit: " + expectedId);
		}
		byte[] bytes;
		try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
			bytes = input.readNBytes(MAX_PROJECT_BYTES + 1);
		}
		if (bytes.length < 8 || bytes.length > MAX_PROJECT_BYTES) {
			throw new IOException("Invalid project size: " + expectedId);
		}
		int contentLength = bytes.length - Long.BYTES;
		CRC32 checksum = new CRC32();
		checksum.update(bytes, 0, contentLength);
		if (checksum.getValue() != ByteBuffer.wrap(bytes, contentLength, Long.BYTES).getLong()) {
			throw new IOException("Project checksum mismatch: " + expectedId);
		}
		try (var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, contentLength))) {
			if (input.readInt() != MAGIC || input.readInt() != VERSION) {
				throw new IOException("Unsupported project format.");
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
				int x = input.readInt();
				int y = input.readInt();
				if (x < -64 || x > 64 || y < -64 || y > 64) {
					throw new IOException("Invalid layer offset.");
				}
				int[] pixels = new int[4096];
				for (int pixel = 0; pixel < pixels.length; pixel++) {
					pixels[pixel] = input.readInt();
				}
				layers.add(new SkinLayer(layerName, visible, pixels, x, y));
			}
			if (input.read() != -1) {
				throw new IOException("Unexpected data after project layers.");
			}
			SkinDocument document = new SkinDocument(id, name, slim, layers, active);
			document.markSaved();
			return document;
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
				if (projectId(path) != null) {
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
		ensureDirectory(directory);
		return directory;
	}

	private static void ensureDirectory(Path directory) throws IOException {
		Path current = directory.getRoot();
		if (current == null || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Storage requires an absolute local directory.");
		}
		for (Path component : directory) {
			current = current.resolve(component);
			try {
				Files.createDirectory(current);
			} catch (FileAlreadyExistsException exception) {
				if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Storage paths cannot contain symbolic links or nondirectories: " + current, exception);
				}
			}
		}
	}

	private static void requireRegularFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Expected a regular local project file: " + path.getFileName());
		}
	}

	private static void atomicWrite(Path target, byte[] bytes) throws IOException {
		Path directory = target.getParent();
		ensureDirectory(directory);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			requireRegularFile(target);
		}
		Path temporary = Files.createTempFile(directory, ".mcskins-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			ensureDirectory(directory);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				requireRegularFile(target);
			}
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException("Local storage must support atomic file replacement; the previous file was kept.", exception);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
