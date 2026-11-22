package de.catsmc.mcskins.client.account;

import com.mojang.logging.LogUtils;
import de.catsmc.mcskins.client.Text;
import de.catsmc.mcskins.skin.LegacySkin;
import de.catsmc.mcskins.skin.PngCodec;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

public final class AccountService {
	private static final URI PROFILE = URI.create("https://api.minecraftservices.com/minecraft/profile");
	private static final int MAX_BYTES = 1024 * 1024;
	private static final Semaphore BUSY = new Semaphore(1);
	private static final Logger LOGGER = LogUtils.getLogger();
	private final SkinRepository repository;
	private final BooleanSupplier importAccountSkins;

	public AccountService(SkinRepository repository, BooleanSupplier importAccountSkins) {
		this.repository = repository;
		this.importAccountSkins = importAccountSkins;
	}

	public record Image(boolean slim, int[] pixels) {
		public Image {
			if (pixels.length != 4096) {
				throw new IllegalArgumentException("Expected a 64 x 64 skin.");
			}
			pixels = pixels.clone();
		}

		@Override
		public int[] pixels() {
			return pixels.clone();
		}

		public boolean matches(boolean model, int[] other) {
			if (slim != model || other == null || pixels.length != other.length) {
				return false;
			}
			for (int index = 0; index < pixels.length; index++) {
				if (pixels[index] != other[index]
					&& (pixels[index] >>> 24 != 0 || other[index] >>> 24 != 0)) {
					return false;
				}
			}
			return true;
		}
	}

	public record Result(AccountProfile profile, Image active, String message) {
	}

	public CompletableFuture<Result> refresh(String token, UUID player) {
		return start(token, player, null, null, false);
	}

	public CompletableFuture<Result> upload(String token, UUID player, Image image) {
		return start(token, player, image, null, true);
	}

	public CompletableFuture<Result> cape(String token, UUID player, AccountProfile known, String capeId) {
		if (known == null || !known.id().equals(player)
			|| (capeId != null && known.capes().stream().noneMatch(cape -> cape.id().equals(capeId)))) {
			return CompletableFuture.failedFuture(new IOException(Text.tr("mcskins.account.error.refresh_capes")));
		}
		return start(token, player, null, capeId, true);
	}

