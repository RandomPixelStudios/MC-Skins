package de.catsmc.mcskins.skin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player model's boxes and their texture regions in model space (x right of the viewer when facing the
 * player, y down, z toward the viewer), shared by the 3D preview and texture tools such as mirroring.
 */
public final class SkinGeometry {
	public enum Part { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

	/** RIGHT is the player's right side (negative x), LEFT the player's left side. */
	public enum Side { FRONT, BACK, RIGHT, LEFT, TOP, BOTTOM }

	public record Box(Part part, boolean outer, double x, double y, double z, int w, int h, int d,
		int u, int v, double inflation, double pivotX, double pivotY) {
	}

	public record Face(Box box, Side side, int u, int v, int columns, int rows) {
		public boolean contains(int x, int y) {
			return x >= u && y >= v && x < u + columns && y < v + rows;
		}
	}

	private static final SkinGeometry CLASSIC = new SkinGeometry(false);
	private static final SkinGeometry SLIM = new SkinGeometry(true);

	private final List<Box> boxes;
	private final List<Face> faces;
	private final Face[] faceAt = new Face[4096];
	private final int[] mirror = new int[4096];

	private SkinGeometry(boolean slim) {
		int arm = slim ? 3 : 4;
		var list = new ArrayList<Box>();
		for (boolean outer : new boolean[]{false, true}) {
			list.add(new Box(Part.HEAD, outer, -4, 0, -4, 8, 8, 8, outer ? 32 : 0, 0, outer ? 0.5 : 0, 0, 8));
			list.add(new Box(Part.BODY, outer, -4, 8, -2, 8, 12, 4, 16, outer ? 32 : 16, outer ? 0.25 : 0, 0, 8));
			list.add(new Box(Part.RIGHT_ARM, outer, -4 - arm, 8, -2, arm, 12, 4, 40, outer ? 32 : 16, outer ? 0.25 : 0, -5, 10));
			list.add(new Box(Part.LEFT_ARM, outer, 4, 8, -2, arm, 12, 4, outer ? 48 : 32, 48, outer ? 0.25 : 0, 5, 10));
			list.add(new Box(Part.RIGHT_LEG, outer, -4, 20, -2, 4, 12, 4, 0, outer ? 32 : 16, outer ? 0.25 : 0, -2, 20));
			list.add(new Box(Part.LEFT_LEG, outer, 0, 20, -2, 4, 12, 4, outer ? 0 : 16, 48, outer ? 0.25 : 0, 2, 20));
		}
		boxes = List.copyOf(list);
		var faceList = new ArrayList<Face>();
		for (Box box : boxes) {
			for (Side side : Side.values()) {
				Face face = face(box, side);
				faceList.add(face);
				for (int row = 0; row < face.rows; row++) {
					for (int column = 0; column < face.columns; column++) {
						faceAt[(face.v + row) * 64 + face.u + column] = face;
					}
				}
			}
		}
		faces = List.copyOf(faceList);
		buildMirror();
	}

	public static SkinGeometry of(boolean slim) {
		return slim ? SLIM : CLASSIC;
	}

	public List<Box> boxes() {
		return boxes;
	}

	public List<Face> faces() {
		return faces;
	}

	/** The face that owns a texture pixel, or null for unused texture space. */
	public Face faceAt(int x, int y) {
		return x < 0 || y < 0 || x >= 64 || y >= 64 ? null : faceAt[y * 64 + x];
	}

	/** The pixel index on the opposite side of the model, or -1 when the pixel is not on the model. */
	public int mirror(int index) {
		return mirror[index];
	}

	public static Face face(Box box, Side side) {
		int u = box.u;
		int v = box.v;
		int w = box.w;
		int h = box.h;
		int d = box.d;
		return switch (side) {
			case FRONT -> new Face(box, side, u + d, v + d, w, h);
			case BACK -> new Face(box, side, u + 2 * d + w, v + d, w, h);
			case RIGHT -> new Face(box, side, u, v + d, d, h);
			case LEFT -> new Face(box, side, u + d + w, v + d, d, h);
			case TOP -> new Face(box, side, u + d, v, w, d);
			case BOTTOM -> new Face(box, side, u + d + w, v, w, d);
		};
	}

	/**
	 * Corner indices (bit 0 = max x, bit 1 = max y, bit 2 = max z) of a face's texture origin, the corner
	 * reached by moving along the texture's columns, and the corner reached along its rows.
	 */
	public static int[] corners(Side side) {
		return switch (side) {
			case FRONT -> new int[]{4, 5, 6};
			case BACK -> new int[]{1, 0, 3};
			case RIGHT -> new int[]{0, 4, 2};
			case LEFT -> new int[]{5, 1, 7};
			case TOP -> new int[]{0, 1, 4};
			case BOTTOM -> new int[]{2, 3, 6};
		};
	}

	/** True when across x down points into the box, so the outward normal is its negation. */
	public static boolean reversed(Side side) {
		return side == Side.BOTTOM;
	}

	private void buildMirror() {
		Arrays.fill(mirror, -1);
		Map<String, Integer> positions = new HashMap<>();
		for (Face face : faces) {
			for (int row = 0; row < face.rows; row++) {
				for (int column = 0; column < face.columns; column++) {
					double[] point = center(face, column, row);
					positions.put(key(face.box.outer, face.side, point[0], point[1], point[2]), (face.v + row) * 64 + face.u + column);
				}
			}
		}
		for (Face face : faces) {
			Side opposite = face.side == Side.RIGHT ? Side.LEFT : face.side == Side.LEFT ? Side.RIGHT : face.side;
			for (int row = 0; row < face.rows; row++) {
				for (int column = 0; column < face.columns; column++) {
					double[] point = center(face, column, row);
					Integer target = positions.get(key(face.box.outer, opposite, -point[0], point[1], point[2]));
					if (target != null) {
						mirror[(face.v + row) * 64 + face.u + column] = target;
					}
				}
			}
		}
	}

	private static String key(boolean outer, Side side, double x, double y, double z) {
		return (outer ? "o" : "b") + side.ordinal() + ":" + Math.round(x * 2) + ":" + Math.round(y * 2) + ":" + Math.round(z * 2);
	}

	/** The rest-pose center of a texel on a face. */
	static double[] center(Face face, int column, int row) {
		Box box = face.box;
		double c = column + 0.5;
		double r = row + 0.5;
		return switch (face.side) {
			case FRONT -> new double[]{box.x + c, box.y + r, box.z + box.d};
			case BACK -> new double[]{box.x + box.w - c, box.y + r, box.z};
			case RIGHT -> new double[]{box.x, box.y + r, box.z + c};
			case LEFT -> new double[]{box.x + box.w, box.y + r, box.z + box.d - c};
			case TOP -> new double[]{box.x + c, box.y, box.z + r};
			case BOTTOM -> new double[]{box.x + c, box.y + box.h, box.z + r};
		};
	}
}
