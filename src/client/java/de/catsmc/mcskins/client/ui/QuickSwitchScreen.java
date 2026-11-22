package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.account.AccountService;
import de.catsmc.mcskins.skin.LibraryIndex;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The in-game skin switcher (hotkey, K by default): pick a skin from the library and upload it to the account.
 * Uploads are spaced out (Mojang limits how often a skin may change) and every error is shown in the screen.
 */
public final class QuickSwitchScreen extends SkinScreen {
	/** Mojang rejects rapid skin changes with HTTP 429; this keeps well clear of that. */
	private static final long COOLDOWN_MILLIS = 30_000;
	private static final int CARD = 56;
	private static long lastUpload;
	private final SkinRepository repository;
	private final AccountService account;
	private List<SkinDocument> documents = List.of();
	private List<int[]> previews = List.of();
	private CompletableFuture<?> loading;
	private CompletableFuture<AccountService.Result> upload;
	private int chosen = -1;
	private int scroll;
	private int columns = 1;
	private int gridLeft;

	public QuickSwitchScreen(Screen parent, SkinRepository repository) {
		super("mcskins.quick.title", parent);
		this.repository = repository;
		account = new AccountService(repository, () -> false);
		load();
	}

	private record Loaded(List<SkinDocument> documents, List<int[]> previews) {
	}

	private void load() {
		var task = CompletableFuture.supplyAsync(() -> {
			try {
				List<SkinDocument> list = repository.list();
				return new Loaded(list, list.stream().map(SkinDocument::flatten).toList());
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io());
		loading = task;
		task.whenComplete((loaded, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
			loading = null;
			if (error == null) {
				// Favourites first, then by name.
				LibraryIndex library = McSkinsClient.library();
				var order = new ArrayList<Integer>();
				for (int index = 0; index < loaded.documents().size(); index++) {
					order.add(index);
				}
				order.sort((a, b) -> Boolean.compare(library.entry(loaded.documents().get(b).id()).favourite(),
					library.entry(loaded.documents().get(a).id()).favourite()));
				documents = order.stream().map(loaded.documents()::get).toList();
				previews = order.stream().map(loaded.previews()::get).toList();
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
		int gridWidth = Math.min(width - 40, 8 * CARD);
		columns = Math.max(1, gridWidth / CARD);
		gridLeft = (width - columns * CARD) / 2;
		int buttonY = height - 30;
		if (chosen >= 0 && chosen < documents.size()) {
			int buttonWidth = 120;
			button(tr("mcskins.quick.upload"), width / 2 - buttonWidth - 2, buttonY, buttonWidth, 20, this::upload).active = upload == null;
			button(tr("mcskins.cancel"), width / 2 + 2, buttonY, buttonWidth, 20, () -> {
				chosen = -1;
				rebuildWidgets();
			});
		} else {
			button(tr("mcskins.done"), width / 2 - 60, buttonY, 120, 20, this::onClose);
		}
	}

	private void upload() {
		long wait = lastUpload + COOLDOWN_MILLIS - System.currentTimeMillis();
		if (wait > 0) {
			status = tr("mcskins.quick.cooldown", (wait + 999) / 1000);
			return;
		}
		SkinDocument document = documents.get(chosen);
		UUID player = minecraft.getUser().getProfileId();
		lastUpload = System.currentTimeMillis();
		upload = account.upload(minecraft.getUser().getAccessToken(), player, new AccountService.Image(document.slim(), previews.get(chosen)));
		status = tr("mcskins.account.running");
		rebuildWidgets();
	}

	@Override
	public void tick() {
		if (upload != null && upload.isDone()) {
			try {
				status = upload.join().message() + " " + tr("mcskins.quick.rejoin");
			} catch (CompletionException exception) {
				Throwable cause = exception.getCause();
				status = cause instanceof IOException ? cause.getMessage() : tr("mcskins.account.failed");
			}
			upload = null;
			chosen = -1;
			rebuildWidgets();
		}
	}

	private int top() {
		return 40;
	}

	private int bottom() {
		return height - 52;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (upload != null || event.y() < top() || event.y() >= bottom() || event.x() < gridLeft || event.x() >= gridLeft + columns * CARD) {
			return false;
		}
		int index = ((int) (event.y() - top()) + scroll) / CARD * columns + (int) ((event.x() - gridLeft) / CARD);
		if (index >= 0 && index < documents.size()) {
			chosen = index;
			rebuildWidgets();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		int rows = (documents.size() + columns - 1) / columns;
		scroll = Math.clamp((int) (scroll - vertical * CARD / 2), 0, Math.max(0, rows * CARD - (bottom() - top())));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		centered(graphics, tr("mcskins.quick.hint"), width / 2, 26, MUTED, width - 20);
		if (loading != null) {
			centered(graphics, tr("mcskins.loading"), width / 2, top() + 20, MUTED, width - 20);
		} else if (documents.isEmpty()) {
			centered(graphics, tr("mcskins.dressing_room.empty"), width / 2, top() + 20, MUTED, width - 20);
		}
		graphics.enableScissor(0, top(), width, bottom());
		LibraryIndex library = McSkinsClient.library();
		for (int index = 0; index < documents.size(); index++) {
			int x = gridLeft + index % columns * CARD;
			int y = top() - scroll + index / columns * CARD;
			if (y + CARD < top() || y > bottom()) {
				continue;
			}
			boolean hovered = mouseX >= x && mouseX < x + CARD && mouseY >= y && mouseY < y + CARD && mouseY >= top() && mouseY < bottom();
			graphics.fill(x + 2, y + 2, x + CARD - 2, y + CARD - 2, hovered ? 0x60000000 : 0x40000000);
			SkinPixels.head(graphics, previews.get(index), x + CARD / 2 - 16, y + 5, 4);
			centered(graphics, documents.get(index).name(), x + CARD / 2, y + CARD - 13, 0xFFFFFFFF, CARD - 6);
			if (library.entry(documents.get(index).id()).favourite()) {
				text(graphics, "★", x + 4, y + 4, 0xFFFFD700, 10);
			}
			if (index == chosen) {
				graphics.outline(x + 2, y + 2, CARD - 4, CARD - 4, ACCENT);
			}
		}
		graphics.disableScissor();
		if (chosen >= 0 && chosen < documents.size()) {
			SkinDocument document = documents.get(chosen);
			centered(graphics, tr("mcskins.account.upload.confirm", document.name(), tr(document.slim() ? "mcskins.slim" : "mcskins.classic")),
				width / 2, bottom() + 2, 0xFFFFFFFF, width - 20);
			centered(graphics, tr("mcskins.account.name", minecraft.getUser().getName()), width / 2, bottom() + 12, MUTED, width - 20);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
