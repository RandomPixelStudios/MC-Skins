package de.catsmc.mcskins.client.preview;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;

final class PreviewWorld {
	private static final String ORPHAN_PREFIX = "mcskins-preview-";
	private static final String MARKER_NAME = ".mcskins-preview-owner";
	private final LevelStorageSource source;
	private final UUID id = UUID.randomUUID();
	private final String folder = ORPHAN_PREFIX + id;
	private final String token = folder + "\n" + UUID.randomUUID() + "\n";
	private final Path base;
	private final Path world;
	private final Path receipt;
	private final Path marker;
	private Object directoryKey;
	private boolean receiptCreated;
	private boolean directoryCreated;
	private boolean deletionAuthorized;

	PreviewWorld(LevelStorageSource source) {
		this.source = source;
		base = source.getBaseDir().toAbsolutePath().normalize();
		world = source.getLevelPath(folder).toAbsolutePath().normalize();
		receipt = base.resolve("." + folder + ".owner");
		marker = world.resolve(MARKER_NAME);
	}

	String folder() {
		return folder;
	}

	Path path() {
		return world;
	}

	void reserve() throws IOException {
		validatePaths();
		if (source.levelExists(folder) || Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Preview directory already exists; refusing to reuse it.");
		}
		try {
			Files.writeString(receipt, token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			receiptCreated = true;
			Files.createDirectory(world);
			directoryCreated = true;
			directoryKey = Files.readAttributes(world, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
			if (directoryKey == null) {
				throw new IOException("Filesystem cannot verify preview directory identity.");
			}
			Files.writeString(marker, token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		} catch (IOException | RuntimeException failure) {
			rollbackReservation();
			throw failure;
		}
	}

	private void rollbackReservation() {
		boolean cleaned = true;
		try {
			if (directoryCreated) {
				Files.deleteIfExists(marker);
				cleaned = stillOwnedAndEmpty() && deleteEmptyDirectory();
			}
			if (cleaned && receiptCreated) {
				Files.deleteIfExists(receipt);
			}
		} catch (IOException | RuntimeException failure) {
			cleaned = false;
		}
		if (cleaned) {
			directoryCreated = false;
			directoryKey = null;
			receiptCreated = false;
		}
	}

	private boolean stillOwnedAndEmpty() throws IOException {
		if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(world)
			|| !world.getParent().equals(base) || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}
		var attributes = Files.readAttributes(world, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (directoryKey != null && !Objects.equals(directoryKey, attributes.fileKey())) {
			return false;
		}
		try (var children = Files.list(world)) {
			return children.findAny().isEmpty();
		}
	}

	private boolean deleteEmptyDirectory() throws IOException {
		Files.delete(world);
		return !Files.exists(world, LinkOption.NOFOLLOW_LINKS);
	}

	static void cleanOrphans(LevelStorageSource source) {
		Path base = source.getBaseDir().toAbsolutePath().normalize();
		if (!Files.isDirectory(base)) {
			return;
		}
		try (var entries = Files.list(base)) {
			entries.filter(path -> path.getFileName().toString().startsWith("." + ORPHAN_PREFIX))
				.forEach(path -> cleanOrphan(base, path));
		} catch (IOException | RuntimeException ignored) {
		}
	}

	private static void cleanOrphan(Path base, Path receipt) {
		try {
			String token = Files.readString(receipt);
			String folder = ownedFolder(token, receipt.getFileName().toString());
			if (folder == null) {
				return;
			}
			Path world = base.resolve(folder);
			boolean present = Files.exists(world, LinkOption.NOFOLLOW_LINKS);
			if (present) {
				if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)
					|| !world.getParent().equals(base)) {
					return;
				}
				Path marker = world.resolve(MARKER_NAME);
				if (Files.isSymbolicLink(marker)
					|| !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
					|| !Files.readString(marker).equals(token)) {
					return;
				}
				purge(world);
				if (Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
					return;
				}
			}
			Files.deleteIfExists(receipt);
		} catch (IOException | RuntimeException ignored) {
		}
	}

	private static String ownedFolder(String token, String name) {
		int firstBreak = token.indexOf('\n');
		int lastBreak = token.lastIndexOf('\n');
		if (firstBreak < 1 || lastBreak != token.length() - 1 || lastBreak == firstBreak) {
			return null;
		}
		String folder = token.substring(0, firstBreak);
		if (token.indexOf('\n', firstBreak + 1) != lastBreak || !folder.startsWith(ORPHAN_PREFIX)
			|| folder.indexOf('/') >= 0 || folder.indexOf('\\') >= 0 || folder.contains("..")
			|| !token.substring(firstBreak + 1, lastBreak).matches(
				"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			return null;
		}
		return name.equals("." + folder + ".owner") ? folder : null;
	}

	private static void purge(Path directory) throws IOException {
		Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
				if (attributes.isSymbolicLink()) {
					throw new IOException("Symlink in preview directory; not deleting.");
				}
				Files.delete(path);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
				if (failure != null) {
					throw failure;
				}
				Files.delete(path);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	void delete() throws IOException, ContentValidationException {
		if (!receiptCreated) {
			return;
		}
		validatePaths();
		validateMarker(receipt);
		if (directoryCreated && Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
			validateDirectory();
			if (!deletionAuthorized) {
				validateMarker(marker);
			}
			rejectLinksInWorld();
			if (!source.levelExists(folder)) {
				throw new IOException("Preview storage no longer recognizes the owned directory.");
			}
			try (var access = source.validateAndCreateAccess(folder)) {
				if (!access.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(world)) {
					throw new IOException("Preview storage path changed.");
				}
				validateDirectory();
				validateMarker(receipt);
				if (!deletionAuthorized) {
					validateMarker(marker);
				}
				rejectLinksInWorld();
				deletionAuthorized = true;
				access.deleteLevel();
			}
		}
		if (directoryCreated && !Files.notExists(world, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Preview directory removal could not be confirmed.");
		}
		validateMarker(receipt);
		Files.delete(receipt);
		receiptCreated = false;
	}

	private void validatePaths() throws IOException {
		if (!folder.equals("mcskins-preview-" + id) || !world.getParent().equals(base)
			|| !world.startsWith(base) || !source.getLevelPath(folder).toAbsolutePath().normalize().equals(world)
			|| !source.getBaseDir().toAbsolutePath().normalize().equals(base)) {
			throw new IOException("Unsafe preview storage path.");
		}
		for (Path current = base; current != null; current = current.getParent()) {
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Symlinked preview storage is not supported.");
			}
		}
		if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || !base.toRealPath().equals(base)) {
			throw new IOException("Preview saves directory is unavailable.");
		}
	}

	private void validateDirectory() throws IOException {
		var attributes = Files.readAttributes(world, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isDirectory() || attributes.isSymbolicLink() || directoryKey == null
			|| !Objects.equals(directoryKey, attributes.fileKey()) || !world.toRealPath().equals(world)) {
			throw new IOException("Owned preview directory identity changed; not deleting.");
		}
	}

	private void validateMarker(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
			|| Files.size(path) != token.length() || !Files.readString(path).equals(token)) {
			throw new IOException("Preview ownership marker missing or changed: " + path.getFileName());
		}
	}

	private void rejectLinksInWorld() throws IOException {
		Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
				check(path, attributes);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
				check(path, attributes);
				return FileVisitResult.CONTINUE;
			}

			private void check(Path path, BasicFileAttributes attributes) throws IOException {
				if (!path.startsWith(world) || attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
					throw new IOException("Symlink in preview directory; not deleting.");
				}
			}
		});
	}
}
