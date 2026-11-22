package de.catsmc.mcskins.skin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Library data that is not part of a skin itself: favourites, tags and when a skin was created. Stored in
 * {@code config/mcskins/library.properties}; the methods are thread-safe and {@link #encode()} takes a snapshot that
 * can be written on another thread.
 */
public final class LibraryIndex {
	public static final int MAX_TAGS = 8;
	public static final int MAX_TAG_LENGTH = 24;
	private static final int MAX_BYTES = 1024 * 1024;

	public record Entry(boolean favourite, List<String> tags, long created) {
		public Entry {
			tags = List.copyOf(tags);
		}

		static final Entry EMPTY = new Entry(false, List.of(), 0);
	}

	public enum Sort { NAME, EDITED, CREATED }

	/** One skin as the library list sees it. */
	public record Item(UUID id, String name, long created, long edited, boolean favourite, List<String> tags) {
		public Item {
			tags = List.copyOf(tags);
		}
	}

	private final Map<UUID, Entry> entries = new HashMap<>();

	public static LibraryIndex load(Path file) {
		var index = new LibraryIndex();
		try {
			if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				index.read(SafeFiles.read(file, MAX_BYTES));
			}
		} catch (IOException | RuntimeException exception) {
			// A damaged index only loses favourites and tags; the skins themselves are untouched.
		}
		return index;
	}

	synchronized void read(byte[] bytes) throws IOException {
		var properties = new Properties();
		properties.load(new ByteArrayInputStream(bytes));
		for (String key : properties.stringPropertyNames()) {
			int dot = key.indexOf('.');
			if (dot != 36) {
				continue;
			}
			UUID id;
			try {
				id = UUID.fromString(key.substring(0, 36));
			} catch (IllegalArgumentException exception) {
				continue;
			}
			Entry entry = entries.getOrDefault(id, Entry.EMPTY);
			String value = properties.getProperty(key);
			switch (key.substring(37)) {
				case "favourite" -> entry = new Entry("true".equals(value), entry.tags(), entry.created());
				case "tags" -> entry = new Entry(entry.favourite(), parseTags(value), entry.created());
				case "created" -> {
					try {
						entry = new Entry(entry.favourite(), entry.tags(), Math.max(0, Long.parseLong(value)));
					} catch (NumberFormatException ignored) {
						// keep 0: unknown
					}
				}
				default -> {
				}
			}
			entries.put(id, entry);
		}
	}

	public synchronized byte[] encode() {
		var properties = new Properties();
		for (var entry : entries.entrySet()) {
			String id = entry.getKey().toString();
			Entry value = entry.getValue();
			if (value.favourite()) {
				properties.setProperty(id + ".favourite", "true");
			}
			if (!value.tags().isEmpty()) {
				properties.setProperty(id + ".tags", String.join(",", value.tags()));
			}
			if (value.created() > 0) {
				properties.setProperty(id + ".created", Long.toString(value.created()));
			}
		}
		var bytes = new ByteArrayOutputStream();
		try {
			properties.store(bytes, "MC-Skins library: favourites, tags and creation times");
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
		return bytes.toByteArray();
	}

	public synchronized Entry entry(UUID id) {
		return entries.getOrDefault(id, Entry.EMPTY);
	}

	public synchronized void favourite(UUID id, boolean favourite) {
		Entry entry = entry(id);
		entries.put(id, new Entry(favourite, entry.tags(), entry.created()));
	}

	public synchronized void tags(UUID id, List<String> tags) {
		Entry entry = entry(id);
		entries.put(id, new Entry(entry.favourite(), parseTags(String.join(",", tags)), entry.created()));
	}

	/** Records when a skin was created, unless that is already known. Returns true if something changed. */
	public synchronized boolean created(UUID id, long millis) {
		Entry entry = entry(id);
		if (entry.created() > 0 || millis <= 0) {
			return false;
		}
		entries.put(id, new Entry(entry.favourite(), entry.tags(), millis));
		return true;
	}

	public synchronized void remove(UUID id) {
		entries.remove(id);
	}

	/** Every tag in use, sorted case-insensitively. */
	public synchronized List<String> allTags() {
		var tags = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		for (Entry entry : entries.values()) {
			tags.addAll(entry.tags());
		}
		return List.copyOf(tags);
	}

	/**
	 * Splits comma separated tags, trimming them, dropping empty and duplicate ones (ignoring case) and keeping at most
	 * {@link #MAX_TAGS} tags of at most {@link #MAX_TAG_LENGTH} characters without control characters.
	 */
	public static List<String> parseTags(String value) {
		if (value == null) {
			return List.of();
		}
		var seen = new LinkedHashSet<String>();
		var result = new ArrayList<String>();
		for (String part : value.split(",")) {
			String tag = part.strip();
			if (tag.length() > MAX_TAG_LENGTH) {
				tag = SkinDocument.truncate(tag, MAX_TAG_LENGTH);
			}
			if (tag.isEmpty() || tag.codePoints().anyMatch(Character::isISOControl)) {
				continue;
			}
			if (seen.add(tag.toLowerCase(Locale.ROOT)) && result.size() < MAX_TAGS) {
				result.add(tag);
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Filters and sorts library items. {@code search} matches names and tags (ignoring case); {@code favouritesOnly}
	 * keeps favourites, {@code tag} (null for any) keeps skins with that tag. Name sorting is A-Z, the time sorts show
	 * the newest first.
	 */
	public static List<Item> query(List<Item> items, String search, boolean favouritesOnly, String tag, Sort sort) {
		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		var result = new ArrayList<Item>();
		for (Item item : items) {
			if (favouritesOnly && !item.favourite()) {
				continue;
			}
			if (tag != null && item.tags().stream().noneMatch(tag::equalsIgnoreCase)) {
				continue;
			}
			if (!needle.isEmpty() && !item.name().toLowerCase(Locale.ROOT).contains(needle)
				&& item.tags().stream().noneMatch(each -> each.toLowerCase(Locale.ROOT).contains(needle))) {
				continue;
			}
			result.add(item);
		}
		Comparator<Item> byName = Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(item -> item.id().toString());
		result.sort(switch (Objects.requireNonNull(sort)) {
			case NAME -> byName;
			case EDITED -> Comparator.comparingLong(Item::edited).reversed().thenComparing(byName);
			case CREATED -> Comparator.comparingLong(Item::created).reversed().thenComparing(byName);
		});
		return result;
	}
}
