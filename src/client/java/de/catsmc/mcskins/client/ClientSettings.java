package de.catsmc.mcskins.client;

import com.mojang.logging.LogUtils;
import de.catsmc.mcskins.skin.SafeFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * Small persisted preferences, written atomically off the render thread. Writes are debounced: changes within
 * {@link #SAVE_DELAY_SECONDS} share one disk write, and {@link #flush()} writes pending changes right away.
 */
public final class ClientSettings {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_RECENT = 16;
	private static final long SAVE_DELAY_SECONDS = 3;

	private final Path file;
	private final Executor io;
	private boolean menuSpin = true;
	private boolean importAccountSkins = true;
	private boolean mirror;
	private boolean grid = true;
	private int brushSize = 1;
	private boolean colorWheel;
	private boolean gradientDither;
	private int shadeStrength = 3;
	private boolean replaceWholeSkin;
	private int replaceTolerance;
	private final List<Integer> recentColors = new ArrayList<>();
	private final List<Integer> palette = new ArrayList<>();
	private final AtomicReference<byte[]> pending = new AtomicReference<>();
	private final AtomicBoolean scheduled = new AtomicBoolean();

	ClientSettings(Path file, Executor io) {
		this.file = file;
		this.io = io;
		load();
	}

	public boolean menuSpin() {
		return menuSpin;
	}

	public void menuSpin(boolean value) {
		menuSpin = value;
		save();
	}

	public boolean importAccountSkins() {
		return importAccountSkins;
	}

	public void importAccountSkins(boolean value) {
		importAccountSkins = value;
		save();
	}

	public boolean mirror() {
		return mirror;
	}

	public void mirror(boolean value) {
		mirror = value;
		save();
	}

	public boolean grid() {
		return grid;
	}

	public void grid(boolean value) {
		grid = value;
		save();
	}

	public int brushSize() {
		return brushSize;
	}

	public void brushSize(int value) {
		brushSize = Math.clamp(value, 1, 8);
		save();
	}

	public boolean colorWheel() {
		return colorWheel;
	}

	public void colorWheel(boolean value) {
		colorWheel = value;
		save();
	}

	public boolean gradientDither() {
		return gradientDither;
	}

	public void gradientDither(boolean value) {
		gradientDither = value;
		save();
	}

	/** Shading brush strength 1-10 (5 % steps). */
	public int shadeStrength() {
		return shadeStrength;
	}

	public void shadeStrength(int value) {
		shadeStrength = Math.clamp(value, 1, 10);
		save();
	}

	public boolean replaceWholeSkin() {
		return replaceWholeSkin;
	}

	public void replaceWholeSkin(boolean value) {
		replaceWholeSkin = value;
		save();
	}

	/** Replace-color tolerance 0-64 per channel. */
	public int replaceTolerance() {
		return replaceTolerance;
	}

	public void replaceTolerance(int value) {
		replaceTolerance = Math.clamp(value, 0, 64);
		save();
	}

	/** The custom palette shown instead of the default one; empty for the default. */
	public List<Integer> palette() {
		return List.copyOf(palette);
	}

	public void palette(List<Integer> colors) {
		palette.clear();
		for (int color : colors) {
			if (palette.size() < 64) {
				palette.add(color);
			}
		}
		save();
	}

	public List<Integer> recentColors() {
		return List.copyOf(recentColors);
	}

	/** Moves a color to the front of the recent list. */
	public void useColor(int color) {
		if (!recentColors.isEmpty() && recentColors.getFirst() == color) {
			return;
		}
		recentColors.remove(Integer.valueOf(color));
		recentColors.addFirst(color);
		while (recentColors.size() > MAX_RECENT) {
			recentColors.removeLast();
		}
		save();
	}

	private void load() {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		var properties = new Properties();
		try {
			properties.load(new ByteArrayInputStream(SafeFiles.read(file, 64 * 1024)));
		} catch (IOException exception) {
			LOGGER.warn("MC-Skins: could not read settings, using defaults: {}", exception.getMessage());
			return;
		}
		menuSpin = !"false".equals(properties.getProperty("menuSpin"));
		importAccountSkins = !"false".equals(properties.getProperty("importAccountSkins"));
		mirror = "true".equals(properties.getProperty("editor.mirror"));
		grid = !"false".equals(properties.getProperty("editor.grid"));
		try {
			brushSize = Math.clamp(Integer.parseInt(properties.getProperty("editor.brush", "1")), 1, 8);
		} catch (NumberFormatException exception) {
			brushSize = 1;
		}
		colorWheel = "true".equals(properties.getProperty("editor.wheel"));
		gradientDither = "true".equals(properties.getProperty("editor.dither"));
		replaceWholeSkin = "true".equals(properties.getProperty("editor.replaceSkin"));
		shadeStrength = number(properties, "editor.shade", 3, 1, 10);
		replaceTolerance = number(properties, "editor.tolerance", 0, 0, 64);
		for (String value : properties.getProperty("editor.palette", "").split(",")) {
			if (value.matches("[0-9A-Fa-f]{8}") && palette.size() < 64) {
				palette.add((int) Long.parseLong(value, 16));
			}
		}
		for (String value : properties.getProperty("editor.recent", "").split(",")) {
			if (value.matches("[0-9A-Fa-f]{8}") && recentColors.size() < MAX_RECENT) {
				recentColors.add((int) Long.parseLong(value, 16));
			}
		}
	}

	private static int number(Properties properties, String key, int fallback, int min, int max) {
		try {
			return Math.clamp(Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))), min, max);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private void save() {
		var properties = new Properties();
		properties.setProperty("menuSpin", Boolean.toString(menuSpin));
		properties.setProperty("importAccountSkins", Boolean.toString(importAccountSkins));
		properties.setProperty("editor.mirror", Boolean.toString(mirror));
		properties.setProperty("editor.grid", Boolean.toString(grid));
		properties.setProperty("editor.brush", Integer.toString(brushSize));
		properties.setProperty("editor.wheel", Boolean.toString(colorWheel));
		properties.setProperty("editor.dither", Boolean.toString(gradientDither));
		properties.setProperty("editor.replaceSkin", Boolean.toString(replaceWholeSkin));
		properties.setProperty("editor.shade", Integer.toString(shadeStrength));
		properties.setProperty("editor.tolerance", Integer.toString(replaceTolerance));
		properties.setProperty("editor.palette", String.join(",", palette.stream()
			.map(color -> String.format(Locale.ROOT, "%08X", color)).toList()));
		properties.setProperty("editor.recent", String.join(",", recentColors.stream()
			.map(color -> String.format(Locale.ROOT, "%08X", color)).toList()));
		var bytes = new ByteArrayOutputStream();
		try {
			properties.store(bytes, "MC-Skins settings");
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
		pending.set(bytes.toByteArray());
		if (scheduled.compareAndSet(false, true)) {
			CompletableFuture.delayedExecutor(SAVE_DELAY_SECONDS, TimeUnit.SECONDS, io).execute(() -> {
				scheduled.set(false);
				writePending();
			});
		}
	}

	/** Writes pending changes now (still on the I/O thread), for example when the editor closes. */
	public void flush() {
		if (pending.get() != null) {
			io.execute(this::writePending);
		}
	}

	/** Writes pending changes on the calling thread; used while the game shuts down. */
	public void writeNow() {
		writePending();
	}

	private synchronized void writePending() {
		byte[] content = pending.getAndSet(null);
		if (content == null) {
			return;
		}
		try {
			SafeFiles.atomicWrite(file, content);
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("MC-Skins: could not save settings: {}", exception.getMessage());
		}
	}
}
