package de.catsmc.mcskins.client.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class TemplateSkins {
	private TemplateSkins() {
	}

	static int[] pixels(boolean slim) throws IOException {
		Identifier id = Identifier.fromNamespaceAndPath("minecraft",
			slim ? "textures/entity/player/slim/alex.png" : "textures/entity/player/wide/steve.png");
		var resource = Minecraft.getInstance().getResourceManager().getResource(id)
			.orElseThrow(() -> new IOException("Vanilla template skin is missing."));
		try (InputStream input = resource.open()) {
			BufferedImage image = ImageIO.read(input);
			if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
				throw new IOException("Vanilla template skin has unexpected dimensions.");
			}
			return image.getRGB(0, 0, 64, 64, null, 0, 64);
		}
	}
}
