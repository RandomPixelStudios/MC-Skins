package de.catsmc.mcskins.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.ModelRenderer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.system.MemoryUtil;

/** Draws a {@link ModelRenderer} at framebuffer resolution inside a GUI rectangle and maps the mouse back to texels. */
final class SkinModelPreview implements AutoCloseable {
	private static final long ANIMATION_FRAME_NANOS = 33_000_000L;
	private static final int HIGHLIGHT = 0x60FFFFFF;
	private final ModelRenderer renderer = new ModelRenderer();
	private final int[] skin = new int[4096];
	private int[] frame = new int[1];
	private int[] row = new int[1];
	private long lastAnimationRender;
	private BitSet highlight = new BitSet();
	private BitSet drawnHighlight = new BitSet();
	private int left;
	private int top;
	private int width = 1;
	private int height = 1;
	private long lastFrame;
	private int lastDeviceWidth;
	private int lastDeviceHeight;
	private boolean frozen;
	private boolean dirty = true;
	private DynamicTexture canvas;
	private int[] cape;

	/** Settings changed through this accessor must be followed by {@link #invalidate()}. */
	ModelRenderer renderer() {
		return renderer;
	}

	void invalidate() {
		dirty = true;
	}

	void texture(int[] pixels, boolean slim) {
		if (!Arrays.equals(skin, pixels) || renderer.slim() != slim) {
			System.arraycopy(pixels, 0, skin, 0, 4096);
			renderer.skin(pixels, slim);
			dirty = true;
		}
	}

	void cape(int[] cape) {
		if (cape != this.cape) {
			this.cape = cape;
			renderer.cape(cape);
			dirty = true;
		}
	}

	void viewport(int left, int top, int width, int height) {
		if (left == this.left && top == this.top && Math.max(1, width) == this.width && Math.max(1, height) == this.height) {
			return;
		}
		this.left = left;
		this.top = top;
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		dirty = true;
	}

	boolean contains(double x, double y) {
		return x >= left && y >= top && x < left + width && y < top + height;
	}

	/** Orbits by GUI pixels dragged. */
	void orbit(double dx, double dy) {
		renderer.orbit(dx * 0.012, dy * 0.012);
		dirty = true;
	}

	void pan(double dx, double dy) {
		renderer.pan(dx / width, dy / height);
		dirty = true;
	}

	void zoom(double amount) {
		renderer.zoom(amount);
		dirty = true;
	}

	void reset() {
		renderer.reset();
		dirty = true;
	}

	void view(int preset) {
		renderer.view(preset);
		dirty = true;
	}

	/** Pauses animation while painting so the texel under the cursor does not move away. */
	void freeze(boolean frozen) {
		this.frozen = frozen;
		lastFrame = 0;
	}

	void highlight(BitSet pixels) {
		highlight = pixels == null ? new BitSet() : pixels;
	}

	/** The texture pixel under a GUI point, or -1. */
	int pick(double x, double y) {
		if (!contains(x, y) || renderer.width() <= 1) {
			return -1;
		}
		int column = (int) ((x - left) * renderer.width() / width);
		int row = (int) ((y - top) * renderer.height() / height);
		return renderer.target(column, row);
	}

	void render(GuiGraphicsExtractor graphics) {
		long now = System.nanoTime();
		if (lastFrame != 0 && !frozen && renderer.animated()) {
			renderer.advance((now - lastFrame) / 1_000_000_000.0);
			// Animation re-renders at most ~30 times per second; time still advances every frame.
			if (now - lastAnimationRender >= ANIMATION_FRAME_NANOS) {
				dirty = true;
			}
		}
		lastFrame = now;
		double scale = GameCompat.guiScale(Minecraft.getInstance());
		int deviceWidth = Math.max(1, (int) Math.round(width * scale));
		int deviceHeight = Math.max(1, (int) Math.round(height * scale));
		boolean full = false;
		if (deviceWidth != lastDeviceWidth || deviceHeight != lastDeviceHeight) {
			lastDeviceWidth = deviceWidth;
			lastDeviceHeight = deviceHeight;
			dirty = true;
		}
		if (dirty) {
			renderer.render(deviceWidth, deviceHeight);
			dirty = false;
			full = true;
			lastAnimationRender = now;
		}
		int w = renderer.width();
		int h = renderer.height();
		if (canvas == null || canvas.getPixels().getWidth() != w || canvas.getPixels().getHeight() != h) {
			closeCanvas();
			canvas = new DynamicTexture("MC-Skins model preview", w, h, false);
			full = true;
		}
		if (frame.length != w * h) {
			frame = new int[w * h];
			full = true;
		}
		if (full) {
			renderer.compose(frame, highlight, HIGHLIGHT);
			copy(0, 0, w, h);
			canvas.upload();
			drawnHighlight = (BitSet) highlight.clone();
		} else if (!highlight.equals(drawnHighlight)) {
			// Only the hover highlight moved: recompose just the screen area of the texels that changed.
			BitSet changed = (BitSet) highlight.clone();
			changed.xor(drawnHighlight);
			int[] region = renderer.bounds(changed);
			if (region != null) {
				// One extra pixel around the area: outlines of neighbouring highlighted texels change too.
				int x0 = region[0] - 1;
				int y0 = region[1] - 1;
				int x1 = region[2] + 1;
				int y1 = region[3] + 1;
				renderer.compose(frame, highlight, HIGHLIGHT, x0, y0, x1, y1);
				copy(x0, y0, x1, y1);
				canvas.upload();
			}
			drawnHighlight = (BitSet) highlight.clone();
		}
		GameCompat.blitPreview(graphics, canvas, left, top, width, height, true);
	}

	/** Bulk-copies an ARGB rectangle of {@link #frame} into the texture's RGBA (ABGR int) memory. */
	private void copy(int x0, int y0, int x1, int y1) {
		NativeImage image = canvas.getPixels();
		int w = image.getWidth();
		int minX = Math.clamp(x0, 0, w);
		int maxX = Math.clamp(x1, 0, w);
		int minY = Math.clamp(y0, 0, image.getHeight());
		int maxY = Math.clamp(y1, 0, image.getHeight());
		int length = maxX - minX;
		if (length <= 0 || maxY <= minY) {
			return;
		}
		IntBuffer target = MemoryUtil.memIntBuffer(image.getPointer(), w * image.getHeight());
		if (row.length < length) {
			row = new int[w];
		}
		for (int y = minY; y < maxY; y++) {
			int offset = y * w + minX;
			for (int x = 0; x < length; x++) {
				int argb = frame[offset + x];
				row[x] = argb & 0xFF00FF00 | (argb >>> 16) & 0xFF | (argb & 0xFF) << 16;
			}
			target.put(offset, row, 0, length);
		}
	}

	private void closeCanvas() {
		if (canvas != null) {
			canvas.close();
			canvas = null;
		}
	}

	@Override
	public void close() {
		closeCanvas();
		lastFrame = 0;
		dirty = true;
	}
}
