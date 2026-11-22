package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
	private boolean loadedSlim;
	private boolean creating;

	CreateSkinScreen(Screen parent, SkinRepository repository) {
		super("mcskins.create.title", parent);
		this.repository = repository;
		loadTemplates();
	}

	/** Loads Steve and Alex for the currently chosen arm width, so the texture always matches the model. */
	private boolean loadTemplates() {
		try {
			steve = TemplateSkins.pixels("steve", slim);
			alex = TemplateSkins.pixels("alex", slim);
			loadedSlim = slim;
			templatesAvailable = true;
			return true;
		} catch (IOException | RuntimeException exception) {
			templatesAvailable = false;
			status = tr("mcskins.create.templates_unavailable", message(exception));
			return false;
		}
	}

	private static String message(Exception exception) {
		String text = exception.getMessage();
		return text == null ? exception.getClass().getSimpleName() : text;
	}

	@Override
	protected void init() {
		if (templatesAvailable && loadedSlim != slim) {
			loadTemplates();
		}
		button(tr("mcskins.back"), 10, 36, 60, this::onClose);
		if (!choosingTemplate) {
			int fieldWidth = Math.min(260, width - 40);
			EditBox name = field(tr("mcskins.name"), skinName, (width - fieldWidth) / 2, 92, fieldWidth, 64);
			Button next = button(tr("mcskins.next"), (width - 120) / 2, 124, 120, () -> {
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
			button(tr(slim ? "mcskins.create.slim_arms" : "mcskins.create.classic_arms"), (width - 120) / 2, 152, 120, () -> {
				slim = !slim;
				lastSlim = slim;
				rebuildWidgets();
			});
			setInitialFocus(name);
		} else {
			int cardWidth = Math.min(140, (width - 32) / 3);
			int left = (width - cardWidth * 3) / 2;
			String[] labels = {"Steve", "Alex", tr("mcskins.create.blank")};
			String[] templates = {"steve", "alex", "custom"};
			for (int index = 0; index < 3; index++) {
				String template = templates[index];
				Button option = button(labels[index], left + index * cardWidth + 4, 154, cardWidth - 8, () -> create(template));
				option.active = !creating && (index >= 2 || templatesAvailable);
			}
			// The chosen arm width applies to every template; it can still be changed here.
			button(tr(slim ? "mcskins.create.slim_arms" : "mcskins.create.classic_arms"), (width - 120) / 2, 180, 120, 20, () -> {
				slim = !slim;
				lastSlim = slim;
				loadTemplates();
				rebuildWidgets();
			}).active = !creating;
		}
	}

	private void create(String template) {
		if (creating) {
			return;
		}
		SkinDocument document;
		SkinRepository.PreparedSave prepared;
		try {
			if (template.equals("custom")) {
				document = repository.create(skinName.strip(), slim, template, tr("mcskins.layer.base_name"));
			} else if (loadTemplates()) {
				boolean alexTemplate = template.equals("alex");
				int[] pixels = alexTemplate ? alex : steve;
				document = new SkinDocument(UUID.randomUUID(), skinName.strip(), slim,
					List.of(new SkinLayer(alexTemplate ? "Alex" : "Steve", pixels)), 0);
			} else {
				rebuildWidgets();
				return;
			}
			prepared = repository.prepare(document);
		} catch (IOException | RuntimeException exception) {
			failure(exception);
			return;
		}
		creating = true;
		status = tr("mcskins.status.saving");
		rebuildWidgets();
		CompletableFuture.supplyAsync(() -> {
			try {
				return repository.write(prepared);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((written, error) -> minecraft.execute(() -> {
			creating = false;
			if (error == null) {
				document.markSaved(prepared.revision(), prepared.sequence());
				if (GameCompat.currentScreen(minecraft) == this) {
					GameCompat.setScreen(minecraft, new SkinEditorScreen(parent, repository, document));
				}
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
				rebuildWidgets();
			}
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (!choosingTemplate) {
			text(graphics, tr("mcskins.create.prompt"), (width - Math.min(260, width - 40)) / 2, 76, 0xFFA0A0A0, width - 40);
			return;
		}
		int cardWidth = Math.min(140, (width - 32) / 3);
		int left = (width - cardWidth * 3) / 2;
		for (int index = 0; index < 3; index++) {
			int x = left + index * cardWidth;
			if (index < 2) {
				SkinPixels.head(graphics, index == 0 ? steve : alex, x + cardWidth / 2 - 24, 92, 6);
			} else {
				centered(graphics, tr("mcskins.create.blank_hint"), x + cardWidth / 2, 112, MUTED, cardWidth - 20);
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
