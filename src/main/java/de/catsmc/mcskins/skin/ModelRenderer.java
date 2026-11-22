package de.catsmc.mcskins.skin;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * A small software rasterizer for the player model. It renders at device resolution into an ARGB buffer and keeps
 * a per-pixel map back to the texture pixel under the cursor, so the editor can paint directly on the model.
 */
public final class ModelRenderer {
	public enum Animation { NONE, IDLE, WALK, RUN }
	public enum Pose { DEFAULT, WAVE, T_POSE, ARMS_UP, STAR, HEAD_BACK, CROUCH, SIT, EXPLODE }
	public enum Shell { BASE, OUTER }
	public enum Focus { ALL, HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

	/** Render size cap; the GUI stretches larger viewports. 1 M pixels keeps a frame cheap even on 4K screens. */
	static final int MAX_PIXELS = 1_000_000;
	private static final double[] LIGHT = normalize(0.35, -0.75, 0.65);
	private static final SkinGeometry.Box CAPE = new SkinGeometry.Box(null, false, -5, 8, -2, 10, 16, 1, 0, 0, 0, 0, 8);

	private final int[] skin = new int[4096];
	private int[] cape;
	private boolean slim;
	private Animation animation = Animation.NONE;
	private Pose pose = Pose.DEFAULT;
	private Shell target = Shell.BASE;
	private Focus focus = Focus.ALL;
	private boolean overlay = true;
	private boolean editing;
	private boolean grid;
	private boolean shadow = true;
	private double yaw = -0.45;
	private double pitch = -0.18;
	private double zoom = 1;
	private double panX;
	private double panY;
	private double time;
	private double capeTime;
	private int maxPixels = MAX_PIXELS;
	private boolean exporting;

	private int width = 1;
	private int height = 1;
	private int[] colors = new int[1];
	private int[] targets = new int[1];
	private float[] depths = new float[1];
	private float[] pickDepths = new float[1];
	/** Per texture pixel, the device rectangle that may show it: minX, minY, maxX, maxY (exclusive). */
	private final int[] texelBounds = new int[4096 * 4];
	private double scale;
	private double originX;
	private double originY;
	private double cosYaw;
	private double sinYaw;
	private double cosPitch;
	private double sinPitch;

	public void skin(int[] pixels, boolean slim) {
		if (pixels == null || pixels.length != 4096) {
			throw new IllegalArgumentException("Skin texture must contain 64 x 64 pixels");
		}
		System.arraycopy(pixels, 0, skin, 0, 4096);
		this.slim = slim;
	}

	public void cape(int[] pixels) {
		if (pixels != null && pixels.length != 2048) {
			throw new IllegalArgumentException("Cape texture must contain 64 x 32 pixels");
		}
		cape = pixels == null ? null : pixels.clone();
	}

	public boolean slim() {
		return slim;
	}

	public Animation animation() {
		return animation;
	}

	public void animation(Animation value) {
		animation = Objects.requireNonNull(value);
		time = 0;
	}

	public Pose pose() {
		return pose;
	}

	public void pose(Pose value) {
		pose = Objects.requireNonNull(value);
	}

	public Shell target() {
		return target;
	}

	/** Which shell receives paint; the other one is still drawn. */
	public void target(Shell value) {
		target = Objects.requireNonNull(value);
	}

	public Focus focus() {
		return focus;
	}

	public void focus(Focus value) {
		focus = Objects.requireNonNull(value);
		zoom = 1;
		panX = 0;
		panY = 0;
	}

	public boolean overlay() {
		return overlay;
	}

	public void overlay(boolean value) {
		overlay = value;
	}

	/** In editing mode the overlay is ghosted while painting the base, and empty overlay pixels are hinted. */
	public void editing(boolean value) {
		editing = value;
	}

	public boolean grid() {
		return grid;
	}

	public void grid(boolean value) {
		grid = value;
	}

	public void shadow(boolean value) {
		shadow = value;
	}

