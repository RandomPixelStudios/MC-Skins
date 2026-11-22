package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.account.ProfileImporter;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;

/** Imports a skin as a new project: any player's current skin by name, or a PNG from a web address. */
final class ImportScreen extends SkinScreen {
	private static String playerDraft = "";
	private static String urlDraft = "";
	private final SkinRepository repository;
	private final Consumer<UUID> imported;
	private boolean busy;

	ImportScreen(DressingRoomScreen parent, SkinRepository repository, Consumer<UUID> imported) {
		super("mcskins.import.title", parent);
		this.repository = repository;
		this.imported = imported;
	}

	@Override
	protected void init() {
		int fieldWidth = Math.min(300, width - 40);
		int x = (width - fieldWidth) / 2;
		int y = height / 2 - 60;
		EditBox player = field(tr("mcskins.import.player"), playerDraft, x, y, fieldWidth - 94, 16);
		player.setResponder(value -> playerDraft = value);
		button(tr("mcskins.import.by_name"), tr("mcskins.import.by_name.tip"), x + fieldWidth - 90, y, 90, 20,
			() -> start(ProfileImporter.byName(playerDraft))).active = !busy;
		EditBox url = field(tr("mcskins.import.url"), urlDraft, x, y + 50, fieldWidth - 94, 2048);
		url.setResponder(value -> urlDraft = value);
		button(tr("mcskins.import.by_url"), tr("mcskins.import.by_url.tip"), x + fieldWidth - 90, y + 50, 90, 20,
			() -> start(ProfileImporter.byUrl(urlDraft, tr("mcskins.imported_skin")))).active = !busy;
		button(tr("mcskins.back"), x, y + 96, fieldWidth, 20, this::onClose);
		setInitialFocus(player);
	}

	private void start(CompletableFuture<ProfileImporter.Imported> task) {
		busy = true;
		status = tr("mcskins.import.running");
		rebuildWidgets();
		String layerName = tr("mcskins.layer.imported");
		task.thenApplyAsync(result -> {
			try {
				SkinDocument document = new SkinDocument(UUID.randomUUID(), result.name(), result.slim(),
					List.of(new SkinLayer(layerName, result.pixels())), 0);
				repository.save(document);
				return document;
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((document, error) -> minecraft.execute(() -> {
			busy = false;
			if (error == null) {
				imported.accept(document.id());
				status = tr("mcskins.status.imported_skin", document.name());
				onClose();
				if (parent instanceof SkinScreen screen) {
					screen.status = status;
				}
			} else {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				status = cause instanceof IOException ? cause.getMessage() : tr("mcskins.import.error.failed");
				rebuildWidgets();
			}
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int fieldWidth = Math.min(300, width - 40);
		int x = (width - fieldWidth) / 2;
		int y = height / 2 - 60;
		text(graphics, tr("mcskins.import.player.caption"), x, y - 12, MUTED, fieldWidth);
		text(graphics, tr("mcskins.import.url.caption"), x, y + 38, MUTED, fieldWidth);
		text(graphics, tr("mcskins.import.legacy_hint"), x, y + 76, MUTED, fieldWidth);
	}
}
