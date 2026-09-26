package de.catsmc.mcskins.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import de.catsmc.mcskins.client.compat.GameCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;

final class SkinModelPreview implements AutoCloseable {
	enum Animation { NONE, IDLE, WALK, RUN }
	enum Shell { BASE, OUTER }
	enum Part { ALL, HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }
	enum Pose { DEFAULT, HEAD_BACK, ARMS_UP, T_POSE, STAR, EXPLODE, CROUCH, SIT, WAVE }

	record Hit(int x, int y, boolean outer, int x0, int y0, int w, int h) {
		Hit(int x, int y, boolean outer) {
			this(x, y, outer, x, y, 1, 1);
		}
	}

	private record Point(double x, double y, double z) {
	}

	private record Face(Point origin, Point across, Point down, int u, int v, int columns, int rows,
		boolean outer, double determinant, double light, boolean cape) {
	}

	private static final int BACKGROUND = 0x00000000;
	private static final int MAX_PIXELS = 750_000;
	private final List<Face> faces = new ArrayList<>(40);
	private final int[] pixels = new int[4096];
	private int[] cape;
	private boolean slim;
	private Animation animation = Animation.NONE;
	private Pose pose = Pose.DEFAULT;
	private Shell shell = Shell.BASE;
	private Part part = Part.ALL;
	private double yaw = -0.4;
	private double pitch = -0.16;
	private double zoom = 1;
	private double panX;
	private double panY;
	private double time;
	private double capeTime;
	private long lastFrame;
	private boolean frozen;
	private boolean geometryDirty = true;
	private boolean rasterDirty = true;
	private boolean uploadDirty = true;
	private int left;
	private int top;
	private int width = 1;
	private int height = 1;
	private double unit;
	private double centerX;
	private double centerY;
	private double minX;
	private double maxX;
	private double minY;
	private double maxY;
	private int rasterWidth;
	private int rasterHeight;
	private int[] colors = new int[0];
	private int[] targets = new int[0];
	private double[] depths = new double[0];
	private double hoverX = Double.NaN;
	private double hoverY = Double.NaN;
	private int highlighted = -1;
	private DynamicTexture canvas;

	void texture(int[] pixels, boolean slim) {
		if (pixels == null || pixels.length != 4096) {
			throw new IllegalArgumentException("Skin texture must contain 64 x 64 pixels");
		}
		if (!Arrays.equals(this.pixels, pixels)) {
			System.arraycopy(pixels, 0, this.pixels, 0, pixels.length);
			rasterDirty = true;
		}
		geometryDirty |= this.slim != slim;
		this.slim = slim;
	}

	void cape(int[] cape) {
		if (cape != null && cape.length != 2048) {
			throw new IllegalArgumentException("Cape texture must contain 64 x 32 pixels");
		}
		this.cape = cape;
		geometryDirty = true;
	}

	Shell shell() {
		return shell;
	}

	void shell(Shell shell) {
		Objects.requireNonNull(shell);
		if (this.shell != shell) {
			this.shell = shell;
			geometryDirty = true;
		}
	}

	boolean showOuter() {
		return shell == Shell.OUTER;
	}

	void showOuter(boolean visible) {
		shell(visible ? Shell.OUTER : Shell.BASE);
	}

	Part part() {
		return part;
	}

	void part(Part part) {
		Objects.requireNonNull(part);
		if (this.part != part) {
			this.part = part;
			zoom = 1;
			panX = 0;
			panY = 0;
			geometryDirty = true;
		}
	}

	Animation animation() {
		return animation;
	}

	Pose pose() {
		return pose;
	}

	void pose(Pose pose) {
		this.pose = Objects.requireNonNull(pose);
		geometryDirty = true;
	}

	void animate(Animation animation) {
		this.animation = Objects.requireNonNull(animation);
		time = 0;
		lastFrame = 0;
		geometryDirty = true;
	}

	void freeze(boolean frozen) {
		this.frozen = frozen;
		lastFrame = 0;
	}

	void reset() {
		yaw = -0.4;
		pitch = -0.16;
		zoom = 1;
		panX = 0;
		panY = 0;
		geometryDirty = true;
	}

