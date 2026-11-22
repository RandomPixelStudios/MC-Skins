package de.catsmc.mcskins.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import net.minecraft.client.input.KeyEvent;
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
	public static final int KEY_ENTER = read("KEY_RETURN", 257);
	public static final int KEY_KP_ENTER = read("KEY_NUMPADENTER", 335);
	public static final int KEY_L = read("KEY_L", 76);
	public static final int KEY_U = read("KEY_U", 85);
	public static final int KEY_D = read("KEY_D", 68);
	public static final int KEY_S = read("KEY_S", 83);
	public static final int KEY_N = read("KEY_N", 78);
	public static final int KEY_Z = read("KEY_Z", 90);
	public static final int KEY_Y = read("KEY_Y", 89);
	public static final int KEY_M = read("KEY_M", 77);
	public static final int KEY_G = read("KEY_G", 71);
	public static final int KEY_O = read("KEY_O", 79);
	public static final int KEY_X = read("KEY_X", 88);
	public static final int KEY_TAB = read("KEY_TAB", 258);
	public static final int KEY_Q = read("KEY_Q", 81);
	public static final int KEY_W = read("KEY_W", 87);
	public static final int KEY_J = read("KEY_J", 74);
	public static final int KEY_H = read("KEY_H", 72);
	public static final int KEY_K = read("KEY_K", 75);
	public static final int KEY_A = read("KEY_A", 65);
	public static final int KEY_C = read("KEY_C", 67);
	public static final int KEY_V = read("KEY_V", 86);
	public static final int KEY_DELETE = read("KEY_DELETE", 261);
	public static final int KEY_BACKSPACE = read("KEY_BACKSPACE", 259);
	public static final int KEY_LEFT_BRACKET = read("KEY_LBRACKET", 91);
	public static final int KEY_RIGHT_BRACKET = read("KEY_RBRACKET", 93);

	private static boolean shortcutResolved;
	private static Method shortcutKey;

	private InputCompat() {
	}

	/**
	 * The key to compare against letter shortcuts, following the keyboard layout: on QWERTZ the key labelled Z gives
	 * {@link #KEY_Z} although the raw key code is the physical US position (Y). Uses Minecraft's own layout-aware
	 * {@code KeyEvent.shortcutKey()} (26.3); versions without it fall back to the raw key.
	 */
	public static int shortcutKey(KeyEvent event) {
		resolveShortcut();
		if (shortcutKey != null) {
			try {
				return (int) shortcutKey.invoke(event);
			} catch (ReflectiveOperationException | RuntimeException exception) {
				LOGGER.debug("MC-Skins: layout-aware shortcut lookup failed: {}", String.valueOf(exception));
				shortcutKey = null;
			}
		}
		return event.key();
	}

	/** The name Minecraft shows for a key (layout-aware), for tooltips. */
	public static String keyName(int key) {
		try {
			String name = InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
			if (!name.isBlank()) {
				return name.toUpperCase(Locale.ROOT);
			}
		} catch (RuntimeException | LinkageError exception) {
			LOGGER.debug("MC-Skins: key name unavailable: {}", String.valueOf(exception));
		}
		return key >= 'A' && key <= 'Z' ? String.valueOf((char) key) : "";
	}

	private static void resolveShortcut() {
		if (shortcutResolved) {
			return;
		}
		shortcutResolved = true;
		try {
			shortcutKey = KeyEvent.class.getMethod("shortcutKey");
		} catch (NoSuchMethodException exception) {
			LOGGER.debug("MC-Skins: no layout-aware shortcut key in this version; shortcuts use physical keys.");
		}
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
