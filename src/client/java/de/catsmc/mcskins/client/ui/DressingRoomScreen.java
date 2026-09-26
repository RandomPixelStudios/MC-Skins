package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.account.AccountProfile;
import de.catsmc.mcskins.client.account.AccountService;
import de.catsmc.mcskins.client.account.CapeCache;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.compat.InputCompat;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

public final class DressingRoomScreen extends SkinScreen {
	private static final int CARD = 64;
	private static boolean autoRefreshed;

	private final SkinRepository repository;
	private final AccountService account;
	private final SkinModelPreview preview = new SkinModelPreview();
	private final Map<String, int[]> capeIcons = new HashMap<>();
	private List<SkinDocument> documents = List.of();
	private List<int[]> previews = List.of();
	private boolean documentsDirty = true;
	private AccountProfile profile;
	private AccountService.Image activeSkin;
	private CompletableFuture<AccountService.Result> operation;
	private CompletableFuture<Map<String, int[]>> capeTask;
	private UUID operationPlayer;
	private AccountService.Image pendingSkin;
	private String pendingCape;
	private String previewCape;
	private String confirmation;
	private int selected = -1;
	private UUID pendingDelete;
	private int scroll;
	private boolean scrollDrag;
	private int scrollGrab;
	private boolean previewDrag;
	private int listWidth = 250;
	private int previewX;
	private int previewWidth;
	private EditBox name;
	private String renameDraft;

	public DressingRoomScreen(Screen parent, SkinRepository repository) {
		super("Dressing Room Alpha", parent);
		this.repository = repository;
		account = new AccountService(repository);
	}

	@Override
	protected void init() {
		if (documentsDirty) {
			reloadDocuments();
		}
		if (profile != null && !profile.id().equals(minecraft.getUser().getProfileId())) {
			profile = null;
			activeSkin = null;
			confirmation = null;
			pendingSkin = null;
			previewCape = null;
			capeIcons.clear();
			autoRefreshed = false;
		}
		previewDrag = false;
		scrollDrag = false;
		if (!autoRefreshed && operation == null && profile == null) {
			autoRefreshed = true;
			begin(account.refresh(minecraft.getUser().getAccessToken(), minecraft.getUser().getProfileId()));
			return;
		}
		listWidth = width >= 520 ? 250 : Math.max(110, width - 190);
		previewX = 20 + listWidth;
		previewWidth = Math.max(1, width - 10 - previewX);
		preview.viewport(previewX, 64, previewWidth, Math.max(1, height - 64 - 190));
		button("Back", 10, 38, 60, this::onClose);
		button(operation == null ? "Refresh" : "Loading...", 76, 38, 100, () -> begin(account.refresh(minecraft.getUser().getAccessToken(),
			minecraft.getUser().getProfileId()))).active = operation == null;
		if (confirmation != null) {
			button("Confirm account change", 10, 130, Math.min(180, width - 20), this::confirm).active = operation == null;
			button("Cancel", 10, 154, 90, () -> { clearConfirmation(); rebuildWidgets(); });
			return;
		}
		if (selected >= 0) {
			SkinDocument document = documents.get(selected);
			int actions = previewX;
			int actionWidth = Math.max(1, width - 10 - actions);
			int base = 64 + previewHeight();
			name = field("Skin name", renameDraft == null ? document.name() : renameDraft, actions, base + 6,
				Math.max(40, actionWidth - 86), 64);
			name.setResponder(value -> renameDraft = value);
			button("Rename", width - 86, base + 6, 76, () -> {
				String value = name.getValue().strip();
				if (value.isEmpty()) {
					status = "Enter a skin name.";
					return;
				}
				String previousName = document.name();
				try {
					document.rename(value);
					repository.save(document);
					pendingDelete = null;
					documentsDirty = true;
					renameDraft = null;
					rebuildWidgets();
					status = "Name saved locally.";
				} catch (IOException | RuntimeException exception) {
					document.rename(previousName);
					failure(exception);
				}
			});
			int half = Math.max(1, (actionWidth - 4) / 2);
			button("Edit", actions, base + 30, half, () -> {
				documentsDirty = true;
				GameCompat.setScreen(minecraft, new SkinEditorScreen(this, repository, document));
			});
			button("Export PNG", actions + half + 4, base + 30, Math.max(1, actionWidth - half - 4), () -> {
				try {
					status = "Exported: " + repository.export(document);
				} catch (IOException | RuntimeException exception) {
					failure(exception);
				}
			});
			button("Upload to account", actions, base + 54, half, () -> {
				pendingSkin = new AccountService.Image(document.slim(), document.flatten());
				confirmation = "Upload " + document.name() + " (" + (document.slim() ? "slim" : "classic") + ")?";
				operationPlayer = minecraft.getUser().getProfileId();
				rebuildWidgets();
			}).active = operation == null;
			boolean armed = document.id().equals(pendingDelete);
			button(armed ? "Confirm delete" : "Delete", actions + half + 4, base + 54, Math.max(1, actionWidth - half - 4), () -> {
				if (!document.id().equals(pendingDelete)) {
					pendingDelete = document.id();
					status = "Click Delete again to remove " + document.name() + " permanently.";
					rebuildWidgets();
					return;
				}
				pendingDelete = null;
				try {
					repository.delete(document);
					selected = -1;
					documentsDirty = true;
					renameDraft = null;
					rebuildWidgets();
					status = "Deleted " + document.name() + " locally.";
				} catch (IOException | RuntimeException exception) {
					failure(exception);
				}
			});
		}
		if (profile != null) {
			int base = 64 + previewHeight();
			button(previewCape == null ? "Hide cape" : "Wear cape", width - 86, base + 78, 76, () -> {
				pendingCape = previewCape;
				confirmation = previewCape == null ? "Hide your account cape?" : "Wear this cape on your account?";
				operationPlayer = minecraft.getUser().getProfileId();
				rebuildWidgets();
			}).active = operation == null;
		}
		updatePreview();
	}