	void view(int preset) {
		if (preset < 0 || preset > 5) {
			throw new IllegalArgumentException("View preset must be between 0 and 5");
		}
		yaw = switch (preset) {
			case 1 -> Math.PI;
			case 2 -> -Math.PI / 2;
			case 3 -> Math.PI / 2;
			default -> 0;
		};
		pitch = preset == 4 ? -Math.PI / 2 : preset == 5 ? Math.PI / 2 : 0;
		zoom = 1;
		panX = 0;
		panY = 0;
		geometryDirty = true;
	}

	void viewport(int left, int top, int width, int height) {
		width = Math.max(1, width);
		height = Math.max(1, height);
		if (this.left != left || this.top != top || this.width != width || this.height != height) {
			this.left = left;
			this.top = top;
			this.width = width;
			this.height = height;
			geometryDirty = true;
		}
	}

	boolean contains(double x, double y) {
		return x >= left && y >= top && x < left + width && y < top + height;
	}

	void orbit(double dx, double dy) {
		if (Double.isFinite(dx) && Double.isFinite(dy)) {
			yaw = Math.IEEEremainder(yaw + dx * 0.012, Math.PI * 2);
			pitch = Math.clamp(pitch + dy * 0.012, -Math.PI / 2, Math.PI / 2);
			geometryDirty = true;
		}
	}

	void pan(double dx, double dy) {
		if (Double.isFinite(dx) && Double.isFinite(dy)) {
			panX = Math.clamp(panX + dx / width, -1, 1);
			panY = Math.clamp(panY + dy / height, -1, 1);
			geometryDirty = true;
		}
	}

	void zoom(double amount) {
		if (Double.isFinite(amount)) {
			zoom = Math.clamp(zoom * Math.exp(Math.clamp(amount, -10, 10) * 0.15), 0.25, 16);
			geometryDirty = true;
		}
	}

	void hover(double x, double y) {
		hoverX = x;
		hoverY = y;
	}

	Hit pick(double x, double y) {
		if (!contains(x, y)) {
			return null;
		}
		prepare();
		int target = target(x, y);
		if (target < 0) {
			return null;
		}
		Face face = faces.get(target >>> 12);
		if (face.cape) {
			return null;
		}
		int pixel = target & 4095;
		return new Hit(pixel & 63, pixel >>> 6, face.outer, face.u, face.v, face.columns, face.rows);
	}

	void render(GuiGraphicsExtractor graphics) {
		long now = System.nanoTime();
		if (lastFrame != 0) {
			double delta = Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
			if (!frozen) {
				capeTime += delta;
				if (animation != Animation.NONE) {
					time += delta;
					geometryDirty = true;
				}
			}
		}
		lastFrame = now;
		prepare();
		int hovered = Double.isFinite(hoverX) && Double.isFinite(hoverY) ? target(hoverX, hoverY) : -1;
		if (hovered >= 0 && faces.get(hovered >>> 12).cape) {
			hovered = -1;
		}
		if (highlighted != hovered) {
			highlighted = hovered;
			uploadDirty = true;
		}
		if (canvas == null || canvas.getPixels().getWidth() != rasterWidth
			|| canvas.getPixels().getHeight() != rasterHeight) {
			close();
			canvas = new DynamicTexture("MC-Skins editable model", rasterWidth, rasterHeight, false);
		}
		if (uploadDirty) {
			upload();
		}
		GameCompat.blitPreview(graphics, canvas, left, top, width, height);
	}

	@Override
	public void close() {
		if (canvas != null) {
			canvas.close();
			canvas = null;
		}
		lastFrame = 0;
		uploadDirty = true;
	}

	private int target(double x, double y) {
		if (!contains(x, y) || targets.length == 0) {
			return -1;
		}
		int column = Math.min(rasterWidth - 1, (int) ((x - left) * rasterWidth / width));
		int row = Math.min(rasterHeight - 1, (int) ((y - top) * rasterHeight / height));
		return targets[row * rasterWidth + column];
	}

	private void prepare() {
		geometry();
		if (rasterDirty) {
			rasterize();
		}
	}

