package de.catsmc.mcskins.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Bounded PNG decoding and encoding shared by imports, account textures and capes. */
public final class PngCodec {
	public static final int MAX_BYTES = 16 * 1024 * 1024;
	private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

	private PngCodec() {
	}

	public static boolean isPng(byte[] bytes) {
		return bytes != null && bytes.length > SIGNATURE.length
			&& Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, SIGNATURE.length));
	}

	/** Decodes a PNG after checking its header dimensions against the given limits. */
	public static BufferedImage decode(byte[] bytes, int maxSide, long maxPixels) throws IOException {
		if (bytes.length > MAX_BYTES) {
			throw new IOException("PNG must be at most 16 MiB.");
		}
		if (!isPng(bytes)) {
			throw new IOException("Not a PNG image.");
		}
		try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new IOException("Cannot decode PNG image.");
			}
			var reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (!reader.getFormatName().equalsIgnoreCase("png") || width < 1 || height < 1
					|| width > maxSide || height > maxSide || (long) width * height > maxPixels) {
					throw new IOException("PNG dimensions " + width + " x " + height + " are not supported.");
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw new IOException("Cannot decode PNG image.");
				}
				return image;
			} finally {
				reader.dispose();
			}
		}
	}

	/** Reads ARGB pixels and clears the color of fully transparent pixels. */
	public static int[] argb(BufferedImage image, int width, int height) {
		int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		for (int index = 0; index < pixels.length; index++) {
			if (pixels[index] >>> 24 == 0) {
				pixels[index] = 0;
			}
		}
		return pixels;
	}

	public static byte[] encode(int[] argb, int width, int height) throws IOException {
		if (argb.length != width * height) {
			throw new IllegalArgumentException("Pixel count does not match the image size.");
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, argb, 0, width);
		var bytes = new ByteArrayOutputStream();
		try (var stream = new MemoryCacheImageOutputStream(bytes)) {
			if (!ImageIO.write(image, "png", stream)) {
				throw new IOException("No PNG encoder is available.");
			}
		}
		return bytes.toByteArray();
	}
}