	private void reloadDocuments() {
		UUID selectedId = selected >= 0 && selected < documents.size() ? documents.get(selected).id() : null;
		boolean kept = false;
		try {
			documents = repository.list();
			previews = documents.stream().map(SkinDocument::flatten).toList();
			selected = -1;
			for (int index = 0; index < documents.size(); index++) {
				if (documents.get(index).id().equals(selectedId)) {
					selected = index;
					kept = true;
				}
			}
			if (selected < 0 && !documents.isEmpty()) {
				selected = 0;
			}
			documentsDirty = false;
		} catch (IOException | RuntimeException exception) {
			documents = List.of();
			previews = List.of();
			selected = -1;
			failure(exception);
		}
		if (!kept) {
			renameDraft = null;
		}
	}

	private int previewHeight() {
		return Math.max(1, height - 64 - 190);
	}

	private int listTop() {
		return 64;
	}

	private int listBottom() {
		return height - 14;
	}

	private int cardWidth() {
		return listWidth / 2;
	}

	private int positions() {
		return documents.size() + 1;
	}

	private int rows() {
		return (positions() + 1) / 2;
	}

	private void updatePreview() {
		if (hasSkin()) {
			preview.texture(previews.get(selected), documents.get(selected).slim());
		}
		preview.cape(previewCape == null ? null : capeIcons.get(previewCape));
	}

	private boolean hasSkin() {
		return selected >= 0 && selected < documents.size();
	}

	private void confirm() {
		if (!minecraft.getUser().getProfileId().equals(operationPlayer)) {
			clearConfirmation();
			status = "Session changed. Refresh the account first.";
			rebuildWidgets();
			return;
		}
		String token = minecraft.getUser().getAccessToken();
		CompletableFuture<AccountService.Result> task = pendingSkin != null
			? account.upload(token, operationPlayer, pendingSkin)
			: account.cape(token, operationPlayer, profile, pendingCape);
		clearConfirmation();
		begin(task);
	}

