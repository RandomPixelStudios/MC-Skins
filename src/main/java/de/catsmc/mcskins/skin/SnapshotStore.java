package de.catsmc.mcskins.skin;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Version history of skins: copies of saved project files in {@code config/mcskins/history/<id>/}. Automatic
 * snapshots are taken when a skin is saved (at most one per {@link #AUTO_INTERVAL_MILLIS}); manual ones when the user
 * asks. Each kind is capped separately, dropping the oldest first.
 */
public final class SnapshotStore {
	public static final int MAX_AUTOMATIC = 20;
	public static final int MAX_MANUAL = 20;
	public static final long AUTO_INTERVAL_MILLIS = 5 * 60 * 1000L;
	private static final String SUFFIX = ".mcskin";
	private static final int MAX_BYTES = 1024 * 1024;
	private final Path root;

	public record Snapshot(UUID id, long time, boolean manual, Path path) {
	}

	public SnapshotStore(Path root) {
		this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
	}

	private Path directory(UUID id) {
		return root.resolve("history").resolve(id.toString());
	}

	/** Snapshots of a skin, newest first. */
	public synchronized List<Snapshot> list(UUID id) throws IOException {
		Path directory = directory(id);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			return List.of();
		}
		var result = new ArrayList<Snapshot>();
		try (var entries = Files.newDirectoryStream(directory)) {
			int scanned = 0;
			for (Path path : entries) {
				if (++scanned > 1024) {
					break;
				}
				Snapshot snapshot = parse(id, path);
				if (snapshot != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					result.add(snapshot);
				}
			}
		} catch (DirectoryIteratorException exception) {
			throw exception.getCause();
		}
		result.sort(Comparator.comparingLong(Snapshot::time).reversed().thenComparing(Snapshot::manual));
		return result;
	}

	private static Snapshot parse(UUID id, Path path) {
		String name = path.getFileName().toString();
		if (!name.endsWith(SUFFIX)) {
			return null;
		}
		String stem = name.substring(0, name.length() - SUFFIX.length());
		int dash = stem.indexOf('-');
		if (dash <= 0) {
			return null;
		}
		String kind = stem.substring(dash + 1);
		if (!kind.equals("auto") && !kind.equals("manual")) {
			return null;
		}
		try {
			return new Snapshot(id, Long.parseLong(stem.substring(0, dash)), kind.equals("manual"), path);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	/**
	 * Stores project bytes as a snapshot taken at {@code now}. Returns null without writing when the newest snapshot
	 * has the same content, or for automatic snapshots when the newest automatic one is younger than the interval
	 * (unless {@code force}).
	 */
	public synchronized Snapshot add(UUID id, byte[] bytes, boolean manual, long now, boolean force) throws IOException {
		List<Snapshot> existing = list(id);
		if (!existing.isEmpty() && Arrays.equals(read(existing.getFirst()), bytes)) {
			return null;
		}
		if (!manual && !force) {
			for (Snapshot snapshot : existing) {
				if (!snapshot.manual()) {
					if (now - snapshot.time() < AUTO_INTERVAL_MILLIS && now >= snapshot.time()) {
						return null;
					}
					break;
				}
			}
		}
		long time = now;
		for (Snapshot snapshot : existing) {
			// Two snapshots in the same millisecond would share a filename.
			if (snapshot.time() >= time && snapshot.manual() == manual) {
				time = snapshot.time() + 1;
			}
		}
		Path target = directory(id).resolve(time + "-" + (manual ? "manual" : "auto") + SUFFIX);
		SafeFiles.atomicWrite(target, bytes);
		prune(id);
		return new Snapshot(id, time, manual, target);
	}

	private void prune(UUID id) throws IOException {
		int automatic = 0;
		int manual = 0;
		for (Snapshot snapshot : list(id)) {
			boolean drop = snapshot.manual() ? ++manual > MAX_MANUAL : ++automatic > MAX_AUTOMATIC;
			if (drop) {
				Files.deleteIfExists(snapshot.path());
			}
		}
	}

	public synchronized byte[] read(Snapshot snapshot) throws IOException {
		if (!snapshot.path().toAbsolutePath().normalize().startsWith(directory(snapshot.id()))) {
			throw new IOException("Snapshot is outside the history folder.");
		}
		return SafeFiles.read(snapshot.path(), MAX_BYTES);
	}

	public synchronized void delete(Snapshot snapshot) throws IOException {
		if (!snapshot.path().toAbsolutePath().normalize().startsWith(directory(snapshot.id()))) {
			throw new IOException("Snapshot is outside the history folder.");
		}
		Files.deleteIfExists(snapshot.path());
	}

	/** Removes a skin's whole history, for example when the skin is deleted. */
	public synchronized void deleteAll(UUID id) throws IOException {
		for (Snapshot snapshot : list(id)) {
			Files.deleteIfExists(snapshot.path());
		}
		Path directory = directory(id);
		if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			try (var entries = Files.list(directory)) {
				if (entries.findAny().isEmpty()) {
					Files.deleteIfExists(directory);
				}
			}
		}
	}
}