	public double yaw() {
		return yaw;
	}

	public void advance(double seconds) {
		if (seconds > 0 && Double.isFinite(seconds)) {
			double step = Math.min(0.1, seconds);
			capeTime += step;
			if (animation != Animation.NONE) {
				time += step;
			}
		}
	}

	public boolean animated() {
		return animation != Animation.NONE || cape != null;
	}

	public void reset() {
		yaw = -0.45;
		pitch = -0.18;
		zoom = 1;
		panX = 0;
		panY = 0;
	}

	/** 0 front, 1 back, 2 player's right, 3 player's left, 4 top, 5 bottom. */
	public void view(int preset) {
		if (preset < 0 || preset > 5) {
			throw new IllegalArgumentException("View preset must be between 0 and 5");
		}
		yaw = switch (preset) {
			case 1 -> Math.PI;
			case 2 -> Math.PI / 2;
			case 3 -> -Math.PI / 2;
			default -> 0;
		};
		pitch = preset == 4 ? -Math.PI / 2 : preset == 5 ? Math.PI / 2 : 0;
		zoom = 1;
		panX = 0;
		panY = 0;
	}

	public void orbit(double dx, double dy) {
		if (Double.isFinite(dx) && Double.isFinite(dy)) {
			yaw = Math.IEEEremainder(yaw + dx, Math.PI * 2);
			pitch = Math.clamp(pitch + dy, -Math.PI / 2, Math.PI / 2);
		}
	}

	/** Pans by a fraction of the viewport. */
	public void pan(double dx, double dy) {
		if (Double.isFinite(dx) && Double.isFinite(dy)) {
			panX = Math.clamp(panX + dx, -1, 1);
			panY = Math.clamp(panY + dy, -1, 1);
		}
	}

	public void zoom(double steps) {
		if (Double.isFinite(steps)) {
			zoom = Math.clamp(zoom * Math.exp(Math.clamp(steps, -10, 10) * 0.15), 0.4, 12);
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The texture pixel index under a device pixel, or -1. */
	public int target(int x, int y) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return -1;
		}
		return targets[y * width + x];
	}

	/** Device pixels per texture pixel for the head front, a hint for brush cursors and grids. */
	public double texelSize() {
		return scale;
	}

	/** A new renderer showing the same skin, cape, pose, animation frame and camera as this one. */
	public ModelRenderer copyView() {
		var copy = new ModelRenderer();
		System.arraycopy(skin, 0, copy.skin, 0, 4096);
		copy.cape = cape == null ? null : cape.clone();
		copy.slim = slim;
		copy.animation = animation;
		copy.pose = pose;
		copy.target = target;
		copy.focus = focus;
		copy.overlay = overlay;
		copy.yaw = yaw;
		copy.pitch = pitch;
		copy.zoom = zoom;
		copy.panX = panX;
		copy.panY = panY;
		copy.time = time;
		copy.capeTime = capeTime;
		return copy;
	}

