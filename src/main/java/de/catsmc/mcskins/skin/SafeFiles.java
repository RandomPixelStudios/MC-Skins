package de.catsmc.mcskins.skin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** File helpers that refuse symbolic links and replace files atomically. */
public final class SafeFiles {
	private SafeFiles() {
	}

	public static void ensureDirectory(Path directory) throws IOException {
		directory = directory.toAbsolutePath().normalize();
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

	public static void requireRegularFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Expected a regular local file: " + path.getFileName());
		}
	}

	/** Reads at most {@code limit} bytes from a regular, non-linked file. */
	public static byte[] read(Path path, int limit) throws IOException {
		requireRegularFile(path);
		if (Files.size(path) > limit) {
			throw new IOException("File exceeds " + limit + " bytes: " + path.getFileName());
		}
		try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
			byte[] bytes = input.readNBytes(limit + 1);
			if (bytes.length > limit) {
				throw new IOException("File exceeds " + limit + " bytes: " + path.getFileName());
			}
			return bytes;
		}
	}

	/** Writes to a temporary sibling, forces it to disk and atomically replaces the target. */
	public static void atomicWrite(Path target, byte[] bytes) throws IOException {
		Path directory = target.toAbsolutePath().normalize().getParent();
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
