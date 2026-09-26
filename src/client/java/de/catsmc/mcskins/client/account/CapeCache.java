package de.catsmc.mcskins.client.account;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;

public final class CapeCache {
	private static final int MAX_BYTES = 1024 * 1024;

	private CapeCache() {
	}

	public static int[] load(URI texture) throws IOException {
		URI uri = validated(texture);
		Path file = cacheDir().resolve(name(uri) + ".png");
		byte[] bytes = readFile(file);
		if (bytes != null) {
			try {
				return decode(bytes);
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
			return Files.readAllBytes(file);
		} catch (IOException ignored) {
			return null;
		}
	}

	private static void deleteFile(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
		}
	}

	private static void writeFile(Path file, byte[] bytes) {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.write(temp, bytes);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException ignored) {
			deleteFile(temp);
		}
	}

	private static URI validated(URI texture) throws IOException {
		if (texture == null || !"https".equals(texture.getScheme()) || !"textures.minecraft.net".equals(texture.getHost())
			|| texture.getPort() != -1 || texture.getUserInfo() != null || texture.getQuery() != null
			|| texture.getFragment() != null || !texture.getRawPath().matches("/texture/[0-9a-fA-F]{32,64}")) {
			throw new IOException("Refusing unexpected cape texture host.");
		}
		return texture;
	}

	private static Path cacheDir() throws IOException {
		Path directory = FabricLoader.getInstance().getConfigDir().resolve("mcskins").resolve("capes");
		Files.createDirectories(directory);
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
			throw new IOException("No SHA-256 available for cape cache names.");
		}
	}

	private static byte[] download(URI uri) throws IOException {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER).build()) {
			HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Cape download failed (HTTP " + response.statusCode() + ").");
			}
			try (InputStream input = response.body()) {
				byte[] bytes = input.readNBytes(MAX_BYTES + 1);
				if (bytes.length > MAX_BYTES) {
					throw new IOException("Cape texture exceeds 1 MiB.");
				}
				return bytes;
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Cape download interrupted.");
		}
	}

	private static int[] decode(byte[] bytes) throws IOException {
		byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
		if (bytes.length < signature.length + 1) {
			throw new IOException("Cape texture is not a PNG.");
		}
		for (int index = 0; index < signature.length; index++) {
			if (bytes[index] != signature[index]) {
				throw new IOException("Cape texture is not a PNG.");
			}
		}
		try (var input = new ByteArrayInputStream(bytes)) {
			BufferedImage image = ImageIO.read(input);
			if (image == null || image.getWidth() != 64 || image.getHeight() != 32) {
				throw new IOException("Cape texture must be 64 x 32.");
			}
			int[] pixels = image.getRGB(0, 0, 64, 32, null, 0, 64);
			for (int index = 0; index < pixels.length; index++) {
				if (pixels[index] >>> 24 == 0) {
					pixels[index] = 0;
				}
			}
			return pixels;
		}
	}
}