	private CompletableFuture<Result> start(String token, UUID player, Image upload, String capeId, boolean change) {
		if (token == null || token.isBlank() || token.equals("0") || token.length() > 16384
			|| token.chars().anyMatch(character -> character <= 32 || character >= 127)) {
			return CompletableFuture.failedFuture(new IOException(Text.tr("mcskins.account.error.offline")));
		}
		if (!BUSY.tryAcquire()) {
			return CompletableFuture.failedFuture(new IOException(Text.tr("mcskins.account.error.busy")));
		}
		var result = new CompletableFuture<Result>();
		Thread.ofPlatform().daemon().name("mcskins-account").start(() -> {
			HttpClient client = null;
			Result completed = null;
			IOException failure = null;
			try {
				client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
					.followRedirects(HttpClient.Redirect.NEVER).build();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
				HttpRequest.Builder request = authenticated(PROFILE, token);
				if (upload != null) {
					String boundary = "mcskins-" + UUID.randomUUID();
					request = authenticated(URI.create(PROFILE + "/skins"), token)
						.header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(upload, boundary)));
				} else if (change) {
					request = authenticated(URI.create(PROFILE + "/capes/active"), token);
					if (capeId == null) {
						request.DELETE();
					} else {
						request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
							"{\"capeId\":\"" + UUID.fromString(capeId) + "\"}", StandardCharsets.UTF_8));
					}
				}
				byte[] response = send(client, request.build(), deadline);
				if (change && response.length == 0) {
					response = send(client, authenticated(PROFILE, token).GET().build(), deadline);
				}
				AccountProfile profile = AccountProfile.parse(response, player);
				completed = importSkins(client, profile, upload, capeId, change, deadline);
			} catch (Exception exception) {
				LOGGER.warn("MC-Skins: account operation failed: {}", String.valueOf(exception));
				String message = exception instanceof IOException ? exception.getMessage() : Text.tr("mcskins.account.error.failed");
				failure = new IOException(message + (change ? " " + Text.tr("mcskins.account.error.unconfirmed") : ""));
			} finally {
				if (client != null) {
					client.shutdownNow();
				}
				BUSY.release();
			}
			if (failure != null) {
				result.completeExceptionally(failure);
			} else {
				result.complete(completed);
			}
		});
		return result;
	}

	private Result importSkins(HttpClient client, AccountProfile profile, Image upload, String capeId,
		boolean change, long deadline) throws IOException {
		boolean importLocally = importAccountSkins.getAsBoolean();
		Image active = null;
		int failed = 0;
		int imported = 0;
		String firstFailure = null;
		List<Image> local;
		try {
			local = new ArrayList<>(repository.list().stream().map(document -> new Image(document.slim(), document.flatten())).toList());
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("MC-Skins: local library unavailable during account import: {}", exception.getMessage());
			local = null;
		}
		String date = LocalDate.now().toString();
		List<AccountProfile.Skin> ordered = profile.skins().stream()
			.sorted(Comparator.comparing(AccountProfile.Skin::active).reversed()).toList();
		for (AccountProfile.Skin skin : ordered) {
			try {
				URI uri = AccountProfile.textureUri(skin.texture().toString());
				Image image = decode(send(client, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(), deadline), skin.slim());
				if (skin.active()) {
					active = image;
				}
				if (!importLocally) {
					continue;
				}
				if (local == null) {
					failed++;
					continue;
				}
				boolean exists = local.stream().anyMatch(known -> image.matches(known.slim(), known.pixels));
				if (!exists) {
					SkinDocument document = new SkinDocument(UUID.randomUUID(),
						Text.tr(skin.active() ? "mcskins.account.skin_name" : "mcskins.account.profile_skin_name", date),
						skin.slim(), List.of(new SkinLayer(Text.tr("mcskins.account.texture_layer"), image.pixels())), 0);
					repository.save(document);
					local.add(image);
					imported++;
				}
			} catch (IOException | RuntimeException exception) {
				failed++;
				if (firstFailure == null) {
					firstFailure = exception instanceof IOException ? exception.getMessage() : Text.tr("mcskins.account.error.import");
				}
			}
		}
		String message = importLocally ? Text.tr("mcskins.account.loaded_imported", imported) : Text.tr("mcskins.account.loaded");
		if (upload != null) {
			message = active != null && active.matches(upload.slim(), upload.pixels())
				? Text.tr("mcskins.account.skin_confirmed")
				: Text.tr("mcskins.account.skin_unconfirmed");
		} else if (change) {
			String actual = profile.capes().stream().filter(AccountProfile.Cape::active).map(AccountProfile.Cape::id).findFirst().orElse(null);
			message = Objects.equals(actual, capeId) ? Text.tr("mcskins.account.cape_confirmed")
				: Text.tr("mcskins.account.cape_unconfirmed");
		}
		if (failed > 0) {
			message = (firstFailure == null ? Text.tr("mcskins.account.error.library") : firstFailure) + " "
				+ Text.tr("mcskins.account.error.imports_failed", failed) + " " + message;
		}
		return new Result(profile, active, message);
	}

	private static HttpRequest.Builder authenticated(URI uri, String token) throws IOException {
		if (!"https".equals(uri.getScheme()) || !"api.minecraftservices.com".equals(uri.getHost())
			|| uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
			|| !List.of("/minecraft/profile", "/minecraft/profile/skins", "/minecraft/profile/capes/active").contains(uri.getRawPath())) {
			throw new IOException(Text.tr("mcskins.account.error.destination"));
		}
		return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
			.header("Accept", "application/json");
	}

	private static byte[] send(HttpClient client, HttpRequest request, long deadline) throws IOException {
		long remaining = Math.min(TimeUnit.SECONDS.toNanos(20), deadline - System.nanoTime());
		if (remaining <= 0) {
			throw new IOException(Text.tr("mcskins.account.error.timeout"));
		}
		var pending = client.sendAsync(request, info -> new LimitedBody());
		try {
			HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException(switch (status) {
					case 401 -> Text.tr("mcskins.account.error.http401");
					case 403 -> Text.tr("mcskins.account.error.http403");
					case 429 -> Text.tr("mcskins.account.error.http429");
					default -> Text.tr("mcskins.account.error.http", status);
				});
			}
			return response.body();
		} catch (TimeoutException exception) {
			throw new IOException(Text.tr("mcskins.account.error.network_timeout"));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException(Text.tr("mcskins.account.error.interrupted"));
		} catch (ExecutionException exception) {
			throw new IOException(Text.tr("mcskins.account.error.network"));
		} finally {
			if (!pending.isDone()) {
				pending.cancel(true);
			}
		}
	}

	private static byte[] multipart(Image image, String boundary) throws IOException {
		byte[] png = PngCodec.encode(image.pixels(), 64, 64);
		var body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
			+ (image.slim() ? "slim" : "classic") + "\r\n--" + boundary
			+ "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(png);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		return body.toByteArray();
	}

	public static Image decode(byte[] bytes, boolean slim) throws IOException {
		if (!PngCodec.isPng(bytes)) {
			throw new IOException(Text.tr("mcskins.account.error.not_png"));
		}
		BufferedImage png = PngCodec.decode(bytes, 64, 64 * 64);
		int height = png.getHeight();
		if (png.getWidth() != 64 || (height != 64 && height != 32) || (height == 32 && slim)) {
			throw new IOException(Text.tr("mcskins.account.error.dimensions"));
		}
		int[] argb = PngCodec.argb(png, 64, height);
		// Old 64 x 32 skins get mirrored left limbs, like the game builds them.
		int[] pixels = height == 32 ? LegacySkin.toModern(argb) : argb;
		return new Image(slim, pixels);
	}

	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private Flow.Subscription subscription;

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > MAX_BYTES - bytes.size()) {
					subscription.cancel();
					body.completeExceptionally(new IOException("Response exceeds 1 MiB."));
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable error) {
			body.completeExceptionally(new IOException("Network response failed."));
		}

		@Override
		public void onComplete() {
			body.complete(bytes.toByteArray());
		}
	}
}