	private void begin(CompletableFuture<AccountService.Result> task) {
		operationPlayer = minecraft.getUser().getProfileId();
		clearConfirmation();
		operation = task;
		activeSkin = null;
		profile = null;
		previewCape = null;
		capeIcons.clear();
		capeTask = null;
		status = "Account request running; no automatic retries.";
		rebuildWidgets();
	}

	private void clearConfirmation() {
		confirmation = null;
		pendingSkin = null;
		pendingCape = null;
	}

	@Override
	public void tick() {
		super.tick();
		if (capeTask != null && capeTask.isDone()) {
			try {
				capeIcons.putAll(capeTask.join());
			} catch (java.util.concurrent.CompletionException exception) {
				Throwable cause = exception.getCause();
				status = cause instanceof IOException ? cause.getMessage() : "Cape icons failed.";
			} finally {
				capeTask = null;
				updatePreview();
			}
		}
		if (operation == null || !operation.isDone()) {
			return;
		}
		try {
			AccountService.Result result = operation.join();
			documentsDirty = true;
			if (minecraft.getUser().getProfileId().equals(operationPlayer)) {
				profile = result.profile();
				activeSkin = result.active();
				status = result.message();
				loadCapeIcons();
			} else {
				status = "Session changed; refresh your current account.";
			}
		} catch (java.util.concurrent.CompletionException exception) {
			Throwable cause = exception.getCause();
			status = cause instanceof IOException ? cause.getMessage() : "Account operation failed.";
			autoRefreshed = true;
		} finally {
			operation = null;
			rebuildWidgets();
		}
	}

	private void loadCapeIcons() {
		capeIcons.clear();
		if (profile == null || profile.capes().isEmpty()) {
			capeTask = null;
			return;
		}
		var capes = List.copyOf(profile.capes());
		capeTask = CompletableFuture.supplyAsync(() -> {
			var loaded = new HashMap<String, int[]>();
			String failure = null;
			for (var cape : capes) {
				try {
					loaded.put(cape.id(), CapeCache.load(cape.texture()));
				} catch (IOException exception) {
					failure = exception.getMessage();
				}
			}
			if (loaded.isEmpty() && failure != null) {
				throw new java.util.concurrent.CompletionException(new IOException(failure));
			}
			return Map.copyOf(loaded);
		});
	}

	private boolean onPreview(double x, double y) {
		return x >= previewX && y >= 64 && x < previewX + previewWidth && y < 64 + previewHeight();
	}

	private boolean onList(double x, double y) {
		return x >= 10 && y >= listTop() && x < 10 + listWidth && y < listBottom();
	}

