package de.catsmc.mcskins.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;

public final class InputCompat {
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final int MOUSE_BUTTON_LEFT = read("MOUSE_BUTTON_LEFT", 0);
	public static final int MOUSE_BUTTON_RIGHT = read("MOUSE_BUTTON_RIGHT", 1);
	public static final int MOUSE_BUTTON_MIDDLE = read("MOUSE_BUTTON_MIDDLE", 2);

	public static final int KEY_1 = read("KEY_1", 49);
	public static final int KEY_8 = read("KEY_8", 56);
	public static final int KEY_ESCAPE = read("KEY_ESCAPE", 256);
	public static final int KEY_EQUALS = read("KEY_EQUALS", 61);
	public static final int KEY_ADD = read("KEY_ADD", 334);
	public static final int KEY_MINUS = read("KEY_MINUS", 45);
	public static final int KEY_B = read("KEY_B", 66);
	public static final int KEY_E = read("KEY_E", 69);
	public static final int KEY_F = read("KEY_F", 70);
	public static final int KEY_I = read("KEY_I", 73);
	public static final int KEY_R = read("KEY_R", 82);

	private InputCompat() {
	}

	private static int read(String name, int fallback) {
		try {
			Field field = InputConstants.class.getField(name);
			if (!Modifier.isStatic(field.getModifiers())) {
				throw new NoSuchFieldException(name);
			}
			return field.getInt(null);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			LOGGER.error("MC-Skins: InputConstants.{} no longer exists, assuming {}.", name, fallback);
			return fallback;
		}
	}
}
