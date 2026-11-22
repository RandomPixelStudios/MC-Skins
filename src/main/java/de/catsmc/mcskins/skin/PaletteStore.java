package de.catsmc.mcskins.skin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Named custom palettes, one small file each in {@code config/mcskins/palettes/}. */
public final class PaletteStore {
	public static final int MAX_COLORS = 64;
	public static final int MAX_PALETTES = 64;
	private static final String SUFFIX = ".palette";
	private final Path directory;

	public record Palette(String name, List<Integer> colors) {
		public Palette {
			name = SkinLayer.validateName(name);
			if (colors.isEmpty() || colors.size() > MAX_COLORS) {
				throw new IllegalArgumentException("A palette holds between 1 and 64 colors.");
			}
			colors = List.copyOf(colors);
		}
	}

	public PaletteStore(Path root) {
		directory = Objects.requireNonNull(root).toAbsolutePath().normalize().resolve("palettes");
	}

	/** Every readable palette, sorted by name. Unreadable files are skipped. */
	public synchronized List<Palette> list() throws IOException {
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			return List.of();
		}
		var result = new ArrayList<Palette>();
		try (var entries = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
			for (Path path : entries) {
				if (result.size() >= MAX_PALETTES) {
					break;
				}
				try {
					result.add(decode(SafeFiles.read(path, 64 * 1024)));
				} catch (IOException | RuntimeException exception) {
					// skip damaged palette files
				}
			}
		} catch (DirectoryIteratorException exception) {
			throw exception.getCause();
		}
		result.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
		return result;
	}

	/** Saves (or replaces) a palette under its name. */
	public synchronized Path save(Palette palette) throws IOException {
		Path target = directory.resolve(fileName(palette.name()));
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && list().size() >= MAX_PALETTES) {
			throw new IOException("At most 64 palettes can be saved.");
		}
		SafeFiles.atomicWrite(target, encode(palette));
		return target;
	}

	public synchronized void delete(String name) throws IOException {
		Path target = directory.resolve(fileName(SkinLayer.validateName(name)));
		if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
			Files.delete(target);
		}
	}

	static String fileName(String name) {
		var cleaned = new StringBuilder();
		name.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
			boolean safe = codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '-' || codePoint == '_');
			cleaned.append(safe ? Character.toString(codePoint) : String.format(Locale.ROOT, "%%%04x", codePoint));
		});
		String base = cleaned.toString();
		if (base.length() > 120) {
			base = base.substring(0, 120) + Integer.toHexString(name.hashCode());
		}
		return base + SUFFIX;
	}

	static byte[] encode(Palette palette) {
		var properties = new Properties();
		properties.setProperty("name", palette.name());
		properties.setProperty("colors", String.join(",", palette.colors().stream()
			.map(color -> String.format(Locale.ROOT, "%08X", color)).toList()));
		var bytes = new ByteArrayOutputStream();
		try {
			properties.store(bytes, "MC-Skins palette");
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
		return bytes.toByteArray();
	}

	static Palette decode(byte[] bytes) throws IOException {
		var properties = new Properties();
		properties.load(new ByteArrayInputStream(bytes));
		var colors = new ArrayList<Integer>();
		for (String value : properties.getProperty("colors", "").split(",")) {
			if (value.matches("[0-9A-Fa-f]{8}") && colors.size() < MAX_COLORS) {
				colors.add((int) Long.parseLong(value, 16));
			}
		}
		String name = properties.getProperty("name");
		if (name == null) {
			throw new IOException("Palette has no name.");
		}
		return new Palette(name, colors);
	}
}
