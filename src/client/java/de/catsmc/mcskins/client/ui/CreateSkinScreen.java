package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

final class CreateSkinScreen extends SkinScreen {
	private static boolean lastSlim;
	private final SkinRepository repository;
	private String skinName = "";
	private boolean slim = lastSlim;
	private boolean choosingTemplate;
	private boolean templatesAvailable;
	private int[] steve = new int[4096];
	private int[] alex = new int[4096];

	CreateSkinScreen(Screen parent, SkinRepository repository) {
		super("Create New Skin", parent);
		this.repository = repository;
		loadTemplates();
	}

	private boolean loadTemplates() {
		try {
			steve = TemplateSkins.pixels(false);
			alex = TemplateSkins.pixels(true);
			templatesAvailable = true;
			return true;
		} catch (IOException | RuntimeException exception) {
			templatesAvailable = false;
			status = "Vanilla templates unavailable: " + message(exception);
			return false;
		}
	}

	private static String message(Exception exception) {
		String text = exception.getMessage();
		return text == null ? exception.getClass().getSimpleName() : text;
	}

	@Override
	protected void init() {
		button("Back", 10, 38, 60, this::onClose);
		if (!choosingTemplate) {
			int fieldWidth = Math.min(260, width - 40);
			EditBox name = field("Skin name", skinName, (width - fieldWidth) / 2, 92, fieldWidth, 64);
			Button next = button("Next", (width - 120) / 2, 124, 120, () -> {
				skinName = name.getValue().strip();
				if (!skinName.isEmpty()) {
					choosingTemplate = true;
					rebuildWidgets();
				}
			});
			next.active = !skinName.isBlank();
			name.setResponder(value -> {
				skinName = value;
				next.active = !value.isBlank();
			});
			button(slim ? "Slim arms" : "Classic arms", (width - 120) / 2, 152, 120, () -> {
				slim = !slim;
				lastSlim = slim;
				rebuildWidgets();
			});
			setInitialFocus(name);
		} else {
			int cardWidth = Math.min(140, (width - 32) / 3);
			int left = (width - cardWidth * 3) / 2;
			String[] labels = {"Steve", "Alex", "Custom"};
			String[] templates = {"steve", "alex", "custom"};
			for (int index = 0; index < 3; index++) {
				String template = templates[index];
				Button option = button(labels[index], left + index * cardWidth + 4, 154, cardWidth - 8, () -> create(template));
				option.active = index >= 2 || templatesAvailable;
			}
		}
	}

	private void create(String template) {
		try {
			SkinDocument document;
			if (template.equals("custom")) {
				document = repository.create(skinName.strip(), slim, template);
			} else if (loadTemplates()) {
				boolean templateSlim = template.equals("alex");
				int[] pixels = templateSlim ? alex : steve;
				document = new SkinDocument(UUID.randomUUID(), skinName.strip(), slim,
					List.of(new SkinLayer(templateSlim ? "Alex" : "Steve", pixels)), 0);
			} else {
				rebuildWidgets();
				return;
			}
			repository.save(document);
			GameCompat.setScreen(minecraft, new SkinEditorScreen(parent, repository, document));
		} catch (IOException | RuntimeException exception) {
			failure(exception);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (!choosingTemplate) {
			text(graphics, "Name your local skin", (width - Math.min(260, width - 40)) / 2, 76, 0xFFA0A0A0, width - 40);
			return;
		}
		int cardWidth = Math.min(140, (width - 32) / 3);
		int left = (width - cardWidth * 3) / 2;
		for (int index = 0; index < 3; index++) {
			int x = left + index * cardWidth;
			graphics.fill(x + 4, 84, x + cardWidth - 4, 150, 0xC0101010);
			if (index < 2) {
				SkinPixels.head(graphics, index == 0 ? steve : alex, x + cardWidth / 2 - 24, 92, 6);
			} else {
				text(graphics, "Blank 64 x 64", x + 10, 112, 0xFFA0A0A0, cardWidth - 20);
			}
		}
	}

	@Override
	public void onClose() {
		if (choosingTemplate) {
			choosingTemplate = false;
			rebuildWidgets();
		} else {
			super.onClose();
		}
	}
}
