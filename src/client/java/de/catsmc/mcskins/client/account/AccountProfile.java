package de.catsmc.mcskins.client.account;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record AccountProfile(UUID id, String name, List<Skin> skins, List<Cape> capes) {
	public AccountProfile {
		skins = List.copyOf(skins);
		capes = List.copyOf(capes);
	}

	public record Skin(String id, boolean active, URI texture, boolean slim) {
	}

	public record Cape(String id, boolean active, URI texture, String alias) {
	}

	public static AccountProfile parse(byte[] bytes, UUID expectedId) throws IOException {
		try (var reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
			reader.setStrictness(Strictness.STRICT);
			reader.setNestingLimit(16);
			UUID id = null;
			String name = null;
			List<Skin> skins = null;
			List<Cape> capes = null;
			var fields = new HashSet<String>();
			reader.beginObject();
			while (reader.hasNext()) {
				String field = reader.nextName();
				if (!fields.add(field)) {
					throw invalid();
				}
				switch (field) {
					case "id" -> id = uuid(string(reader));
					case "name" -> name = string(reader);
					case "skins" -> skins = skins(reader);
					case "capes" -> capes = capes(reader);
					default -> reader.skipValue();
				}
			}
			reader.endObject();
			if (reader.peek() != JsonToken.END_DOCUMENT || !expectedId.equals(id) || name == null
				|| !name.matches("[A-Za-z0-9_]{1,16}") || skins == null || capes == null) {
				throw invalid();
			}
			return new AccountProfile(id, name, skins, capes);
		} catch (IOException | RuntimeException exception) {
			throw invalid();
		}
	}

	private static List<Skin> skins(JsonReader reader) throws IOException {
		var skins = new ArrayList<Skin>();
		var ids = new HashSet<String>();
		int activeCount = 0;
		reader.beginArray();
		while (reader.hasNext()) {
			if (skins.size() >= 128) {
				throw invalid();
			}
			var values = entry(reader);
			String id = uuid(required(values, "id")).toString();
			boolean active = active(required(values, "state"));
			String variant = required(values, "variant");
			if (!ids.add(id) || (active && ++activeCount > 1)
				|| !(variant.equals("CLASSIC") || variant.equals("SLIM"))) {
				throw invalid();
			}
			skins.add(new Skin(id, active, textureUri(required(values, "url")), variant.equals("SLIM")));
		}
		reader.endArray();
		return skins;
	}

	private static List<Cape> capes(JsonReader reader) throws IOException {
		var capes = new ArrayList<Cape>();
		var ids = new HashSet<String>();
		int activeCount = 0;
		reader.beginArray();
		while (reader.hasNext()) {
			if (capes.size() >= 128) {
				throw invalid();
			}
			var values = entry(reader);
			String id = uuid(required(values, "id")).toString();
			boolean active = active(required(values, "state"));
			String alias = required(values, "alias");
			if (!ids.add(id) || (active && ++activeCount > 1) || alias.isBlank() || alias.length() > 64
				|| alias.codePoints().anyMatch(character -> Character.isISOControl(character) || character == 0x00A7)) {
				throw invalid();
			}
			capes.add(new Cape(id, active, textureUri(required(values, "url")), alias));
		}
		reader.endArray();
		return capes;
	}

	private static java.util.Map<String, String> entry(JsonReader reader) throws IOException {
		var values = new java.util.HashMap<String, String>();
		var fields = new HashSet<String>();
		reader.beginObject();
		while (reader.hasNext()) {
			String field = reader.nextName();
			if (!fields.add(field)) {
				throw invalid();
			}
			switch (field) {
				case "id", "state", "url", "variant", "alias" -> values.put(field, string(reader));
				default -> reader.skipValue();
			}
		}
		reader.endObject();
		return values;
	}

	private static String string(JsonReader reader) throws IOException {
		if (reader.peek() != JsonToken.STRING) {
			throw invalid();
		}
		String value = reader.nextString();
		if (value.length() > 512) {
			throw invalid();
		}
		return value;
	}

	private static String required(java.util.Map<String, String> values, String key) throws IOException {
		String value = values.get(key);
		if (value == null) {
			throw invalid();
		}
		return value;
	}

	private static boolean active(String value) throws IOException {
		if (!value.equals("ACTIVE") && !value.equals("INACTIVE")) {
			throw invalid();
		}
		return value.equals("ACTIVE");
	}

	private static UUID uuid(String value) throws IOException {
		String compact = value.replace("-", "");
		if (!compact.matches("[0-9a-fA-F]{32}") || !(value.length() == 32 || value.length() == 36)) {
			throw invalid();
		}
		UUID id = UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
			+ compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20));
		if (value.length() == 36 && !id.toString().equalsIgnoreCase(value)) {
			throw invalid();
		}
		return id;
	}

	static URI textureUri(String value) throws IOException {
		try {
			URI uri = URI.create(value);
			if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
				|| !"textures.minecraft.net".equals(uri.getHost()) || uri.getPort() != -1
				|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| !uri.getRawPath().matches("/texture/[0-9a-fA-F]{32,64}")) {
				throw invalid();
			}
			return URI.create("https://textures.minecraft.net" + uri.getRawPath());
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private static IOException invalid() {
		return new IOException("Invalid or unsupported account profile response.");
	}
}