	private boolean onCapes(double x, double y) {
		int base = 64 + previewHeight();
		if (profile == null || x < previewX + 52) {
			return false;
		}
		int column = (int) ((x - previewX - 52) / 26);
		int cellX = previewX + 52 + column * 26;
		int cellWidth = column == 0 ? 24 : 20;
		int cellBottom = base + (column == 0 ? 112 : 110);
		return y >= base + 78 && y < cellBottom && x < cellX + cellWidth
			&& (column == 0 || (column - 1 < profile.capes().size() && cellX + 20 <= width - 100));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (confirmation == null && onPreview(event.x(), event.y())
			&& event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			clearFocus();
			previewDrag = true;
			return true;
		}
		if (confirmation == null && onCapes(event.x(), event.y())
			&& event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			int slot = (int) ((event.x() - previewX - 52) / 26);
			if (slot < 0) {
				return true;
			}
			if (slot == 0) {
				previewCape = null;
			} else if (profile != null && slot - 1 < profile.capes().size()) {
				String id = profile.capes().get(slot - 1).id();
				previewCape = id.equals(previewCape) ? null : id;
			} else {
				return true;
			}
			updatePreview();
			rebuildWidgets();
			return true;
		}
		if (confirmation == null && onList(event.x(), event.y())
			&& event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			int track = 10 + listWidth - 8;
			if (event.x() >= track && rows() * CARD > listBottom() - listTop()) {
				scrollDrag = true;
				scrollGrab = (int) event.y() - thumbTop();
				return true;
			}
			int position = ((int) (event.y() - listTop()) + scroll) / CARD * 2 + (int) ((event.x() - 10) / cardWidth());
			if (position == 0) {
				documentsDirty = true;
				GameCompat.setScreen(minecraft, new CreateSkinScreen(this, repository));
				return true;
			}
			int index = position - 1;
			if (index >= 0 && index < documents.size()) {
				if (selected != index) {
					pendingDelete = null;
					renameDraft = null;
				}
				selected = index;
				updatePreview();
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int thumbTop() {
		int visible = listBottom() - listTop();
		int content = rows() * CARD;
		int track = visible - 8;
		int thumb = Math.max(12, track * visible / Math.max(1, content));
		return listTop() + 4 + (track - thumb) * scroll / Math.max(1, content - visible);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (previewDrag && event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			preview.orbit(dx, dy);
			return true;
		}
		if (scrollDrag && event.button() == InputCompat.MOUSE_BUTTON_LEFT) {
			int visible = listBottom() - listTop();
			int content = rows() * CARD;
			int track = visible - 8;
			int thumb = Math.max(12, track * visible / Math.max(1, content));
			scroll = Math.clamp((int) ((event.y() - scrollGrab - listTop() - 4) * (content - visible) / Math.max(1, track - thumb)),
				0, Math.max(0, content - visible));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = previewDrag || scrollDrag;
		previewDrag = false;
		scrollDrag = false;
		return handled || super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (onList(x, y)) {
			scroll = Math.clamp((int) (scroll - vertical * CARD), 0, Math.max(0, rows() * CARD - (listBottom() - listTop())));
			return true;
		}
		if (onPreview(x, y)) {
			preview.zoom(vertical);
			return true;
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	@Override
	public void removed() {
		renameDraft = null;
		preview.close();
		super.removed();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (confirmation != null) {
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			text(graphics, confirmation, 10, 76, 0xFFFFFFFF, width - 20);
			text(graphics, "Changes your online Minecraft account, not just this library.", 10, 94, 0xFFFFD38A, width - 20);
			text(graphics, "Account: " + minecraft.getUser().getName(), 10, 112, 0xFFA0A0A0, width - 20);
			return;
		}
		graphics.enableScissor(previewX, 64, previewX + previewWidth, 64 + previewHeight());
		if (hasSkin()) {
			preview.render(graphics);
		}
		graphics.disableScissor();
		graphics.outline(previewX, 64, previewWidth, previewHeight(), 0xFF8B8B8B);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		text(graphics, "Drag to rotate | Wheel to zoom", previewX + 5, 69, 0xFFA0A0A0, previewWidth - 10);
		drawList(graphics);
		drawCapes(graphics);
		if (documents.isEmpty()) {
			text(graphics, "Create a skin or import your account profile skins.", 14, 84, 0xFFA0A0A0, listWidth - 8);
		}
	}

	private void drawList(GuiGraphicsExtractor graphics) {
		int top = listTop();
		int bottom = listBottom();
		int visible = bottom - top;
		scroll = Math.clamp(scroll, 0, Math.max(0, rows() * CARD - visible));
		graphics.enableScissor(10, top, 10 + listWidth, bottom);
		for (int position = 0; position < positions(); position++) {
			int cardY = top - scroll + position / 2 * CARD;
			if (cardY + CARD < top || cardY > bottom) {
				continue;
			}
			int cardX = 10 + position % 2 * cardWidth();
			if (position == 0) {
				graphics.fill(cardX + 2, cardY + 2, cardX + cardWidth() - 2, cardY + CARD - 2, 0xC0101010);
				int centerX = cardX + cardWidth() / 2;
				int centerY = cardY + 26;
				graphics.fill(centerX - 4, centerY - 18, centerX + 4, centerY + 18, 0xFFE0E0E0);
				graphics.fill(centerX - 18, centerY - 4, centerX + 18, centerY + 4, 0xFFE0E0E0);
				String label = "New skin";
				graphics.text(font, label, cardX + (cardWidth() - font.width(label)) / 2, cardY + 48, 0xFFFFFFFF, false);
				continue;
			}
			int index = position - 1;
			SkinPixels.head(graphics, previews.get(index), cardX + cardWidth() / 2 - 20, cardY + 4, 5);
			String label = font.plainSubstrByWidth(documents.get(index).name(), cardWidth() - 8);
			graphics.text(font, label, cardX + (cardWidth() - font.width(label)) / 2, cardY + 48, 0xFFFFFFFF, false);
			if (index == selected) {
				graphics.outline(cardX + 2, cardY + 2, cardWidth() - 4, CARD - 4, 0xFFFFFFFF);
			}
			if (activeSkin != null && activeSkin.matches(documents.get(index).slim(), previews.get(index))) {
				graphics.outline(cardX, cardY, cardWidth(), CARD, 0xFF75D6AD);
			}
		}
		graphics.disableScissor();
		if (rows() * CARD > visible) {
			int track = 10 + listWidth - 8;
			graphics.fill(track, top, track + 6, bottom, 0xFF000000);
			int thumb = Math.max(12, (visible - 8) * visible / Math.max(1, rows() * CARD));
			graphics.fill(track, thumbTop(), track + 6, thumbTop() + thumb, 0xFF808080);
		}
	}

	private void drawCapes(GuiGraphicsExtractor graphics) {
		int base = 64 + previewHeight();
		text(graphics, "Capes:", previewX, base + 80, 0xFFA0A0A0, 60);
		if (profile == null) {
			text(graphics, profile == null && operation != null ? "Loading..." : "Refresh to load your capes.", previewX + 52, base + 80, 0xFFA0A0A0,
				Math.max(1, width - 10 - previewX - 52));
			return;
		}
		int x = previewX + 52;
		graphics.fill(x, base + 78, x + 24, base + 112, 0xC0101010);
		graphics.text(font, "X", x + 9, base + 90, 0xFFFFFFFF, false);
		if (previewCape == null) {
			graphics.outline(x, base + 78, 24, 34, 0xFFFFFFFF);
		}
		x += 26;
		String worn = profile.capes().stream().filter(AccountProfile.Cape::active).map(AccountProfile.Cape::id).findFirst().orElse(null);
		for (var cape : profile.capes()) {
			if (x + 20 > width - 100) {
				break;
			}
			int[] icon = capeIcons.get(cape.id());
			if (icon != null) {
				SkinPixels.cape(graphics, icon, x, base + 78, 2);
			} else {
				graphics.fill(x, base + 78, x + 20, base + 110, 0xC0101010);
			}
			if (cape.id().equals(previewCape)) {
				graphics.outline(x - 2, base + 76, 24, 36, 0xFFFFFFFF);
			}
			if (cape.id().equals(worn)) {
				graphics.outline(x - 2, base + 76, 24, 36, 0xFF75D6AD);
			}
			x += 26;
		}
		buttonRowHint(graphics, base);
	}

	private void buttonRowHint(GuiGraphicsExtractor graphics, int base) {
		if (selected < 0) {
			return;
		}
		String worn = profile == null ? null
			: profile.capes().stream().filter(AccountProfile.Cape::active).map(AccountProfile.Cape::id).findFirst().orElse(null);
		if (previewCape != null && !previewCape.equals(worn) && capeIcons.containsKey(previewCape)) {
			text(graphics, "Previewing selected cape. Use Wear to put it on your account.", previewX, base + 118, 0xFFA0A0A0,
				Math.max(1, width - 10 - previewX));
		}
	}
}
