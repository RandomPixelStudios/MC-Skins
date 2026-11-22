package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.Text;
import de.catsmc.mcskins.skin.PngCodec;
import de.catsmc.mcskins.skin.SafeFiles;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads an image from the system clipboard. Minecraft runs with AWT in headless mode, so besides AWT this asks the
 * platform's own clipboard tools: {@code wl-paste} or {@code xclip} on Linux, PowerShell on Windows and
 * {@code osascript} on macOS. Clipboard text that names a PNG file (a path or file:// URL) also works. Call it off the
 * render thread.
 */
final class ClipboardImage {
	private static final long TIMEOUT_SECONDS = 5;

	private ClipboardImage() {
	}

	static BufferedImage read(String clipboardText) throws IOException {
		BufferedImage image = fromAwt();
		if (image == null) {
			byte[] png = fromPlatform();
			if (png != null && PngCodec.isPng(png)) {
				image = PngCodec.decode(png, 4096, 4_194_304);
			}
		}
		if (image == null) {
			image = fromText(clipboardText);
		}
		if (image == null) {
			throw new IOException(Text.tr("mcskins.clipboard.empty"));
		}
		return image;
	}

	private static BufferedImage fromAwt() {
		if (GraphicsEnvironment.isHeadless()) {
			return null;
		}
		try {
			var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
				Image image = (Image) clipboard.getData(DataFlavor.imageFlavor);
				int width = image.getWidth(null);
				int height = image.getHeight(null);
				if (width < 1 || height < 1 || width > 4096 || height > 4096) {
					return null;
				}
				var buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				var graphics = buffered.createGraphics();
				graphics.drawImage(image, 0, 0, null);
				graphics.dispose();
				return buffered;
			}
		} catch (Exception | LinkageError exception) {
			// fall through to the platform tools
		}
		return null;
	}

	private static byte[] fromPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("win")) {
				return viaTempFile(path -> List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
					"Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
						+ "$i = [System.Windows.Forms.Clipboard]::GetImage(); if ($i) { $i.Save('" + path.toString().replace("'", "''")
						+ "', [System.Drawing.Imaging.ImageFormat]::Png) }"));
			}
			if (os.contains("mac")) {
				return viaTempFile(path -> List.of("osascript",
					"-e", "set png to (the clipboard as «class PNGf»)",
					"-e", "set f to open for access (POSIX file \"" + path.toString().replace("\"", "") + "\") with write permission",
					"-e", "set eof f to 0",
					"-e", "write png to f",
					"-e", "close access f"));
			}
			byte[] wayland = run(List.of("wl-paste", "--no-newline", "--type", "image/png"));
			if (wayland != null && PngCodec.isPng(wayland)) {
				return wayland;
			}
			return run(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-o"));
		} catch (IOException exception) {
			return null;
		}
	}

	private interface Command {
		List<String> build(Path target);
	}

	private static byte[] viaTempFile(Command command) throws IOException {
		Path target = Files.createTempFile("mcskins-clipboard-", ".png");
		try {
			run(command.build(target));
			return Files.size(target) > 0 ? SafeFiles.read(target, PngCodec.MAX_BYTES) : null;
		} finally {
			Files.deleteIfExists(target);
		}
	}

	/** Runs a helper and returns its standard output (at most 16 MiB), or null if it is missing or fails. */
	private static byte[] run(List<String> command) {
		Process process;
		try {
			process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException exception) {
			return null;
		}
		try (InputStream output = process.getInputStream()) {
			var bytes = new ByteArrayOutputStream();
			byte[] buffer = new byte[64 * 1024];
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
			// A helper that hangs is killed after the timeout, which also ends the read below.
			Thread killer = Thread.ofPlatform().daemon().start(() -> {
				try {
					if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
						process.destroyForcibly();
					}
				} catch (InterruptedException ignored) {
					process.destroyForcibly();
				}
			});
			int read;
			while ((read = output.read(buffer)) >= 0) {
				if (bytes.size() + read > PngCodec.MAX_BYTES || System.nanoTime() > deadline) {
					process.destroyForcibly();
					return null;
				}
				bytes.write(buffer, 0, read);
			}
			killer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS) + 500);
			return process.exitValue() == 0 && bytes.size() > 0 ? bytes.toByteArray() : null;
		} catch (IOException | InterruptedException | IllegalThreadStateException exception) {
			process.destroyForcibly();
			return null;
		}
	}

	private static BufferedImage fromText(String text) throws IOException {
		if (text == null) {
			return null;
		}
		String value = text.strip();
		if (value.isEmpty() || value.length() > 4096 || value.contains("\n")) {
			return null;
		}
		Path path;
		try {
			path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
		} catch (RuntimeException exception) {
			return null;
		}
		if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") || !Files.isRegularFile(path)) {
			return null;
		}
		return PngCodec.decode(SafeFiles.read(path, PngCodec.MAX_BYTES), 4096, 4_194_304);
	}
}
