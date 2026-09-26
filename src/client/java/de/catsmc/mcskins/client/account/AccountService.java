package de.catsmc.mcskins.client.account;

import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class AccountService {
	private static final URI PROFILE = URI.create("https://api.minecraftservices.com/minecraft/profile");
	private static final int MAX_BYTES = 1024 * 1024;
	private static final Semaphore BUSY = new Semaphore(1);
	private final SkinRepository repository;

	public AccountService(SkinRepository repository) {
		this.repository = repository;
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
			return CompletableFuture.failedFuture(new IOException("Refresh your owned capes before changing them."));
		}
		return start(token, player, null, capeId, true);
	}

	private CompletableFuture<Result> start(String token, UUID player, Image upload, String capeId, boolean change) {
		if (token == null || token.isBlank() || token.equals("0") || token.length() > 16384
			|| token.chars().anyMatch(character -> character <= 32 || character >= 127)) {
			return CompletableFuture.failedFuture(new IOException("No online session. Sign in normally and restart Minecraft."));
		}
		if (!BUSY.tryAcquire()) {
			return CompletableFuture.failedFuture(new IOException("An account operation is already running."));
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
				String message = exception instanceof IOException ? exception.getMessage() : "Account operation failed.";
				failure = new IOException(message + (change ? " Account change unconfirmed; refresh before retrying." : ""));
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
		Image active = null;
		int failed = 0;
		int imported = 0;
		String firstFailure = null;
		List<SkinDocument> local;
		try {
			local = new ArrayList<>(repository.list());
		} catch (IOException | RuntimeException exception) {
			local = null;
		}
		List<AccountProfile.Skin> ordered = profile.skins().stream()
			.sorted(java.util.Comparator.comparing(AccountProfile.Skin::active).reversed()).toList();
		for (AccountProfile.Skin skin : ordered) {
			try {
				URI uri = AccountProfile.textureUri(skin.texture().toString());
				Image image = decode(send(client, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(), deadline), skin.slim());
				if (skin.active()) {
					active = image;
				}
				if (local == null) {
					failed++;
					continue;
				}
				boolean exists = local.stream().anyMatch(document -> image.matches(document.slim(), document.flatten()));
				if (!exists) {
					SkinDocument document = new SkinDocument(UUID.randomUUID(), skin.active() ? "Account skin" : "Profile skin",
						skin.slim(), List.of(new SkinLayer("Account texture", image.pixels())), 0);
					repository.save(document);
					local.add(document);
					imported++;
				}
			} catch (IOException | RuntimeException exception) {
				failed++;
				if (firstFailure == null) {
					firstFailure = exception instanceof IOException ? exception.getMessage() : "Skin import failed.";
				}
			}
		}
		String message = "Profile loaded; " + imported + " skin(s) imported locally.";
		if (upload != null) {
			message = active != null && active.matches(upload.slim(), upload.pixels())
				? "Account skin confirmed. In-game caches may require a restart."
				: "Upload accepted, but active PNG/variant not confirmed. Refresh before retrying.";
		} else if (change) {
			String actual = profile.capes().stream().filter(AccountProfile.Cape::active).map(AccountProfile.Cape::id).findFirst().orElse(null);
			message = java.util.Objects.equals(actual, capeId) ? "Account cape confirmed. In-game caches may require a restart."
				: "Cape change accepted, but active cape differs. Refresh before retrying.";
		}
		if (failed > 0) {
			message = (firstFailure == null ? "Local library unavailable." : firstFailure) + " "
				+ failed + " skin download/import(s) failed. " + message;
		}
		return new Result(profile, active, message);
	}

	private static HttpRequest.Builder authenticated(URI uri, String token) throws IOException {
		if (!"https".equals(uri.getScheme()) || !"api.minecraftservices.com".equals(uri.getHost())
			|| uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
			|| !List.of("/minecraft/profile", "/minecraft/profile/skins", "/minecraft/profile/capes/active").contains(uri.getRawPath())) {
			throw new IOException("Account request destination refused.");
		}
		return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
			.header("Accept", "application/json");
	}

	private static byte[] send(HttpClient client, HttpRequest request, long deadline) throws IOException {
		long remaining = Math.min(TimeUnit.SECONDS.toNanos(20), deadline - System.nanoTime());
		if (remaining <= 0) {
			throw new IOException("Account operation timed out.");
		}
		var pending = client.sendAsync(request, info -> new LimitedBody());
		try {
			HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException(switch (status) {
					case 401 -> "Unauthorized session (401). Sign in normally and restart Minecraft.";
					case 403 -> "Account request forbidden (403). Check account access.";
					case 429 -> "Rate limited (429). Wait before manually trying again.";
					default -> "Account/texture request failed (HTTP " + status + ").";
				});
			}
			return response.body();
		} catch (TimeoutException exception) {
			throw new IOException("Network request timed out; no automatic retry.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Account operation interrupted.");
		} catch (java.util.concurrent.ExecutionException exception) {
			throw new IOException("Network request failed or response exceeded 1 MiB; no automatic retry.");
		} finally {
			if (!pending.isDone()) {
				pending.cancel(true);
			}
		}
	}

	private static byte[] multipart(Image image, String boundary) throws IOException {
		BufferedImage png = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		png.setRGB(0, 0, 64, 64, image.pixels(), 0, 64);
		var encoded = new ByteArrayOutputStream();
		try (var output = new MemoryCacheImageOutputStream(encoded)) {
			if (!ImageIO.write(png, "png", output)) {
				throw new IOException("PNG encoder unavailable.");
			}
		}
		var body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
			+ (image.slim() ? "slim" : "classic") + "\r\n--" + boundary
			+ "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(encoded.toByteArray());
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		return body.toByteArray();
	}

	public static Image decode(byte[] bytes, boolean slim) throws IOException {
		byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
		if (bytes.length < 24 || !Arrays.equals(signature, Arrays.copyOf(bytes, 8))) {
			throw new IOException("Account texture is not a PNG.");
		}
		try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new IOException("Cannot decode account PNG.");
			}
			var reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				int height = reader.getHeight(0);
				if (!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0) != 64
					|| (height != 64 && height != 32) || (height == 32 && slim)) {
					throw new IOException("Unsupported account skin dimensions.");
				}
				BufferedImage png = reader.read(0);
				int[] pixels = new int[4096];
				png.getRGB(0, 0, 64, height, pixels, 0, 64);
				if (height == 32) {
					mirror(pixels, 4, 16, 20, 48, 4, 4);
					mirror(pixels, 8, 16, 24, 48, 4, 4);
					mirror(pixels, 0, 20, 24, 52, 4, 12);
					mirror(pixels, 4, 20, 20, 52, 4, 12);
					mirror(pixels, 8, 20, 16, 52, 4, 12);
					mirror(pixels, 12, 20, 28, 52, 4, 12);
					mirror(pixels, 44, 16, 36, 48, 4, 4);
					mirror(pixels, 48, 16, 40, 48, 4, 4);
					mirror(pixels, 40, 20, 40, 52, 4, 12);
					mirror(pixels, 44, 20, 36, 52, 4, 12);
					mirror(pixels, 48, 20, 32, 52, 4, 12);
					mirror(pixels, 52, 20, 44, 52, 4, 12);
				}
				for (int index = 0; index < pixels.length; index++) {
					if (pixels[index] >>> 24 == 0) {
						pixels[index] = 0;
					}
				}
				return new Image(slim, pixels);
			} finally {
				reader.dispose();
			}
		}
	}

	private static void mirror(int[] pixels, int sx, int sy, int dx, int dy, int width, int height) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				pixels[(dy + y) * 64 + dx + x] = pixels[(sy + y) * 64 + sx + width - x - 1];
			}
		}
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