	private void geometry() {
		if (!geometryDirty) {
			return;
		}
		faces.clear();
		minX = minY = Double.POSITIVE_INFINITY;
		maxX = maxY = Double.NEGATIVE_INFINITY;
		double phase = time * (animation == Animation.RUN ? 10 : 5);
		double swing = switch (animation) {
			case NONE -> 0;
			case IDLE -> Math.sin(time * 1.4) * 0.035;
			case WALK -> Math.sin(phase) * 0.65;
			case RUN -> Math.sin(phase) * 1.1;
		};
		double splay = animation == Animation.NONE ? 0
			: animation == Animation.IDLE ? 0.04 + Math.cos(time * 1.2) * 0.025 : 0.04;
		int arm = slim ? 3 : 4;
		double headRX = 0;
		double headRZ = 0;
		double bodyRX = 0;
		double bodyOY = 0;
		double armRRX = 0;
		double armLRX = 0;
		double legRRX = 0;
		double legLRX = 0;
		double armRRZ = 0;
		double armLRZ = 0;
		double legRRZ = 0;
		double legLRZ = 0;
		double headOX = 0;
		double headOY = 0;
		double armROX = 0;
		double armROY = 0;
		double armLOX = 0;
		double armLOY = 0;
		double legROX = 0;
		double legROY = 0;
		double legLOX = 0;
		double legLOY = 0;
		switch (pose) {
			case HEAD_BACK -> headRX = 0.95;
			case ARMS_UP -> {
				armRRZ = 2.5;
				armLRZ = -2.5;
			}
			case T_POSE -> {
				armRRZ = 1.5;
				armLRZ = -1.5;
			}
			case STAR -> {
				armRRZ = 1.5;
				armLRZ = -1.5;
				legRRZ = 0.38;
				legLRZ = -0.38;
			}
			case EXPLODE -> {
				headRX = 0.4;
				headOY = -7;
				armRRZ = 0.35;
				armROX = -8;
				armROY = -3;
				armLRZ = -0.35;
				armLOX = 8;
				armLOY = -3;
				legRRZ = 0.15;
				legROX = -4;
				legROY = 5;
				legLRZ = -0.15;
				legLOX = 4;
				legLOY = 5;
			}
			case CROUCH -> {
				headRX = -0.1;
				bodyRX = 0.18;
				bodyOY = 2.5;
				headOY = 2.5;
				armROY = 2.5;
				armLOY = 2.5;
				legROY = 2.5;
				legLOY = 2.5;
				armRRX = -0.25;
				armLRX = 0.25;
				legRRX = 0.5;
				legLRX = -0.5;
			}
			case SIT -> {
				bodyRX = -0.1;
				armRRX = -0.2;
				armLRX = 0.2;
				legRRX = 1.35;
				legLRX = 1.35;
			}
			case WAVE -> {
				armRRZ = 2.5;
				headRZ = 0.12;
				armLRZ = -0.2;
			}
			default -> {
			}
		}
		cuboid(Part.HEAD, -4, 0, -4, 8, 8, 8, 0, 8, 0, headRX, headRZ, 0, 0, 32, 0, 0.5, headOX, headOY, 0);
		cuboid(Part.BODY, -4, 8, -2, 8, 12, 4, 0, 8, 0, bodyRX, 0, 16, 16, 16, 32, 0.25, 0, bodyOY, 0);
		cuboid(Part.RIGHT_ARM, -4 - arm, 8, -2, arm, 12, 4, -5, 10, 0, swing + armRRX, splay + armRRZ, 40, 16, 40, 32, 0.25, armROX, armROY, 0);
		cuboid(Part.LEFT_ARM, 4, 8, -2, arm, 12, 4, 5, 10, 0, -swing + armLRX, -splay + armLRZ, 32, 48, 48, 48, 0.25, armLOX, armLOY, 0);
		double legSwing = animation == Animation.IDLE ? 0 : swing;
		cuboid(Part.RIGHT_LEG, -4, 20, -2, 4, 12, 4, -2, 20, 0, -legSwing + legRRX, legRRZ, 0, 16, 0, 32, 0.25, legROX, legROY, 0);
		cuboid(Part.LEFT_LEG, 0, 20, -2, 4, 12, 4, 2, 20, 0, legSwing + legLRX, legLRZ, 16, 48, 0, 48, 0.25, legLOX, legLOY, 0);
		if (cape != null && (part == Part.ALL || part == Part.BODY)) {
			double wave = Math.sin(capeTime * 2.4) * 0.7;
			Point[] cloth = {project(-5, 8.5, -2.6), project(5, 8.5, -2.6),
				project(-5, 24.5, -4.4 + wave), project(5, 24.5, -4.4 + wave)};
			for (Point point : cloth) {
				minX = Math.min(minX, point.x);
				maxX = Math.max(maxX, point.x);
				minY = Math.min(minY, point.y);
				maxY = Math.max(maxY, point.y);
			}
			face(cloth, 0, 1, 2, 1, 1, 10, 16, false, false, 0.95, true);
			face(cloth, 0, 1, 2, 1, 1, 10, 16, false, true, 0.95, true);
		}
		centerX = (minX + maxX) * 0.5;
		centerY = (minY + maxY) * 0.5;
		unit = Math.min(width / Math.max(1, maxX - minX), height / Math.max(1, maxY - minY)) * 0.86 * zoom;
		geometryDirty = false;
		rasterDirty = true;
	}

