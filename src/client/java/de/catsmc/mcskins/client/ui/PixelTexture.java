package de.catsmc.mcskins.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import de.catsmc.mcskins.client.compat.GameCompat;
import java.nio.IntBuffer;
import java.util.Arrays;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

/**
 * A small ARGB image drawn as one stretched texture instead of one GUI fill per pixel. It only uploads when the
 * pixels actually change.
 */
final class PixelTexture implements AutoCloseable {
	private final String name;
	private DynamicTexture texture;
	private int[] shown = new int[0];
	private int width;
	private int height;

	PixelTexture(String name) {
		this.name = name;
	}

	/** Sets the ARGB pixels (row-major, {@code width * height}); uploads only if they differ from the last call. */
	void set(int[] argb, int width, int height) {
		if (argb.length != width * height) {
			throw new IllegalArgumentException("Pixel count does not match the texture size.");
		}
		if (texture != null && width == this.width && height == this.height && Arrays.equals(argb, shown)) {
			return;
		}
		if (texture == null || width != this.width || height != this.height) {
			close();
			texture = new DynamicTexture(name, width, height, false);
			this.width = width;
			this.height = height;
		}
		NativeImage image = texture.getPixels();
		IntBuffer target = MemoryUtil.memIntBuffer(image.getPointer(), width * height);
		int[] abgr = new int[argb.length];
		for (int index = 0; index < argb.length; index++) {
			int color = argb[index];
			abgr[index] = color & 0xFF00FF00 | (color >>> 16) & 0xFF | (color & 0xFF) << 16;
		}
		target.put(0, abgr, 0, abgr.length);
		texture.upload();
		shown = argb.clone();
	}

	void draw(GuiGraphicsExtractor graphics, int left, int top, int drawWidth, int drawHeight) {
		if (texture != null && drawWidth > 0 && drawHeight > 0) {
			GameCompat.blitPreview(graphics, texture, left, top, drawWidth, drawHeight, true);
		}
	}

	@Override
	public void close() {
		if (texture != null) {
			texture.close();
			texture = null;
		}
		shown = new int[0];
	}
}
