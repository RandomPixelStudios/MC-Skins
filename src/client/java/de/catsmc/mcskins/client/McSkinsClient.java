package de.catsmc.mcskins.client;

import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.mixin.PlayerSkinWidgetAccessor;
import de.catsmc.mcskins.client.preview.PreviewSession;
import de.catsmc.mcskins.client.ui.DressingRoomScreen;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class McSkinsClient implements ClientModInitializer {
	private static boolean menuSpin = true;
	private static Path settingsFile;

	@Override
	public void onInitializeClient() {
		PreviewSession.initialize();
		SkinRepository repository = new SkinRepository(FabricLoader.getInstance().getConfigDir().resolve("mcskins"));
		settingsFile = FabricLoader.getInstance().getConfigDir().resolve("mcskins").resolve("settings.properties");
		menuSpin = readSpin();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!menuSpin || !(GameCompat.currentScreen(client) instanceof TitleScreen screen)) {
				return;
			}
			for (var child : screen.children()) {
				if (child instanceof PlayerSkinWidget widget) {
					var accessor = (PlayerSkinWidgetAccessor) widget;
					accessor.mcskins$setRotationY(accessor.mcskins$getRotationY() + 0.025F);
				}
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen && PreviewSession.isActive()) {
				Screens.getWidgets(screen).add(Button.builder(Component.literal("Back to Editor"), button ->
					PreviewSession.exitToEditor()).bounds((width - 204) / 2, height - 32, 204, 20).build());
				return;
			}
			if (!(screen instanceof TitleScreen)) {
				return;
			}
			Button singleplayer = null;
			Button quit = null;
			for (var child : screen.children()) {
				if (child instanceof Button button
					&& button.getMessage().getContents() instanceof TranslatableContents translatable) {
					if (translatable.getKey().equals("menu.singleplayer")) {
						singleplayer = button;
					} else if (translatable.getKey().equals("menu.quit")) {
						quit = button;
					}
				}
			}
			int panelWidth = Math.min(120, Math.max(80, width / 6));
			int x;
			int nameY;
			int previewY;
			int previewHeight;
			int buttonY;
			if (singleplayer != null && quit != null
				&& singleplayer.getX() + singleplayer.getWidth() + 8 + panelWidth <= width - 8
				&& quit.getY() - singleplayer.getY() - 18 >= 60) {
				x = singleplayer.getX() + singleplayer.getWidth() + 8;
				nameY = singleplayer.getY();
				previewY = nameY + 12;
				previewHeight = Math.max(40, Math.min(90, quit.getY() - previewY - 6));
				buttonY = quit.getY();
			} else {
				x = 8;
				nameY = height - 150;
				previewY = nameY + 12;
				previewHeight = Math.max(48, Math.min(90, height - previewY - 30));
				buttonY = previewY + previewHeight + 6;
				if (buttonY + 20 > height - 8) {
					previewHeight = Math.max(40, height - 8 - 20 - 6 - previewY);
					buttonY = previewY + previewHeight + 6;
				}
			}
			int panelX = x;
			int panelButtonY = buttonY;
			Screens.getWidgets(screen).add(Button.builder(Component.literal("Dressing Room"), button ->
				GameCompat.setScreen(client, new DressingRoomScreen(screen, repository)))
				.bounds(panelX, panelButtonY, panelWidth, 20).build());
			ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) -> {
				String name = client.font.plainSubstrByWidth(client.getUser().getName(), panelWidth);
				graphics.text(client.font, name, panelX + (panelWidth - client.font.width(name)) / 2, nameY, 0xFFFFFFFF, true);
			});
			PlayerSkinWidget preview = new PlayerSkinWidget(panelWidth, previewHeight, client.getEntityModels(),
				client.getSkinManager().createLookup(client.getGameProfile(), false));
			preview.setX(panelX);
			preview.setY(previewY);
			Screens.getWidgets(screen).add(preview);
		});
	}

	public static boolean menuSpin() {
		return menuSpin;
	}

	public static void menuSpin(boolean spin) {
		menuSpin = spin;
		var settings = new Properties();
		settings.setProperty("menuSpin", Boolean.toString(spin));
		try {
			Files.createDirectories(settingsFile.getParent());
			try (OutputStream output = Files.newOutputStream(settingsFile)) {
				settings.store(output, null);
			}
		} catch (IOException ignored) {
		}
	}

	private static boolean readSpin() {
		var settings = new Properties();
		try (InputStream input = Files.newInputStream(settingsFile)) {
			settings.load(input);
		} catch (IOException ignored) {
			return true;
		}
		return !settings.getProperty("menuSpin", "true").equals("false");
	}
}
