package de.catsmc.mcskins.client.account;

import com.mojang.logging.LogUtils;
import de.catsmc.mcskins.client.Text;
import de.catsmc.mcskins.skin.PngCodec;
import de.catsmc.mcskins.skin.SafeFiles;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class CapeCache {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_BYTES = 1024 * 1024;
	private static final int MAX_FILES = 64;
	/** One client for all cape downloads instead of a new one (with its own threads) per cape. */
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER).build();

	private CapeCache() {
	}

	public static int[] load(URI texture) throws IOException {
		URI uri = validated(texture);
		Path file = cacheDir().resolve(name(uri) + ".png");
		byte[] bytes = readFile(file);
		if (bytes != null) {
			try {
				int[] pixels = decode(bytes);
				touch(file);
				return pixels;
			} catch (IOException exception) {
				deleteFile(file);
			}
		}
		bytes = download(uri);
		int[] pixels = decode(bytes);
		writeFile(file, bytes);
		return pixels;
	}

	private static byte[] readFile(Path file) {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		try {
			return SafeFiles.read(file, MAX_BYTES);
		} catch (IOException exception) {
			LOGGER.debug("MC-Skins: ignoring unreadable cached cape {}: {}", file.getFileName(), exception.getMessage());
			return null;
		}
	}

	/** Marks a cache hit as recently used, so pruning keeps capes that are still shown. */
	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException exception) {
			LOGGER.debug("MC-Skins: could not mark cached cape {} as used", file.getFileName(), exception);
		}
	}

	private static void deleteFile(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException exception) {
			LOGGER.debug("MC-Skins: could not delete cached cape {}", file.getFileName(), exception);
		}
	}

	private static void writeFile(Path file, byte[] bytes) {
		try {
			SafeFiles.atomicWrite(file, bytes);
			prune(file.getParent());
		} catch (IOException exception) {
			LOGGER.warn("MC-Skins: could not cache cape texture: {}", exception.getMessage());
		}
	}

	/** Keeps only the most recently used cached capes (cache hits refresh the file time). */
	private static void prune(Path directory) throws IOException {
		List<Path> files;
		try (var stream = Files.list(directory)) {
			files = stream.filter(path -> path.getFileName().toString().endsWith(".png")
				&& Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
		}
		if (files.size() <= MAX_FILES) {
			return;
		}
		var sorted = new ArrayList<>(files);
		sorted.sort(Comparator.comparing(path -> {
			try {
				return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
			} catch (IOException exception) {
				return 0L;
			}
		}));
		for (Path path : sorted.subList(0, sorted.size() - MAX_FILES)) {
			deleteFile(path);
		}
	}

	private static URI validated(URI texture) throws IOException {
		if (texture == null || !"https".equals(texture.getScheme()) || !"textures.minecraft.net".equals(texture.getHost())
			|| texture.getPort() != -1 || texture.getUserInfo() != null || texture.getQuery() != null
			|| texture.getFragment() != null || !texture.getRawPath().matches("/texture/[0-9a-fA-F]{32,64}")) {
			throw new IOException(Text.tr("mcskins.error.cape_host"));
		}
		return texture;
	}

	private static Path cacheDir() throws IOException {
		Path directory = FabricLoader.getInstance().getConfigDir().resolve("mcskins").resolve("capes");
		SafeFiles.ensureDirectory(directory);
		return directory;
	}

	private static String name(URI uri) throws IOException {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));
			var text = new StringBuilder(digest.length * 2);
			for (byte value : digest) {
				text.append(Character.forDigit(value >> 4 & 15, 16)).append(Character.forDigit(value & 15, 16));
			}
			return text.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IOException(Text.tr("mcskins.error.cape_hash"));
		}
	}

	private static byte[] download(URI uri) throws IOException {
		try {
			HttpResponse<InputStream> response = CLIENT.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException(Text.tr("mcskins.error.cape_http", response.statusCode()));
			}
			try (InputStream input = response.body()) {
				byte[] bytes = input.readNBytes(MAX_BYTES + 1);
				if (bytes.length > MAX_BYTES) {
					throw new IOException(Text.tr("mcskins.error.cape_size"));
				}
				return bytes;
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException(Text.tr("mcskins.error.cape_interrupted"));
		}
	}

	private static int[] decode(byte[] bytes) throws IOException {
		BufferedImage image = PngCodec.decode(bytes, 64, 64 * 32);
		if (image.getWidth() != 64 || image.getHeight() != 32) {
			throw new IOException(Text.tr("mcskins.error.cape_dimensions"));
		}
		return PngCodec.argb(image, 64, 32);
	}
}
