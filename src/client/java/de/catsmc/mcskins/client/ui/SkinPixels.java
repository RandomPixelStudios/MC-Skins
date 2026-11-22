package de.catsmc.mcskins.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

final class SkinPixels {
	private SkinPixels() {
	}

	static void head(GuiGraphicsExtractor graphics, int[] pixels, int x, int y, int scale) {		for (int row = 0; row < 8; row++) {
			for (int column = 0; column < 8; column++) {
				int background = ((row + column) & 1) == 0 ? 0xFF5A5A5A : 0xFF3C3C3C;
				int base = over(pixels[(row + 8) * 64 + column + 8], background);
				int color = over(pixels[(row + 8) * 64 + column + 40], base);
				graphics.fill(x + column * scale, y + row * scale, x + (column + 1) * scale, y + (row + 1) * scale, color);
			}
		}
	}

	static void cape(GuiGraphicsExtractor graphics, int[] pixels, int x, int y, int scale) {
		for (int row = 0; row < 16; row++) {
			for (int column = 0; column < 10; column++) {
				int background = ((row + column) & 1) == 0 ? 0xFF5A5A5A : 0xFF3C3C3C;
				int color = over(pixels[(row + 1) * 64 + column + 1], background);
				graphics.fill(x + column * scale, y + row * scale, x + (column + 1) * scale, y + (row + 1) * scale, color);
			}
		}
	}

	static void canvas(GuiGraphicsExtractor graphics, int[] pixels, int x, int y, int scale, boolean grid) {
		for (int row = 0; row < 64; row++) {
			for (int column = 0; column < 64; column++) {
				int background = ((row / 4 + column / 4) & 1) == 0 ? 0xFF6E6E6E : 0xFF484848;
				int color = over(pixels[row * 64 + column], background);
				int left = x + column * scale;
				int top = y + row * scale;
				graphics.fill(left, top, left + scale, top + scale, color);
			}
		}
		if (grid && scale >= 4) {
			for (int index = 0; index <= 64; index++) {
				graphics.fill(x + index * scale, y, x + index * scale + 1, y + 64 * scale, 0x55404040);
				graphics.fill(x, y + index * scale, x + 64 * scale, y + index * scale + 1, 0x55404040);
			}
		}
	}

	static int over(int foreground, int background) {
		int alpha = foreground >>> 24;
		int inverse = 255 - alpha;
		int red = (((foreground >>> 16) & 255) * alpha + ((background >>> 16) & 255) * inverse + 127) / 255;
		int green = (((foreground >>> 8) & 255) * alpha + ((background >>> 8) & 255) * inverse + 127) / 255;
		int blue = ((foreground & 255) * alpha + (background & 255) * inverse + 127) / 255;
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}
}
