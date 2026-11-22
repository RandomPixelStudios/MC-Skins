package de.catsmc.mcskins.client.account;

import com.mojang.logging.LogUtils;
import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.Text;
import de.catsmc.mcskins.skin.MojangProfiles;
import de.catsmc.mcskins.skin.PngCodec;
import de.catsmc.mcskins.skin.SkinDocument;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;

/**
 * Downloads skins that are not on the user's own account: any player's current skin by name (public Mojang API, no
 * login needed) or a PNG from a web address. Runs on the network thread; errors carry translated messages.
 */
public final class ProfileImporter {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_JSON = 64 * 1024;
	private static final int MAX_IMAGE = 4 * 1024 * 1024;

	public record Imported(String name, boolean slim, int[] pixels) {
		public Imported {
			pixels = pixels.clone();
		}

		@Override
		public int[] pixels() {
			return pixels.clone();
		}
	}

	private ProfileImporter() {
	}

	public static CompletableFuture<Imported> byName(String playerName) {
		String name = playerName == null ? "" : playerName.strip();
		if (!MojangProfiles.validName(name)) {
			return CompletableFuture.failedFuture(new IOException(Text.tr("mcskins.import.error.name_format")));
		}
		return CompletableFuture.supplyAsync(() -> {
			HttpClient client = client(HttpClient.Redirect.NEVER);
			try {
				byte[] lookup = get(client, URI.create(MojangProfiles.NAME_LOOKUP + name), MAX_JSON, true);
				if (lookup == null) {
					throw new IOException(Text.tr("mcskins.import.error.unknown_name", name));
				}
				UUID id;
				try {
					id = MojangProfiles.parseNameLookup(lookup);
				} catch (MojangProfiles.LookupException exception) {
					throw new IOException(Text.tr("mcskins.import.error.unknown_name", name));
				}
				byte[] profile = get(client, URI.create(MojangProfiles.SESSION_PROFILE + MojangProfiles.compact(id)), MAX_JSON, true);
				if (profile == null) {
					throw new IOException(Text.tr("mcskins.import.error.unknown_name", name));
				}
				MojangProfiles.Textures textures = MojangProfiles.parseProfile(profile);
				if (textures.skin() == null) {
					throw new IOException(Text.tr("mcskins.import.error.default_skin", name));
				}
				byte[] png = get(client, textures.skin(), MAX_IMAGE, false);
				AccountService.Image image = AccountService.decode(png, textures.slim());
				String skinName = textures.name() != null && MojangProfiles.validName(textures.name()) ? textures.name() : name;
				return new Imported(skinName, textures.slim(), image.pixels());
			} catch (MojangProfiles.LookupException exception) {
				throw new CompletionException(new IOException(Text.tr("mcskins.import.error.invalid")));
			} catch (IOException exception) {
				throw new CompletionException(exception);
			} finally {
				client.shutdownNow();
			}
		}, McSkinsClient.network());
	}

	public static CompletableFuture<Imported> byUrl(String address, String fallbackName) {
		URI uri;
		try {
			uri = MojangProfiles.imageUrl(address);
		} catch (IOException exception) {
			return CompletableFuture.failedFuture(new IOException(Text.tr("mcskins.import.error.url")));
		}
		return CompletableFuture.supplyAsync(() -> {
			HttpClient client = client(HttpClient.Redirect.NORMAL);
			try {
				byte[] bytes = get(client, uri, MAX_IMAGE, false);
				if (!PngCodec.isPng(bytes)) {
					throw new IOException(Text.tr("mcskins.import.error.not_png"));
				}
				BufferedImage image = PngCodec.decode(bytes, 4096, 4_194_304);
				int[] pixels;
				boolean slim;
				if (image.getWidth() == 64 && (image.getHeight() == 64 || image.getHeight() == 32)) {
					pixels = AccountService.decode(bytes, false).pixels();
				} else {
					pixels = SkinDocument.scaled(image, 64, 64);
				}
				slim = pixels[20 * 64 + 54] >>> 24 == 0 && pixels[31 * 64 + 55] >>> 24 == 0 && pixels[20 * 64 + 44] >>> 24 != 0;
				return new Imported(nameFrom(uri, fallbackName), slim, pixels);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			} finally {
				client.shutdownNow();
			}
		}, McSkinsClient.network());
	}

	private static String nameFrom(URI uri, String fallback) {
		String path = uri.getPath() == null ? "" : uri.getPath();
		String file = path.substring(path.lastIndexOf('/') + 1);
		if (file.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
			file = file.substring(0, file.length() - 4);
		}
		file = file.strip();
		return file.isEmpty() || file.length() > 48 || file.codePoints().anyMatch(Character::isISOControl) ? fallback : file;
	}

	private static HttpClient client(HttpClient.Redirect redirect) {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(redirect).build();
	}

	/** GETs a URL; returns null for "not found" answers when {@code missingIsNull}. */
	private static byte[] get(HttpClient client, URI uri, int limit, boolean missingIsNull) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
			.header("User-Agent", "MC-Skins").header("Accept", "application/json, image/png").GET().build();
		HttpResponse<InputStream> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (HttpTimeoutException exception) {
			throw new IOException(Text.tr("mcskins.account.error.network_timeout"));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException(Text.tr("mcskins.account.error.interrupted"));
		} catch (IOException exception) {
			LOGGER.warn("MC-Skins: download failed: {}", String.valueOf(exception));
			throw new IOException(Text.tr("mcskins.import.error.network"));
		}
		try (InputStream body = response.body()) {
			int status = response.statusCode();
			if (missingIsNull && (status == 204 || status == 404)) {
				return null;
			}
			if (status == 429) {
				throw new IOException(Text.tr("mcskins.import.error.rate_limited"));
			}
			if (status < 200 || status >= 300) {
				throw new IOException(Text.tr("mcskins.account.error.http", status));
			}
			byte[] bytes = body.readNBytes(limit + 1);
			if (bytes.length > limit) {
				throw new IOException(Text.tr("mcskins.import.error.too_large"));
			}
			if (missingIsNull && bytes.length == 0) {
				return null;
			}
			return bytes;
		}
	}
}
