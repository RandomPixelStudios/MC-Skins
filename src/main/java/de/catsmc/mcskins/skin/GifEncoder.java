package de.catsmc.mcskins.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Writes looping animated GIFs with the JDK's GIF encoder (each frame gets its own 256-color palette). */
public final class GifEncoder {
	public static final int MAX_FRAMES = 300;

	private GifEncoder() {
	}

	/** Encodes opaque ARGB frames ({@code width * height} each) shown {@code delayMillis} apart, looping forever. */
	public static byte[] encode(List<int[]> frames, int width, int height, int delayMillis) throws IOException {
		if (frames.isEmpty() || frames.size() > MAX_FRAMES) {
			throw new IllegalArgumentException("A GIF needs between 1 and 300 frames.");
		}
		if (width < 1 || height < 1 || width > 2048 || height > 2048) {
			throw new IllegalArgumentException("GIF frames must be between 1 and 2048 pixels per side.");
		}
		var writers = ImageIO.getImageWritersByFormatName("gif");
		if (!writers.hasNext()) {
			throw new IOException("No GIF encoder is available.");
		}
		ImageWriter writer = writers.next();
		var bytes = new ByteArrayOutputStream();
		try (var stream = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(stream);
			writer.prepareWriteSequence(null);
			int delay = Math.clamp(Math.round(delayMillis / 10F), 2, 6553);
			boolean first = true;
			for (int[] frame : frames) {
				if (frame.length != width * height) {
					throw new IllegalArgumentException("Every frame must have width * height pixels.");
				}
				BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
				image.setRGB(0, 0, width, height, frame, 0, width);
				IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
				configure(metadata, delay, first);
				first = false;
				writer.writeToSequence(new IIOImage(image, null, metadata), null);
			}
			writer.endWriteSequence();
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static void configure(IIOMetadata metadata, int delayCentiseconds, boolean first) throws IOException {
		String format = metadata.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
		IIOMetadataNode control = child(root, "GraphicControlExtension");
		control.setAttribute("disposalMethod", "none");
		control.setAttribute("userInputFlag", "FALSE");
		control.setAttribute("transparentColorFlag", "FALSE");
		control.setAttribute("delayTime", Integer.toString(delayCentiseconds));
		control.setAttribute("transparentColorIndex", "0");
		if (first) {
			IIOMetadataNode applications = child(root, "ApplicationExtensions");
			IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
			loop.setAttribute("applicationID", "NETSCAPE");
			loop.setAttribute("authenticationCode", "2.0");
			loop.setUserObject(new byte[]{1, 0, 0});
			applications.appendChild(loop);
		}
		metadata.setFromTree(format, root);
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int index = 0; index < root.getLength(); index++) {
			if (root.item(index).getNodeName().equalsIgnoreCase(name)) {
				return (IIOMetadataNode) root.item(index);
			}
		}
		IIOMetadataNode node = new IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}

	/** Box-filters an ARGB image down so neither side exceeds {@code maxSide}; returns {width, height} first. */
	public static int[][] downscale(int[] pixels, int width, int height, int maxSide) {
		int factor = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) maxSide));
		int newWidth = Math.max(1, width / factor);
		int newHeight = Math.max(1, height / factor);
		int[] result = new int[newWidth * newHeight];
		for (int y = 0; y < newHeight; y++) {
			for (int x = 0; x < newWidth; x++) {
				long red = 0;
				long green = 0;
				long blue = 0;
				int count = 0;
				for (int dy = 0; dy < factor; dy++) {
					for (int dx = 0; dx < factor; dx++) {
						int sx = x * factor + dx;
						int sy = y * factor + dy;
						if (sx < width && sy < height) {
							int color = pixels[sy * width + sx];
							red += (color >>> 16) & 255;
							green += (color >>> 8) & 255;
							blue += color & 255;
							count++;
						}
					}
				}
				result[y * newWidth + x] = 0xFF000000 | (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count);
			}
		}
		return new int[][]{{newWidth, newHeight}, result};
	}
}