	private void cuboid(Part selected, double x, double y, double z, int w, int h, int d,
		double pivotX, double pivotY, double pivotZ, double rotateX, double rotateZ,
		int u, int v, int outerU, int outerV, double inflation, double offX, double offY, double offZ) {
		if (part != Part.ALL && part != selected) {
			return;
		}
		boolean outer = shell == Shell.OUTER;
		if (outer) {
			u = outerU;
			v = outerV;
		} else {
			inflation = 0;
		}
		Point[] corners = new Point[8];
		for (int index = 0; index < 8; index++) {
			double px = x + offX + ((index & 1) == 0 ? -inflation : w + inflation) - pivotX;
			double py = y + offY + ((index & 2) == 0 ? -inflation : h + inflation) - pivotY;
			double pz = z + offZ + ((index & 4) == 0 ? -inflation : d + inflation) - pivotZ;
			double ry = py * Math.cos(rotateX) - pz * Math.sin(rotateX);
			double rz = py * Math.sin(rotateX) + pz * Math.cos(rotateX);
			double rx = px * Math.cos(rotateZ) - ry * Math.sin(rotateZ);
			ry = px * Math.sin(rotateZ) + ry * Math.cos(rotateZ);
			Point point = project(rx + pivotX, ry + pivotY, rz + pivotZ);
			corners[index] = point;
			minX = Math.min(minX, point.x);
			maxX = Math.max(maxX, point.x);
			minY = Math.min(minY, point.y);
			maxY = Math.max(maxY, point.y);
		}
		face(corners, 4, 5, 6, u + d, v + d, w, h, outer, false, 1, false);
		face(corners, 1, 0, 3, u + 2 * d + w, v + d, w, h, outer, false, 0.9, false);
		face(corners, 0, 4, 2, u, v + d, d, h, outer, false, 0.8, false);
		face(corners, 5, 1, 7, u + d + w, v + d, d, h, outer, false, 0.86, false);
		face(corners, 0, 1, 4, u + d, v, w, d, outer, false, 1, false);
		face(corners, 2, 3, 6, u + d + w, v, w, d, outer, true, 0.7, false);
	}

	private Point project(double x, double y, double z) {
		double rx = x * Math.cos(yaw) + z * Math.sin(yaw);
		double rz = z * Math.cos(yaw) - x * Math.sin(yaw);
		return new Point(rx, y * Math.cos(pitch) - rz * Math.sin(pitch),
			y * Math.sin(pitch) + rz * Math.cos(pitch));
	}

	private void face(Point[] corners, int origin, int across, int down, int u, int v,
		int columns, int rows, boolean outer, boolean reverse, double light, boolean cape) {
		Point a = corners[origin];
		Point b = corners[across];
		Point c = corners[down];
		Point horizontal = new Point(b.x - a.x, b.y - a.y, b.z - a.z);
		Point vertical = new Point(c.x - a.x, c.y - a.y, c.z - a.z);
		double determinant = horizontal.x * vertical.y - horizontal.y * vertical.x;
		if ((reverse ? -determinant : determinant) > 0.000001) {
			faces.add(new Face(a, horizontal, vertical, u, v, columns, rows, outer, determinant, light, cape));
		}
	}

	private void rasterize() {
		double scale = Math.min(1, Math.sqrt(MAX_PIXELS / ((double) width * height)));
		rasterWidth = Math.max(1, (int) (width * scale));
		rasterHeight = Math.max(1, (int) (height * scale));
		int size = rasterWidth * rasterHeight;
		if (colors.length != size) {
			colors = new int[size];
			targets = new int[size];
			depths = new double[size];
		}
		Arrays.fill(colors, BACKGROUND);
		Arrays.fill(targets, -1);
		Arrays.fill(depths, Double.NEGATIVE_INFINITY);
		for (int index = 0; index < faces.size(); index++) {
			rasterize(faces.get(index), index);
		}
		rasterDirty = false;
		uploadDirty = true;
	}

