package de.catsmc.mcskins.skin;

/**
 * A named group of layers. Hiding a group hides its layers, and its opacity scales theirs. {@code collapsed} only
 * affects how the editor lists the group.
 */
public record LayerGroup(int id, String name, boolean visible, int opacity, boolean collapsed) {
	public static final int MAX_GROUPS = 32;

	public LayerGroup {
		if (id <= 0) {
			throw new IllegalArgumentException("Group ids must be positive.");
		}
		name = SkinLayer.validateName(name);
		if (opacity < 0 || opacity > 255) {
			throw new IllegalArgumentException("Group opacity must be between 0 and 255.");
		}
	}

	LayerGroup withName(String value) {
		return new LayerGroup(id, value, visible, opacity, collapsed);
	}

	LayerGroup withVisible(boolean value) {
		return new LayerGroup(id, name, value, opacity, collapsed);
	}

	LayerGroup withOpacity(int value) {
		return new LayerGroup(id, name, visible, value, collapsed);
	}

	LayerGroup withCollapsed(boolean value) {
		return new LayerGroup(id, name, visible, opacity, value);
	}
}
