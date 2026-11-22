package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinRepository;
import de.catsmc.mcskins.skin.SnapshotStore;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The version history of one skin: take a snapshot now, or restore an older one. */
final class SnapshotScreen extends SkinScreen {
	private static final int ROWS = 8;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
	private final SkinRepository repository;
	private final SkinDocument document;
	private List<SnapshotStore.Snapshot> snapshots = List.of();
	private boolean busy = true;
	private int page;
	private SnapshotStore.Snapshot armed;

	SnapshotScreen(DressingRoomScreen parent, SkinRepository repository, SkinDocument document) {
		super("mcskins.snapshots.title", parent);
		this.repository = repository;
		this.document = document;
		reload();
	}

	private void reload() {
		busy = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				return repository.snapshots().list(document.id());
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((list, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
			busy = false;
			if (error == null) {
				snapshots = list;
			} else {
				status = tr("mcskins.error", String.valueOf(error.getCause() == null ? error.getMessage() : error.getCause().getMessage()));
			}
			if (minecraft != null) {
				rebuildWidgets();
			}
		}));
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(320, width - 40);
		int x = (width - panelWidth) / 2;
		int y = 44;
		button(tr("mcskins.snapshots.take"), tr("mcskins.snapshots.take.tip"), x, y, panelWidth, 20, this::takeSnapshot).active = !busy;
		int pages = Math.max(1, (snapshots.size() + ROWS - 1) / ROWS);
		page = Math.clamp(page, 0, pages - 1);
		for (int row = 0; row < ROWS; row++) {
			int index = page * ROWS + row;
			if (index >= snapshots.size()) {
				break;
			}
			SnapshotStore.Snapshot snapshot = snapshots.get(index);
			boolean confirm = snapshot.equals(armed);
			button(tr(confirm ? "mcskins.snapshots.restore.confirm" : "mcskins.snapshots.restore"), x + panelWidth - 90, y + 28 + row * 22, 90, 20,
				() -> restore(snapshot)).active = !busy;
		}
		int bottom = y + 28 + ROWS * 22 + 4;
		button("‹", x, bottom, 20, 20, () -> {
			page--;
			rebuildWidgets();
		}).active = page > 0;
		button("›", x + 24, bottom, 20, 20, () -> {
			page++;
			rebuildWidgets();
		}).active = page < pages - 1;
		button(tr("mcskins.back"), x + panelWidth - 100, bottom, 100, 20, this::onClose);
	}

	private void takeSnapshot() {
		if (document.dirty()) {
			status = tr("mcskins.snapshots.unsaved");
		}
		byte[] bytes;
		try {
			bytes = SkinRepository.encode(document);
		} catch (IOException | RuntimeException exception) {
			failure(exception);
			return;
		}
		busy = true;
		rebuildWidgets();
		CompletableFuture.supplyAsync(() -> {
			try {
				return repository.snapshot(document.id(), bytes);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((snapshot, error) -> minecraft.execute(() -> {
			status = error != null ? tr("mcskins.error", String.valueOf(error.getMessage()))
				: snapshot == null ? tr("mcskins.snapshots.unchanged") : tr("mcskins.snapshots.taken");
			reload();
		}));
	}

	private void restore(SnapshotStore.Snapshot snapshot) {
		if (!snapshot.equals(armed)) {
			armed = snapshot;
			status = tr("mcskins.snapshots.restore.armed");
			rebuildWidgets();
			return;
		}
		armed = null;
		busy = true;
		rebuildWidgets();
		CompletableFuture.supplyAsync(() -> {
			try {
				return repository.restore(snapshot);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((restored, error) -> minecraft.execute(() -> {
			if (error == null) {
				status = tr("mcskins.snapshots.restored", format(snapshot.time()));
				if (parent instanceof SkinScreen screen) {
					screen.status = status;
				}
				onClose();
			} else {
				Throwable cause = error.getCause() == null ? error : error.getCause();
				status = tr("mcskins.error", String.valueOf(cause.getMessage()));
				reload();
			}
		}));
	}

	private static String format(long time) {
		return TIME.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int panelWidth = Math.min(320, width - 40);
		int x = (width - panelWidth) / 2;
		int y = 44;
		centered(graphics, document.name(), width / 2, 30, MUTED, width - 20);
		if (busy && snapshots.isEmpty()) {
			text(graphics, tr("mcskins.loading"), x, y + 34, MUTED, panelWidth);
		} else if (snapshots.isEmpty()) {
			text(graphics, tr("mcskins.snapshots.none"), x, y + 34, MUTED, panelWidth);
		}
		for (int row = 0; row < ROWS; row++) {
			int index = page * ROWS + row;
			if (index >= snapshots.size()) {
				break;
			}
			SnapshotStore.Snapshot snapshot = snapshots.get(index);
			int rowY = y + 28 + row * 22;
			text(graphics, format(snapshot.time()), x, rowY + 6, 0xFFFFFFFF, panelWidth - 180);
			text(graphics, tr(snapshot.manual() ? "mcskins.snapshots.manual" : "mcskins.snapshots.auto"), x + panelWidth - 170, rowY + 6,
				snapshot.manual() ? 0xFFFFE6A0 : MUTED, 76);
		}
	}
}
