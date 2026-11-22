package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.skin.PngCodec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class TemplateSkins {
	/** Vanilla skins are tiny; anything larger is a broken or hostile resource pack, refused before decoding. */
	private static final int MAX_BYTES = 1024 * 1024;

	private TemplateSkins() {
	}

	/**
	 * The pixels of a vanilla default skin drawn for the given arm width. Vanilla ships every default skin in both
	 * {@code wide} (4 px arms) and {@code slim} (3 px arms) variants, so Steve and Alex fit either model.
	 */
	static int[] pixels(String skin, boolean slim) throws IOException {
		Identifier id = Identifier.fromNamespaceAndPath("minecraft",
			"textures/entity/player/" + (slim ? "slim/" : "wide/") + skin + ".png");
		var resource = Minecraft.getInstance().getResourceManager().getResource(id)
			.orElseThrow(() -> new IOException("Vanilla template skin is missing: " + id));
		byte[] bytes;
		try (InputStream input = resource.open()) {
			bytes = input.readNBytes(MAX_BYTES + 1);
		}
		if (bytes.length > MAX_BYTES) {
			throw new IOException("Vanilla template skin is larger than 1 MiB.");
		}
		// Header dimensions are checked before any pixels are decoded (no decompression bombs from resource packs).
		BufferedImage image = PngCodec.decode(bytes, 64, 64 * 64);
		if (image.getWidth() != 64 || image.getHeight() != 64) {
			throw new IOException("Vanilla template skin has unexpected dimensions.");
		}
		return image.getRGB(0, 0, 64, 64, null, 0, 64);
	}
}