	/**
	 * Renders a square image for export: transparent background, no shadow, grid or editing hints, the base shell
	 * drawn opaque like the game does, and 2x supersampling up to 1024 pixels for smooth edges. Returns ARGB pixels.
	 */
	public int[] renderImage(int size) {
		if (size < 16 || size > 4096) {
			throw new IllegalArgumentException("Render size must be between 16 and 4096 pixels.");
		}
		int factor = size <= 1024 ? 2 : 1;
		int full = size * factor;
		boolean wasEditing = editing;
		boolean hadGrid = grid;
		boolean hadShadow = shadow;
		int previousMax = maxPixels;
		try {
			editing = false;
			grid = false;
			shadow = false;
			exporting = true;
			maxPixels = full * full;
			render(full, full);
			if (factor == 1) {
				return colors.clone();
			}
			int[] result = new int[size * size];
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					long alpha = 0;
					long red = 0;
					long green = 0;
					long blue = 0;
					for (int dy = 0; dy < 2; dy++) {
						for (int dx = 0; dx < 2; dx++) {
							int color = colors[(y * 2 + dy) * full + x * 2 + dx];
							int a = color >>> 24;
							alpha += a;
							red += ((color >>> 16) & 255) * a;
							green += ((color >>> 8) & 255) * a;
							blue += (color & 255) * a;
						}
					}
					if (alpha > 0) {
						result[y * size + x] = (int) ((alpha + 2) / 4) << 24 | (int) (red / alpha) << 16
							| (int) (green / alpha) << 8 | (int) (blue / alpha);
					}
				}
			}
			return result;
		} finally {
			editing = wasEditing;
			grid = hadGrid;
			shadow = hadShadow;
			exporting = false;
			maxPixels = previousMax;
		}
	}

	public void render(int width, int height) {
		long pixels = (long) Math.max(1, width) * Math.max(1, height);
		double reduce = pixels > maxPixels ? Math.sqrt(maxPixels / (double) pixels) : 1;
		this.width = Math.max(1, (int) (width * reduce));
		this.height = Math.max(1, (int) (height * reduce));
		int size = this.width * this.height;
		if (colors.length != size) {
			colors = new int[size];
			targets = new int[size];
			depths = new float[size];
			pickDepths = new float[size];
		}
		Arrays.fill(colors, 0);
		Arrays.fill(targets, -1);
		Arrays.fill(depths, Float.NEGATIVE_INFINITY);
		Arrays.fill(pickDepths, Float.NEGATIVE_INFINITY);
		for (int texel = 0; texel < 4096; texel++) {
			int base = texel * 4;
			texelBounds[base] = Integer.MAX_VALUE;
			texelBounds[base + 1] = Integer.MAX_VALUE;
			texelBounds[base + 2] = Integer.MIN_VALUE;
			texelBounds[base + 3] = Integer.MIN_VALUE;
		}
		camera();
		Transform[] transforms = transforms();
		if (shadow && focus == Focus.ALL) {
			shadow(transforms);
		}
		SkinGeometry geometry = SkinGeometry.of(slim);
		for (int pass = 0; pass < 2; pass++) {
			boolean outer = pass == 1;
			for (SkinGeometry.Box box : geometry.boxes()) {
				if (box.outer() != outer || !focused(box.part())) {
					continue;
				}
				Transform transform = transforms[box.part().ordinal()];
				for (SkinGeometry.Side side : SkinGeometry.Side.values()) {
					face(box, SkinGeometry.face(box, side), transform, skin, false);
				}
			}
		}
		if (cape != null && (focus == Focus.ALL || focus == Focus.BODY)) {
			double wave = animation == Animation.RUN ? 0.9 : animation == Animation.WALK ? 0.5 : 0.12;
			double tilt = 0.12 + wave + Math.sin(capeTime * 2.2) * 0.05;
			Transform transform = new Transform(0, transforms[1].offY, 0, 0, 8, -2, -tilt, 0, Math.PI);
			for (SkinGeometry.Side side : SkinGeometry.Side.values()) {
				face(CAPE, SkinGeometry.face(CAPE, side), transform, cape, true);
			}
		}
	}

	/** Copies the image into {@code out} (ARGB, row-major), outlining the highlighted texture pixels. */
	public void compose(int[] out, BitSet highlight, int highlightColor) {
		int size = width * height;
		if (highlight == null || highlight.isEmpty()) {
			System.arraycopy(colors, 0, out, 0, size);
			return;
		}
		compose(out, highlight, highlightColor, 0, 0, width, height);
	}

	/**
	 * Like {@link #compose(int[], BitSet, int)} but only rewrites the device rectangle [x0, x1) x [y0, y1) of
	 * {@code out}; pass a rectangle from {@link #bounds(BitSet)} when only the highlight changed.
	 */
	public void compose(int[] out, BitSet highlight, int highlightColor, int x0, int y0, int x1, int y1) {
		int minX = Math.clamp(x0, 0, width);
		int maxX = Math.clamp(x1, 0, width);
		int minY = Math.clamp(y0, 0, height);
		int maxY = Math.clamp(y1, 0, height);
		boolean empty = highlight == null || highlight.isEmpty();
		for (int y = minY; y < maxY; y++) {
			int row = y * width;
			if (empty) {
				System.arraycopy(colors, row + minX, out, row + minX, maxX - minX);
				continue;
			}
			for (int x = minX; x < maxX; x++) {
				int index = row + x;
				int pixel = targets[index];
				int color = colors[index];
				if (pixel >= 0 && highlight.get(pixel)) {
					boolean edge = x == 0 || y == 0 || x == width - 1 || y == height - 1
						|| !lit(highlight, targets[index - 1]) || !lit(highlight, targets[index + 1])
						|| !lit(highlight, targets[index - width]) || !lit(highlight, targets[index + width]);
					color = edge ? 0xFFFFFFFF : SkinDocument.over(highlightColor, color);
				}
				out[index] = color;
			}
		}
	}

	/**
	 * The device rectangle {minX, minY, maxX, maxY} (max exclusive) that can show any of the given texture pixels in
	 * the last render, or null if none of them is on screen.
	 */
	public int[] bounds(BitSet pixels) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		if (pixels != null) {
			for (int texel = pixels.nextSetBit(0); texel >= 0 && texel < 4096; texel = pixels.nextSetBit(texel + 1)) {
				int base = texel * 4;
				if (texelBounds[base + 2] < texelBounds[base]) {
					continue;
				}
				minX = Math.min(minX, texelBounds[base]);
				minY = Math.min(minY, texelBounds[base + 1]);
				maxX = Math.max(maxX, texelBounds[base + 2]);
				maxY = Math.max(maxY, texelBounds[base + 3]);
			}
		}
		return maxX < minX ? null : new int[]{minX, minY, maxX, maxY};
	}

	private static boolean lit(BitSet highlight, int pixel) {
		return pixel >= 0 && highlight.get(pixel);
	}

	private boolean focused(SkinGeometry.Part part) {
		return focus == Focus.ALL || focus.ordinal() - 1 == part.ordinal();
	}

	private void camera() {
		cosYaw = Math.cos(yaw);
		sinYaw = Math.sin(yaw);
		cosPitch = Math.cos(pitch);
		sinPitch = Math.sin(pitch);
		double centerX;
		double centerY;
		double centerZ;
		double extent;
		if (focus == Focus.ALL) {
			centerX = 0;
			centerY = 16;
			centerZ = 0;
			extent = 40;
		} else {
			SkinGeometry.Box box = SkinGeometry.of(slim).boxes().get(focus.ordinal() - 1);
			centerX = box.x() + box.w() / 2.0;
			centerY = box.y() + box.h() / 2.0;
			centerZ = box.z() + box.d() / 2.0;
			extent = Math.sqrt(box.w() * box.w() + box.h() * box.h() + box.d() * box.d()) * 1.25;
		}
		scale = Math.min(width, height) / extent * zoom;
		double[] center = view(centerX, centerY, centerZ);
		originX = width * (0.5 + panX) - center[0] * scale;
		originY = height * (0.5 + panY) - center[1] * scale;
	}

	private double[] view(double x, double y, double z) {
		double rx = x * cosYaw + z * sinYaw;
		double rz = z * cosYaw - x * sinYaw;
		return new double[]{rx, y * cosPitch - rz * sinPitch, y * sinPitch + rz * cosPitch};
	}

	private record Transform(double offX, double offY, double offZ, double pivotX, double pivotY, double pivotZ,
		double rotateX, double rotateZ, double rotateY) {
	}

	private Transform[] transforms() {
		double phase = time * (animation == Animation.RUN ? 10 : 6);
		double swing = switch (animation) {
			case NONE, IDLE -> 0;
			case WALK -> Math.sin(phase) * 0.7;
			case RUN -> Math.sin(phase) * 1.15;
		};
		double breathe = animation == Animation.NONE ? 0 : Math.sin(time * 1.6) * 0.05;
		double bob = animation == Animation.RUN ? Math.abs(Math.sin(phase)) * -1.2
			: animation == Animation.WALK ? Math.abs(Math.sin(phase)) * -0.5 : 0;
		double[] rx = new double[6];
		double[] rz = new double[6];
		double[] ox = new double[6];
		double[] oy = new double[6];
		double[] oz = new double[6];
		rz[2] = 0.05 + breathe;
		rz[3] = -0.05 - breathe;
		rx[2] = swing;
		rx[3] = -swing;
		rx[4] = -swing;
		rx[5] = swing;
		switch (pose) {
			case HEAD_BACK -> rx[0] = 0.7;
			case ARMS_UP -> {
				rz[2] = 2.6;
				rz[3] = -2.6;
			}
			case T_POSE -> {
				rz[2] = Math.PI / 2;
				rz[3] = -Math.PI / 2;
			}
			case STAR -> {
				rz[2] = 2.2;
				rz[3] = -2.2;
				rz[4] = 0.35;
				rz[5] = -0.35;
			}
			case EXPLODE -> {
				oy[0] = -6;
				ox[2] = -6;
				ox[3] = 6;
				ox[4] = -3;
				ox[5] = 3;
				oy[4] = 4;
				oy[5] = 4;
			}
			case CROUCH -> {
				rx[1] = -0.5;
				rx[2] -= 0.4;
				rx[3] -= 0.4;
				oz[4] = -4;
				oz[5] = -4;
				for (int part = 0; part < 4; part++) {
					oy[part] = 3.2;
				}
			}
			case SIT -> {
				rx[2] = 0.6;
				rx[3] = 0.6;
				rx[4] = 1.4;
				rx[5] = 1.4;
				rz[4] = 0.1;
				rz[5] = -0.1;
			}
			case WAVE -> {
				rz[2] = 2.7 + Math.sin(time * 9 + capeTime * 9) * 0.25;
				rx[0] = -0.08;
				rz[0] = 0.1;
			}
			default -> {
			}
		}
		var result = new Transform[6];
		for (int part = 0; part < 6; part++) {
			result[part] = new Transform(ox[part], oy[part] + bob, oz[part], Double.NaN, Double.NaN, 0, rx[part], rz[part], 0);
		}
		return result;
	}

	private double[] place(SkinGeometry.Box box, Transform transform, double x, double y, double z) {
		double pivotX = Double.isNaN(transform.pivotX) ? box.pivotX() : transform.pivotX;
		double pivotY = Double.isNaN(transform.pivotY) ? box.pivotY() : transform.pivotY;
		double pivotZ = transform.pivotZ;
		double px = x - pivotX;
		double py = y - pivotY;
		double pz = z - pivotZ;
		if (transform.rotateY != 0) {
			double cos = Math.cos(transform.rotateY);
			double sin = Math.sin(transform.rotateY);
			double nx = px * cos + pz * sin;
			pz = pz * cos - px * sin;
			px = nx;
		}
		double cosX = Math.cos(transform.rotateX);
		double sinX = Math.sin(transform.rotateX);
		double ry = py * cosX - pz * sinX;
		double rz = py * sinX + pz * cosX;
		double cosZ = Math.cos(transform.rotateZ);
		double sinZ = Math.sin(transform.rotateZ);
		double rx = px * cosZ - ry * sinZ;
		ry = px * sinZ + ry * cosZ;
		return view(rx + pivotX + transform.offX, ry + pivotY + transform.offY, rz + pivotZ + transform.offZ);
	}

	private void face(SkinGeometry.Box box, SkinGeometry.Face face, Transform transform, int[] texture, boolean isCape) {
		int[] corners = SkinGeometry.corners(face.side());
		double inflation = box.inflation();
		double[][] points = new double[3][];
		for (int index = 0; index < 3; index++) {
			int corner = corners[index];
			double x = box.x() + ((corner & 1) == 0 ? -inflation : box.w() + inflation);
			double y = box.y() + ((corner & 2) == 0 ? -inflation : box.h() + inflation);
			double z = box.z() + ((corner & 4) == 0 ? -inflation : box.d() + inflation);
			points[index] = place(box, transform, x, y, z);
		}
		double[] origin = points[0];
		double ax = points[1][0] - origin[0];
		double ay = points[1][1] - origin[1];
		double az = points[1][2] - origin[2];
		double dx = points[2][0] - origin[0];
		double dy = points[2][1] - origin[1];
		double dz = points[2][2] - origin[2];
		boolean reversed = SkinGeometry.reversed(face.side());
		double determinant = ax * dy - ay * dx;
		if ((reversed ? -determinant : determinant) <= 1e-9) {
			return;
		}
		double[] normal = normalize(ay * dz - az * dy, az * dx - ax * dz, determinant);
		if (reversed) {
			normal[0] = -normal[0];
			normal[1] = -normal[1];
			normal[2] = -normal[2];
		}
		double diffuse = Math.max(0, normal[0] * LIGHT[0] + normal[1] * LIGHT[1] + normal[2] * LIGHT[2]);
		double light = Math.min(1, 0.52 + 0.42 * diffuse + 0.12 * Math.max(0, normal[2]));
		boolean outer = box.outer();
		boolean drawn = !outer || overlay;
		boolean pickable = !isCape && (outer ? target == Shell.OUTER : target == Shell.BASE);
		if (!drawn && !pickable) {
			return;
		}
		double ghost = outer && editing && target == Shell.BASE ? 0.55 : 1;
		boolean hintEmpty = outer && editing && target == Shell.OUTER;
		double sx = scale;
		double ox = originX;
		double oy = originY;
		double x0 = origin[0] * sx + ox;
		double y0 = origin[1] * sx + oy;
		double sax = ax * sx;
		double say = ay * sx;
		double sdx = dx * sx;
		double sdy = dy * sx;
		double det = sax * sdy - say * sdx;
		int minX = (int) Math.floor(Math.min(Math.min(x0, x0 + sax), Math.min(x0 + sdx, x0 + sax + sdx)));
		int maxX = (int) Math.ceil(Math.max(Math.max(x0, x0 + sax), Math.max(x0 + sdx, x0 + sax + sdx)));
		int minY = (int) Math.floor(Math.min(Math.min(y0, y0 + say), Math.min(y0 + sdy, y0 + say + sdy)));
		int maxY = (int) Math.ceil(Math.max(Math.max(y0, y0 + say), Math.max(y0 + sdy, y0 + say + sdy)));
		minX = Math.clamp(minX, 0, width);
		maxX = Math.clamp(maxX, 0, width);
		minY = Math.clamp(minY, 0, height);
		maxY = Math.clamp(maxY, 0, height);
		int columns = face.columns();
		int rows = face.rows();
		double texelX = Math.hypot(sax, say) / columns;
		double texelY = Math.hypot(sdx, sdy) / rows;
		boolean lines = grid && Math.min(texelX, texelY) >= 6;
		double lineU = lines ? 1 / texelX : 0;
		double lineV = lines ? 1 / texelY : 0;
		int textureWidth = 64;
		for (int y = minY; y < maxY; y++) {
			double py = y + 0.5 - y0;
			int row = y * width;
			for (int x = minX; x < maxX; x++) {
				double px = x + 0.5 - x0;
				double s = (px * sdy - py * sdx) / det;
				double t = (sax * py - say * px) / det;
				if (s < 0 || t < 0 || s >= 1 || t >= 1) {
					continue;
				}
				int index = row + x;
				float depth = (float) (origin[2] + s * az + t * dz);
				double fu = s * columns;
				double fv = t * rows;
				int column = Math.min(columns - 1, (int) fu);
				int line = Math.min(rows - 1, (int) fv);
				int pixel = (face.v() + line) * textureWidth + face.u() + column;
				if (pickable && depth > pickDepths[index]) {
					pickDepths[index] = depth;
					targets[index] = pixel;
					int base = pixel * 4;
					if (x < texelBounds[base]) {
						texelBounds[base] = x;
					}
					if (y < texelBounds[base + 1]) {
						texelBounds[base + 1] = y;
					}
					if (x + 1 > texelBounds[base + 2]) {
						texelBounds[base + 2] = x + 1;
					}
					if (y + 1 > texelBounds[base + 3]) {
						texelBounds[base + 3] = y + 1;
					}
				}
				if (!drawn || depth <= depths[index]) {
					continue;
				}
				int color = texture[pixel];
				int alpha = color >>> 24;
				if (outer) {
					if (alpha == 0) {
						if (hintEmpty && depth > depths[index]) {
							colors[index] = SkinDocument.over(0x1CFFFFFF, colors[index] == 0 ? 0xFF202020 : colors[index]);
						}
						continue;
					}
					if (ghost < 1) {
						color = (int) (alpha * ghost) << 24 | color & 0xFFFFFF;
					}
					color = shade(color, light);
					colors[index] = SkinDocument.over(color, colors[index]);
					if (alpha == 255 && ghost == 1) {
						depths[index] = depth;
					}
				} else {
					if (alpha < 255 && exporting && !isCape) {
						// The game draws the base shell opaque; transparent pixels come out black.
						color = alpha == 0 ? 0xFF000000 : color | 0xFF000000;
					} else if (alpha < 255) {
						int checker = (((face.u() + column) + (face.v() + line)) & 1) == 0 ? 0xFF4A4A4A : 0xFF353535;
						color = isCape && alpha == 0 ? 0xFF2A2A2A : SkinDocument.over(color, checker);
					}
					colors[index] = shade(color, light);
					depths[index] = depth;
				}
				if (lines && (fu - Math.floor(fu) < lineU || fv - Math.floor(fv) < lineV)) {
					colors[index] = SkinDocument.over(0x38000000, colors[index]);
				}
			}
		}
	}

	private void shadow(Transform[] transforms) {
		double[] center = view(0, 32 + transforms[4].offY, 0);
		double cx = center[0] * scale + originX;
		double cy = center[1] * scale + originY;
		double radiusX = 9 * scale;
		double radiusY = Math.max(1, 9 * scale * Math.abs(sinPitch));
		if (sinPitch > 0) {
			return;
		}
		int minX = Math.clamp((int) (cx - radiusX), 0, width);
		int maxX = Math.clamp((int) Math.ceil(cx + radiusX), 0, width);
		int minY = Math.clamp((int) (cy - radiusY), 0, height);
		int maxY = Math.clamp((int) Math.ceil(cy + radiusY), 0, height);
		for (int y = minY; y < maxY; y++) {
			double ny = (y + 0.5 - cy) / radiusY;
			for (int x = minX; x < maxX; x++) {
				double nx = (x + 0.5 - cx) / radiusX;
				double distance = nx * nx + ny * ny;
				if (distance < 1) {
					int alpha = (int) (90 * (1 - distance) * (1 - distance));
					colors[y * width + x] = alpha << 24;
				}
			}
		}
	}

	private static int shade(int color, double light) {
		int red = (int) (((color >>> 16) & 255) * light);
		int green = (int) (((color >>> 8) & 255) * light);
		int blue = (int) ((color & 255) * light);
		return color & 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private static double[] normalize(double x, double y, double z) {
		double length = Math.sqrt(x * x + y * y + z * z);
		return length == 0 ? new double[]{0, 0, 1} : new double[]{x / length, y / length, z / length};
	}
}