	private void rasterize(Face face, int faceIndex) {
		double x0 = face.origin.x;
		double y0 = face.origin.y;
		double x1 = x0 + face.across.x;
		double y1 = y0 + face.across.y;
		double x2 = x0 + face.down.x;
		double y2 = y0 + face.down.y;
		double x3 = x1 + face.down.x;
		double y3 = y1 + face.down.y;
		double sx = unit * rasterWidth / width;
		double sy = unit * rasterHeight / height;
		double ox = rasterWidth * (0.5 + panX) - centerX * sx;
		double oy = rasterHeight * (0.5 + panY) - centerY * sy;
		int startX = Math.clamp((int) Math.floor(Math.min(Math.min(x0, x1), Math.min(x2, x3)) * sx + ox), 0, rasterWidth);
		int endX = Math.clamp((int) Math.ceil(Math.max(Math.max(x0, x1), Math.max(x2, x3)) * sx + ox), 0, rasterWidth);
		int startY = Math.clamp((int) Math.floor(Math.min(Math.min(y0, y1), Math.min(y2, y3)) * sy + oy), 0, rasterHeight);
		int endY = Math.clamp((int) Math.ceil(Math.max(Math.max(y0, y1), Math.max(y2, y3)) * sy + oy), 0, rasterHeight);
		for (int y = startY; y < endY; y++) {
			double dy = (y + 0.5 - oy) / sy - y0;
			for (int x = startX; x < endX; x++) {
				double dx = (x + 0.5 - ox) / sx - x0;
				double u = (dx * face.down.y - dy * face.down.x) / face.determinant;
				double v = (face.across.x * dy - face.across.y * dx) / face.determinant;
				if (u < 0 || v < 0 || u >= 1 || v >= 1) {
					continue;
				}
				int index = y * rasterWidth + x;
				double depth = face.origin.z + u * face.across.z + v * face.down.z;
				if (depth <= depths[index]) {
					continue;
				}
				int pixelX = face.u + Math.min(face.columns - 1, (int) (u * face.columns));
				int pixelY = face.v + Math.min(face.rows - 1, (int) (v * face.rows));
				int pixel = pixelY * 64 + pixelX;
				int checker = ((pixelX + pixelY) & 1) == 0 ? 0xFF3F3F3F : 0xFF2B2B2B;
				colors[index] = shade(SkinPixels.over((face.cape ? cape : pixels)[pixel], checker), face.light);
				targets[index] = faceIndex << 12 | pixel;
				depths[index] = depth;
			}
		}
	}

	private static int shade(int color, double light) {
		int red = (int) (((color >>> 16) & 255) * light);
		int green = (int) (((color >>> 8) & 255) * light);
		int blue = (int) ((color & 255) * light);
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private void upload() {
		NativeImage image = canvas.getPixels();
		for (int y = 0; y < rasterHeight; y++) {
			for (int x = 0; x < rasterWidth; x++) {
				int index = y * rasterWidth + x;
				int color = colors[index];
				if (highlighted >= 0 && targets[index] == highlighted) {
					boolean edge = x == 0 || y == 0 || x == rasterWidth - 1 || y == rasterHeight - 1
						|| targets[index - 1] != highlighted || targets[index + 1] != highlighted
						|| targets[index - rasterWidth] != highlighted || targets[index + rasterWidth] != highlighted;
					color = edge ? 0xFFFFFFFF : SkinPixels.over(0x5551C8FF, color);
				}
				if (color >>> 24 == 0) {
					int bleed = x > 0 ? colors[index - 1]
						: x < rasterWidth - 1 ? colors[index + 1]
						: y > 0 ? colors[index - rasterWidth]
						: y < rasterHeight - 1 ? colors[index + rasterWidth] : 0;
					image.setPixel(x, y, bleed & 0x00FFFFFF);
				} else {
					image.setPixel(x, y, color);
				}
			}
		}
		canvas.upload();
		uploadDirty = false;
	}
}
