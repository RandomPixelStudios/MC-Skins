package de.catsmc.mcskins.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Parsing for the public Mojang profile lookup: player name to UUID ({@code api.mojang.com}), then the session
 * profile ({@code sessionserver.mojang.com}) whose base64 "textures" property names the skin, its model and the cape.
 */
public final class MojangProfiles {
	public static final String NAME_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/";
	public static final String SESSION_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

	/** The textures of a player profile; {@code skin} is null when the player uses a default skin. */
	public record Textures(String name, URI skin, boolean slim, URI cape) {
	}

	/** A failure the user should see; {@code reason} is a short code the client turns into a translated text. */
	public static final class LookupException extends IOException {
		private final String reason;

		public LookupException(String reason) {
			super("Profile lookup failed: " + reason);
			this.reason = reason;
		}

		public String reason() {
			return reason;
		}
	}

	private MojangProfiles() {
	}

	public static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_]{1,16}");
	}

	/** Compact (no dashes) UUID form used by the session server URL. */
	public static String compact(UUID id) {
		return id.toString().replace("-", "");
	}

	/** Reads {@code {"id": "...", "name": "..."}} from the name lookup. */
	public static UUID parseNameLookup(byte[] json) throws IOException {
		JsonObject object = object(json);
		String id = string(object, "id");
		if (id == null) {
			throw new LookupException("unknown");
		}
		return uuid(id);
	}

	/** Reads the session profile and decodes its textures property. */
	public static Textures parseProfile(byte[] json) throws IOException {
		JsonObject object = object(json);
		String name = string(object, "name");
		JsonElement properties = object.get("properties");
		if (properties == null || !properties.isJsonArray()) {
			throw new LookupException("invalid");
		}
		for (JsonElement property : properties.getAsJsonArray()) {
			if (property.isJsonObject() && "textures".equals(string(property.getAsJsonObject(), "name"))) {
				String value = string(property.getAsJsonObject(), "value");
				if (value == null) {
					throw new LookupException("invalid");
				}
				byte[] decoded;
				try {
					decoded = Base64.getDecoder().decode(value);
				} catch (IllegalArgumentException exception) {
					throw new LookupException("invalid");
				}
				return parseTextures(name, decoded);
			}
		}
		return new Textures(name, null, false, null);
	}

	static Textures parseTextures(String name, byte[] json) throws IOException {
		JsonObject root = object(json);
		JsonElement textures = root.get("textures");
		if (textures == null || !textures.isJsonObject()) {
			return new Textures(name, null, false, null);
		}
		URI skin = null;
		boolean slim = false;
		URI cape = null;
		JsonElement skinElement = textures.getAsJsonObject().get("SKIN");
		if (skinElement != null && skinElement.isJsonObject()) {
			JsonObject skinObject = skinElement.getAsJsonObject();
			skin = textureUri(string(skinObject, "url"));
			JsonElement metadata = skinObject.get("metadata");
			if (metadata != null && metadata.isJsonObject()) {
				slim = "slim".equalsIgnoreCase(string(metadata.getAsJsonObject(), "model"));
			}
		}
		JsonElement capeElement = textures.getAsJsonObject().get("CAPE");
		if (capeElement != null && capeElement.isJsonObject()) {
			cape = textureUri(string(capeElement.getAsJsonObject(), "url"));
		}
		return new Textures(name, skin, slim, cape);
	}

	/** Accepts only texture URLs on {@code textures.minecraft.net} and returns them as https. */
	public static URI textureUri(String value) throws IOException {
		if (value == null) {
			throw new LookupException("invalid");
		}
		try {
			URI uri = URI.create(value);
			if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
				|| !"textures.minecraft.net".equals(uri.getHost()) || uri.getPort() != -1
				|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| uri.getRawPath() == null || !uri.getRawPath().matches("/texture/[0-9a-fA-F]{32,64}")) {
				throw new LookupException("invalid");
			}
			return URI.create("https://textures.minecraft.net" + uri.getRawPath());
		} catch (IllegalArgumentException exception) {
			throw new LookupException("invalid");
		}
	}

	/**
	 * Checks a user-entered image URL: http(s) only, no credentials, at most 2048 characters. Returns the parsed URI.
	 */
	public static URI imageUrl(String value) throws IOException {
		if (value == null || value.isBlank() || value.length() > 2048) {
			throw new LookupException("url");
		}
		try {
			URI uri = new URI(value.strip());
			if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null || uri.getUserInfo() != null) {
				throw new LookupException("url");
			}
			return uri;
		} catch (java.net.URISyntaxException exception) {
			throw new LookupException("url");
		}
	}

	private static JsonObject object(byte[] json) throws IOException {
		try (var reader = new JsonReader(new StringReader(new String(json, StandardCharsets.UTF_8)))) {
			reader.setStrictness(Strictness.STRICT);
			reader.setNestingLimit(16);
			JsonElement element = JsonParser.parseReader(reader);
			if (!element.isJsonObject()) {
				throw new LookupException("invalid");
			}
			return element.getAsJsonObject();
		} catch (LookupException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw new LookupException("invalid");
		}
	}

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			? element.getAsString() : null;
	}

	static UUID uuid(String value) throws IOException {
		String compact = value.replace("-", "");
		if (!compact.matches("[0-9a-fA-F]{32}")) {
			throw new LookupException("invalid");
		}
		return UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
			+ compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20));
	}
}
