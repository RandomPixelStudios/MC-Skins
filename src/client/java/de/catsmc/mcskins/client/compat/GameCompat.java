package de.catsmc.mcskins.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.slf4j.Logger;

public final class GameCompat {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String RENDERPEARL_FILTER = "com.mojang.renderpearl.api.textures.FilterMode";
	private static final String BLAZE3D_FILTER = "com.mojang.blaze3d.textures.FilterMode";

	private static boolean screenResolved;
	private static Method setScreen;
	private static Method screenGetter;
	private static Field screenField;

	private static boolean hudResolved;
	private static Field hudField;
	private static Method hudHidden;
	private static Method hudToggle;
	private static Field hideGui;

	private static boolean blitResolved;
	private static boolean blitBroken;
	private static Class<?> filterMode;
	private static Object linearFilter;
	private static Method clampToEdge;
	private static Method textureView;
	private static Method blit;

	private static boolean swingResolved;
	private static boolean swingBroken;
	private static Constructor<?> swing;
	private static Field currentSwing;
	private static Field swingAnimation;

	private GameCompat() {
	}

	public static void setScreen(Minecraft minecraft, Screen screen) {
		resolveScreen();
		if (setScreen == null) {
			throw new IllegalStateException("MC-Skins: this Minecraft version has no setScreen method.");
		}
		Object receiver = setScreen.getDeclaringClass().isInstance(minecraft.gui) ? minecraft.gui : minecraft;
		invoke(setScreen, receiver, screen);
	}

	public static Screen currentScreen(Minecraft minecraft) {
		resolveScreen();
		if (screenGetter != null) {
			return (Screen) invoke(screenGetter, minecraft.gui);
		}
		if (screenField != null) {
			try {
				return (Screen) screenField.get(minecraft);
			} catch (IllegalAccessException exception) {
				throw new IllegalStateException("MC-Skins cannot read the open screen.", exception);
			}
		}
		return null;
	}

	public static boolean hudHidden(Minecraft minecraft) {
		resolveHud();
		if (hudHidden != null) {
			return (boolean) invoke(hudHidden, hud(minecraft));
		}
		if (hideGui != null) {
			try {
				return hideGui.getBoolean(minecraft.options);
			} catch (IllegalAccessException exception) {
				throw new IllegalStateException("MC-Skins cannot read the HUD state.", exception);
			}
		}
		return false;
	}

	public static void setHudHidden(Minecraft minecraft, boolean hidden) {
		resolveHud();
		if (hudHidden != null && hudToggle != null) {
			Object hud = hud(minecraft);
			if ((boolean) invoke(hudHidden, hud) != hidden) {
				invoke(hudToggle, hud);
			}
			return;
		}
		if (hideGui != null) {
			try {
				hideGui.setBoolean(minecraft.options, hidden);
			} catch (IllegalAccessException exception) {
				throw new IllegalStateException("MC-Skins cannot set the HUD state.", exception);
			}
			return;
		}
		throw new IllegalStateException("MC-Skins: this Minecraft version has no HUD toggle.");
	}

	public static void blitPreview(GuiGraphicsExtractor graphics, DynamicTexture texture, int left, int top,
		int width, int height) {
		if (blitBroken) {
			return;
		}
		resolveBlit();
		if (blitBroken) {
			return;
		}
		try {
			Object view = textureView.invoke(texture);
			Object sampler = clampToEdge.invoke(RenderSystem.getSamplerCache(), linearFilter);
			if (blit == null) {
				blit = findBlit(view, sampler);
				if (blit == null) {
					breakBlit("no matching blit overload");
					return;
				}
			}
			blit.invoke(graphics, view, sampler, left, top, left + width, top + height, 0F, 1F, 0F, 1F);
		} catch (InvocationTargetException exception) {
			breakBlit(String.valueOf(exception.getCause()));
		} catch (ReflectiveOperationException | RuntimeException exception) {
			breakBlit(String.valueOf(exception));
		}
	}

	public static void applySwing(AvatarRenderState state, float animation) {
		resolveSwing();
		if (swingBroken) {
			return;
		}
		try {
			currentSwing.set(state, swingInstance());
			swingAnimation.setFloat(state, animation);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			swingBroken = true;
			LOGGER.warn("MC-Skins: the swing animation stopped working: {}", String.valueOf(exception));
		}
	}

	private static void resolveScreen() {
		if (screenResolved) {
			return;
		}
		screenResolved = true;
		setScreen = publicMethod(Gui.class, "setScreen", Screen.class);
		if (setScreen == null) {
			setScreen = publicMethod(Minecraft.class, "setScreen", Screen.class);
		}
		screenGetter = publicMethod(Gui.class, "screen");
		if (screenGetter == null) {
			screenField = publicField(Minecraft.class, "screen");
		}
	}

	private static void resolveHud() {
		if (hudResolved) {
			return;
		}
		hudResolved = true;
		hudField = publicField(Gui.class, "hud");
		if (hudField != null) {
			hudHidden = publicMethod(hudField.getType(), "isHidden");
			hudToggle = publicMethod(hudField.getType(), "toggle");
		}
		hideGui = publicField(Options.class, "hideGui");
		if (hideGui == null) {
			hideGui = anyField(Options.class, "hideGui");
		}
	}

