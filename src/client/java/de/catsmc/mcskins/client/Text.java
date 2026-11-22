package de.catsmc.mcskins.client;

import net.minecraft.client.resources.language.I18n;

/** Translated user-facing text for code outside the screens (account, cape cache, preview session). */
public final class Text {
	private Text() {
	}

	public static String tr(String key, Object... arguments) {
		return I18n.get(key, arguments);
	}
}
