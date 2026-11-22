package de.catsmc.mcskins.client;

import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.mixin.PlayerSkinWidgetAccessor;
import de.catsmc.mcskins.client.preview.PreviewSession;
import de.catsmc.mcskins.client.ui.DressingRoomScreen;
import de.catsmc.mcskins.client.ui.QuickSwitchScreen;
import de.catsmc.mcskins.skin.LibraryIndex;
import de.catsmc.mcskins.skin.SafeFiles;
import de.catsmc.mcskins.skin.SkinRepository;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mcskins-io").factory());
	private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mcskins-net").factory());
	/** Title screen model turn speed in the widget's rotation units per second (the old 0.025 per tick). */
	private static final float SPIN_PER_SECOND = 0.5F;
	private static ClientSettings settings;
	private static SkinRepository repository;
	private static LibraryIndex library;
	private static Path libraryFile;
	private static KeyMapping quickSwitch;
	private static long lastSpin;

	/** A single background thread for disk work, so the render thread never waits on I/O. Saves run in order. */
	public static ExecutorService io() {
		return IO;
	}

	/** A separate background thread for downloads, so slow networks never delay saving, export or loading. */
	public static ExecutorService network() {
		return NETWORK;
	}

	public static ClientSettings settings() {
		return settings;
	}

	public static SkinRepository repository() {
		return repository;
	}

	/** Favourites, tags and creation times of the library; change it on the render thread, then call {@link #saveLibrary()}. */
	public static LibraryIndex library() {
		return library;
	}

	public static void saveLibrary() {
		byte[] bytes = library.encode();
		IO.execute(() -> {
			try {
				SafeFiles.atomicWrite(libraryFile, bytes);
			} catch (java.io.IOException | RuntimeException exception) {
				com.mojang.logging.LogUtils.getLogger().warn("MC-Skins: could not save the library index: {}", exception.getMessage());
			}
		});
	}

	/** Turns the title screen models smoothly with the frame time instead of in 20 Hz steps. */
	private static void spin(net.minecraft.client.gui.screens.Screen screen) {
		long now = System.nanoTime();
		float seconds = lastSpin == 0 ? 0 : Math.min(0.1F, (now - lastSpin) / 1_000_000_000F);
		lastSpin = now;
		if (!settings.menuSpin() || seconds <= 0) {
			return;
		}
		for (var child : screen.children()) {
			if (child instanceof PlayerSkinWidget widget) {
				var accessor = (PlayerSkinWidgetAccessor) widget;
				accessor.mcskins$setRotationY(accessor.mcskins$getRotationY() + SPIN_PER_SECOND * seconds);
			}
		}
	}

	@Override
	public void onInitializeClient() {
		PreviewSession.initialize();
		Path root = FabricLoader.getInstance().getConfigDir().resolve("mcskins");
		repository = new SkinRepository(root);
		settings = new ClientSettings(root.resolve("settings.properties"), IO);
		libraryFile = root.resolve("library.properties");
		library = LibraryIndex.load(libraryFile);
		quickSwitch = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcskins.quick_switch",
			InputConstants.KEY_K, new KeyMapping.Category(Identifier.fromNamespaceAndPath("mcskins", "main"))));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (quickSwitch.consumeClick()) {
				if (GameCompat.currentScreen(client) == null && client.player != null && !PreviewSession.isActive()) {
					GameCompat.setScreen(client, new QuickSwitchScreen(null, repository));
				}
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> settings.writeNow());
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen && PreviewSession.isActive()) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("mcskins.preview.back_to_editor"), button ->
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
			Screens.getWidgets(screen).add(Button.builder(Component.translatable("mcskins.dressing_room"), button ->
				GameCompat.setScreen(client, new DressingRoomScreen(screen, repository)))
				.bounds(panelX, panelButtonY, panelWidth, 20).build());
			lastSpin = 0;
			ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) -> {
				spin(current);
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
}