	private static void resolveBlit() {
		if (blitResolved) {
			return;
		}
		blitResolved = true;
		textureView = publicMethod(DynamicTexture.class, "getTextureView");
		filterMode = findClass(RENDERPEARL_FILTER, BLAZE3D_FILTER);
		try {
			Class<?> cache = RenderSystem.class.getMethod("getSamplerCache").getReturnType();
			if (filterMode != null) {
				clampToEdge = publicMethod(cache, "getClampToEdge", filterMode);
			}
			if (clampToEdge == null) {
				for (Method candidate : cache.getMethods()) {
					if (candidate.getName().equals("getClampToEdge") && candidate.getParameterCount() == 1) {
						clampToEdge = candidate;
						filterMode = candidate.getParameterTypes()[0];
						break;
					}
				}
			}
		} catch (NoSuchMethodException | RuntimeException exception) {
			breakBlit("no sampler cache: " + exception);
			return;
		}
		if (clampToEdge != null) {
			try {
				linearFilter = constant(filterMode, "LINEAR");
			} catch (ReflectiveOperationException | RuntimeException exception) {
				breakBlit("no filter mode: " + exception);
				return;
			}
		}
		if (textureView == null || clampToEdge == null) {
			breakBlit("no texture view or sampler on this Minecraft version");
		}
	}

	private static void resolveSwing() {
		if (swingResolved) {
			return;
		}
		swingResolved = true;
		try {
			Class<?> description = Class.forName("net.minecraft.world.entity.LivingEntity$SwingDescription");
			Constructor<?>[] constructors = description.getConstructors();
			swing = constructors.length == 0 ? null : constructors[0];
			currentSwing = anyField(AvatarRenderState.class, "currentSwing");
			swingAnimation = anyField(AvatarRenderState.class, "swingAnimation");
			swingBroken = swing == null || currentSwing == null || swingAnimation == null;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			swingBroken = true;
		}
		if (swingBroken) {
			LOGGER.info("MC-Skins: the swing animation needs Minecraft 26.3 or newer and is turned off.");
		}
	}

	private static Object swingInstance() throws ReflectiveOperationException {
		Class<?>[] parameters = swing.getParameterTypes();
		if (parameters.length != 3) {
			throw new NoSuchMethodException("unexpected swing constructor");
		}
		return swing.newInstance(constant(parameters[0], "MAIN_HAND"), constant(parameters[1], "DEFAULT"), 6);
	}

	private static Object constant(Class<?> type, String name) throws ReflectiveOperationException {
		if (type.isEnum()) {
			for (Object value : type.getEnumConstants()) {
				if (((Enum<?>) value).name().equals(name)) {
					return value;
				}
			}
			return type.getEnumConstants()[0];
		}
		try {
			return type.getField(name).get(null);
		} catch (NoSuchFieldException exception) {
			for (Field field : type.getFields()) {
				if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
					return field.get(null);
				}
			}
			throw exception;
		}
	}

	private static Method findBlit(Object view, Object sampler) {
		for (Method candidate : GuiGraphicsExtractor.class.getMethods()) {
			if (!candidate.getName().equals("blit") || candidate.getParameterCount() != 10) {
				continue;
			}
			Class<?>[] types = candidate.getParameterTypes();
			if (types[0].isInstance(view) && types[1].isInstance(sampler)
				&& types[2] == int.class && types[6] == float.class) {
				return candidate;
			}
		}
		return null;
	}

	private static void breakBlit(String reason) {
		blitBroken = true;
		LOGGER.error("MC-Skins: the skin preview cannot be drawn: {}", reason);
	}

	private static Object hud(Minecraft minecraft) {
		try {
			return hudField.get(minecraft.gui);
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("MC-Skins cannot reach the HUD.", exception);
		}
	}

	private static Object invoke(Method method, Object receiver, Object... arguments) {
		try {
			return method.invoke(receiver, arguments);
		} catch (InvocationTargetException exception) {
			throw new IllegalStateException("MC-Skins cannot call " + method, exception.getCause());
		} catch (IllegalAccessException | RuntimeException exception) {
			throw new IllegalStateException("MC-Skins cannot call " + method, exception);
		}
	}

	private static Method publicMethod(Class<?> owner, String name, Class<?>... parameters) {
		try {
			return owner.getMethod(name, parameters);
		} catch (NoSuchMethodException | SecurityException exception) {
			return null;
		}
	}

	private static Field publicField(Class<?> owner, String name) {
		try {
			return owner.getField(name);
		} catch (NoSuchFieldException | SecurityException exception) {
			return null;
		}
	}

	private static Field anyField(Class<?> owner, String name) {
		Field field = publicField(owner, name);
		if (field != null) {
			return field;
		}
		for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
			try {
				Field found = type.getDeclaredField(name);
				if (!Modifier.isStatic(found.getModifiers())) {
					found.setAccessible(true);
					return found;
				}
			} catch (NoSuchFieldException | SecurityException exception) {
			}
		}
		return null;
	}

	private static Class<?> findClass(String... names) {
		for (String name : names) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ignored) {
			}
		}
		return null;
	}
}
