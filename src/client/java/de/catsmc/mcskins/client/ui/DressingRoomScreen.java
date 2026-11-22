package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.ClientSettings;
import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.account.AccountProfile;
import de.catsmc.mcskins.client.account.AccountService;
import de.catsmc.mcskins.client.account.CapeCache;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.client.compat.InputCompat;
import de.catsmc.mcskins.skin.LibraryIndex;
import de.catsmc.mcskins.skin.SafeFiles;
import de.catsmc.mcskins.skin.SkinDocument;
import de.catsmc.mcskins.skin.SkinLayer;
import de.catsmc.mcskins.skin.SkinRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

public final class DressingRoomScreen extends SkinScreen {
	private static final int CARD = 64;
	/** Height of the action area under (or beside) the preview: name, buttons, cape label row, cape strip, hint. */
	private static final int ACTIONS = 174;
	private static final int ACTION_COLUMN = 180;
	private static final int MIN_PREVIEW = 100;
	private static final int SLOT = 26;
	private static final int ARROW = 12;
	private static boolean autoRefreshed;
	private static String search = "";
	private static LibraryIndex.Sort sort = LibraryIndex.Sort.NAME;
	/** 0 shows everything, 1 favourites, 2 and up the n-th tag. */
	private static int filter;

	private final SkinRepository repository;
	private final ClientSettings settings = McSkinsClient.settings();
	private final AccountService account;
	private final SkinModelPreview preview = new SkinModelPreview();
	private final Map<String, int[]> capeIcons = new HashMap<>();
	private List<SkinDocument> documents = List.of();
	private List<int[]> previews = List.of();
	private Map<UUID, Long> modified = Map.of();
	private Map<UUID, Long> created = Map.of();
	/** Indices into {@link #documents} in display order, after search, filter and sorting. */
	private List<Integer> visible = List.of();
	private final Set<UUID> batch = new LinkedHashSet<>();
	private boolean comparePick;
	private final LibraryIndex library = McSkinsClient.library();
	private boolean documentsDirty = true;
	private long loadedModifications = Long.MIN_VALUE;
	private CompletableFuture<Loaded> loading;
	private CompletableFuture<?> fileTask;
	private AtomicBoolean capeCancel;
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
	private UUID selectedId;
	private UUID pendingDelete;
	private int scroll;
	private boolean scrollDrag;
	private int scrollGrab;
	private boolean previewDrag;
	private int listWidth = 250;
	private int previewX;
	private int previewWidth;
	private int previewHeight = 1;
	private int actionX;
	private int actionWidth = 1;
	private int actionBase;
	private int capeScroll;
	private EditBox name;
	private String renameDraft;

	/** A scanned library with its flattened previews, both made on the I/O thread. */
	private record Loaded(SkinRepository.Library library, List<int[]> previews, long modifications) {
	}

	public DressingRoomScreen(Screen parent, SkinRepository repository) {
		super("mcskins.dressing_room", parent);
		this.repository = repository;
		account = new AccountService(repository, settings::importAccountSkins);
	}

	@Override
	protected void init() {
		if (loading == null && fileTask == null && (documentsDirty || repository.modifications() != loadedModifications
			|| documents.stream().anyMatch(SkinDocument::dirty))) {
			// Rescan only if something was written or deleted, or an editor left unsaved changes in a document.
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
		layout();
		preview.viewport(previewX + 1, 65, previewWidth - 2, Math.max(1, previewHeight() - 2));
		button(tr("mcskins.back"), 10, 36, 60, 20, this::onClose);
		button(operation == null ? tr("mcskins.refresh") : tr("mcskins.loading"), tr("mcskins.refresh.tip"), 74, 36, 90, 20,
			() -> begin(account.refresh(minecraft.getUser().getAccessToken(), minecraft.getUser().getProfileId()))).active = operation == null;
		button("⚙", tr("mcskins.settings"), 168, 36, 20, 20, () -> GameCompat.setScreen(minecraft, new SettingsScreen(this)));
		button(tr("mcskins.import"), tr("mcskins.import.tip"), 192, 36, 70, 20, () -> GameCompat.setScreen(minecraft,
			new ImportScreen(this, repository, id -> {
				selectedId = id;
				selected = -1;
				documentsDirty = true;
			})));
		if (!batch.isEmpty()) {
			button(tr("mcskins.batch.export", batch.size()), tr("mcskins.batch.export.tip"), 266, 36, 96, 20, this::exportBatch).active = fileTask == null;
			button("✕", tr("mcskins.batch.clear"), 364, 36, 20, 20, () -> {
				batch.clear();
				rebuildWidgets();
			});
		}
		if (confirmation != null) {
			int x = (width - 240) / 2;
			button(tr("mcskins.account.confirm"), x, height / 2 + 10, 240, 20, this::confirm).active = operation == null;
			button(tr("mcskins.cancel"), x, height / 2 + 34, 240, 20, () -> { clearConfirmation(); rebuildWidgets(); });
			return;
		}
		int filterWidth = 34;
		EditBox searchBox = field(tr("mcskins.search"), search, 10, 62, Math.max(30, listWidth - 2 * (filterWidth + 2)), 64);
		searchBox.setResponder(value -> {
			search = value;
			applyFilter();
		});
		button(tr("mcskins.sort." + sort.name().toLowerCase(Locale.ROOT) + ".short"), tr("mcskins.sort.tip", tr("mcskins.sort." + sort.name().toLowerCase(Locale.ROOT))),
			10 + listWidth - 2 * filterWidth - 2, 62, filterWidth, 20, () -> {
				sort = LibraryIndex.Sort.values()[(sort.ordinal() + 1) % LibraryIndex.Sort.values().length];
				applyFilter();
				rebuildWidgets();
			});
		List<String> tags = library.allTags();
		if (filter > tags.size() + 1) {
			filter = 0;
		}
		String filterLabel = filter == 0 ? tr("mcskins.filter.all") : filter == 1 ? "★" : "#" + tags.get(filter - 2);
		selected(button(font.plainSubstrByWidth(filterLabel, filterWidth - 6), tr("mcskins.filter.tip", filterLabel), 10 + listWidth - filterWidth, 62, filterWidth, 20, () -> {
			filter = (filter + 1) % (library.allTags().size() + 2);
			applyFilter();
			rebuildWidgets();
		}), filter != 0);
		if (hasSkin()) {
			SkinDocument document = documents.get(selected);
			int actions = actionX;
			int base = actionBase;
			int actionRight = actionX + actionWidth;
			name = field(tr("mcskins.name"), renameDraft == null ? document.name() : renameDraft, actions, base + 6,
				Math.max(40, actionWidth - 86), 64);
			name.setResponder(value -> renameDraft = value);
			button(tr("mcskins.rename"), actionRight - 76, base + 6, 76, 20, () -> rename(document)).active = fileTask == null;
			int half = Math.max(1, (actionWidth - 4) / 2);
			button(tr("mcskins.edit"), actions, base + 30, half, 20, () ->
				GameCompat.setScreen(minecraft, new SkinEditorScreen(this, repository, document)));
			button(tr("mcskins.export"), tr("mcskins.export.tip"), actions + half + 4, base + 30, Math.max(1, actionWidth - half - 4), 20, () -> export(document));
			button(tr("mcskins.account.upload"), tr("mcskins.account.upload.tip"), actions, base + 54, half, 20, () -> {
				pendingSkin = new AccountService.Image(document.slim(), document.flatten());
				confirmation = tr("mcskins.account.upload.confirm", document.name(), tr(document.slim() ? "mcskins.slim" : "mcskins.classic"));
				operationPlayer = minecraft.getUser().getProfileId();
				rebuildWidgets();
			}).active = operation == null;
			boolean armed = document.id().equals(pendingDelete);
			button(tr(armed ? "mcskins.delete.confirm" : "mcskins.delete"), actions + half + 4, base + 54, Math.max(1, actionWidth - half - 4), 20,
				() -> delete(document)).active = fileTask == null;
			boolean favourite = library.entry(document.id()).favourite();
			selected(button(favourite ? "★" : "☆", tr("mcskins.favourite.tip"), actions, base + 78, 20, 20, () -> {
				library.favourite(document.id(), !favourite);
				McSkinsClient.saveLibrary();
				applyFilter();
				rebuildWidgets();
			}), favourite);
			int third = Math.max(1, (actionWidth - 24 - 8) / 3);
			button(tr("mcskins.tags"), tr("mcskins.tags.tip"), actions + 24, base + 78, third, 20, () -> editTags(document));
			button(tr("mcskins.snapshots"), tr("mcskins.snapshots.tip"), actions + 28 + third, base + 78, third, 20, () -> {
				if (fileTask == null) {
					GameCompat.setScreen(minecraft, new SnapshotScreen(this, repository, document));
				}
			});
			selected(button(tr("mcskins.compare"), tr("mcskins.compare.tip"), actions + 32 + 2 * third, base + 78, Math.max(1, actionWidth - 32 - 2 * third), 20, () -> {
				comparePick = !comparePick;
				status = comparePick ? tr("mcskins.compare.pick") : "";
				rebuildWidgets();
			}), comparePick).active = documents.size() > 1;
		}
		if (profile != null) {
			int base = actionBase;
			String worn = wornCape();
			boolean changes = previewCape == null ? worn != null : !previewCape.equals(worn);
			button(tr(previewCape == null ? "mcskins.cape.hide" : "mcskins.cape.wear"), actionX + actionWidth - 76, base + 102, 76, 20, () -> {
				pendingCape = previewCape;
				confirmation = tr(previewCape == null ? "mcskins.cape.hide.confirm" : "mcskins.cape.wear.confirm");
				operationPlayer = minecraft.getUser().getProfileId();
				rebuildWidgets();
			}).active = operation == null && changes;
		}
		updatePreview();
	}

	/**
	 * Splits the screen into the skin list, the preview and the actions. The list never gets wider on narrower
	 * windows; when the window is too low for the actions below a useful preview, they move beside the preview.
	 */
	private void layout() {
		listWidth = Math.clamp(width / 2 - 20, 110, 250);
		boolean beside = false;
		if (height - 64 - ACTIONS - 14 < 110) {
			// Room left for list + preview once the action column sits on the right; split it evenly.
			int room = width - 30 - 6 - ACTION_COLUMN;
			int narrower = Math.min(listWidth, Math.max(110, room / 2));
			if (room - narrower >= MIN_PREVIEW) {
				listWidth = narrower;
				beside = true;
			}
		}
		previewX = 20 + listWidth;
		if (beside) {
			actionWidth = ACTION_COLUMN;
			actionX = width - 10 - actionWidth;
			previewWidth = Math.max(1, actionX - 6 - previewX);
			previewHeight = Math.max(1, listBottom() - 64);
			actionBase = 58;
		} else {
			previewWidth = Math.max(1, width - 10 - previewX);
			previewHeight = Math.max(1, height - 64 - ACTIONS - 14);
			actionX = previewX;
			actionWidth = previewWidth;
			actionBase = 64 + previewHeight;
		}
		capeScroll = Math.clamp(capeScroll, 0, Math.max(0, capeCount() - visibleCapes()));
	}

	private int capeCount() {
		return profile == null ? 0 : profile.capes().size();
	}

	private boolean capesScroll() {
		return capeCount() > (actionWidth - SLOT) / SLOT;
	}

	/** How many cape icons fit next to the "no cape" slot (and the scroll arrows, if needed). */
	private int visibleCapes() {
		return capesScroll() ? Math.max(1, (actionWidth - SLOT - 2 * ARROW) / SLOT) : capeCount();
	}

	private int capeStripY() {
		return actionBase + 124;
	}

	private int firstCapeX() {
		return actionX + SLOT + (capesScroll() ? ARROW : 0);
	}

	private String wornCape() {
		return profile == null ? null
			: profile.capes().stream().filter(AccountProfile.Cape::active).map(AccountProfile.Cape::id).findFirst().orElse(null);
	}

	private void rename(SkinDocument document) {
		String value = name.getValue().strip();
		if (value.isEmpty()) {
			status = tr("mcskins.status.enter_name");
			return;
		}
		if (fileTask != null) {
			return;
		}
		String previousName = document.name();
		SkinRepository.PreparedSave prepared;
		try {
			document.rename(value);
			prepared = repository.prepare(document);
		} catch (IOException | RuntimeException exception) {
			document.rename(previousName);
			failure(exception);
			return;
		}
		status = tr("mcskins.status.saving");
		fileTask = CompletableFuture.supplyAsync(() -> {
			try {
				return repository.write(prepared);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((written, error) -> minecraft.execute(() -> {
			fileTask = null;
			if (error == null) {
				if (written) {
					document.markSaved(prepared.revision(), prepared.sequence());
				}
				pendingDelete = null;
				renameDraft = null;
				status = tr("mcskins.status.renamed");
				reloadDocuments();
			} else {
				document.rename(previousName);
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
			rebuildWidgets();
		}));
		rebuildWidgets();
	}

	private void export(SkinDocument document) {
		int[] flattened = document.flatten();
		UUID id = document.id();
		String skinName = document.name();
		CompletableFuture.supplyAsync(() -> {
			try {
				return repository.export(id, skinName, flattened);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((path, error) -> minecraft.execute(() -> {
			if (error == null) {
				status = tr("mcskins.status.exported", path.getFileName());
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
		}));
	}

	private void delete(SkinDocument document) {
		if (!document.id().equals(pendingDelete)) {
			pendingDelete = document.id();
			status = tr("mcskins.status.delete_armed", document.name());
			rebuildWidgets();
			return;
		}
		pendingDelete = null;
		if (fileTask != null) {
			return;
		}
		fileTask = CompletableFuture.runAsync(() -> {
			try {
				repository.delete(document);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((ignored, error) -> minecraft.execute(() -> {
			fileTask = null;
			if (error == null) {
				selectedId = null;
				renameDraft = null;
				library.remove(document.id());
				McSkinsClient.saveLibrary();
				status = tr("mcskins.status.deleted", document.name());
				reloadDocuments();
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
			rebuildWidgets();
		}));
		rebuildWidgets();
	}

	private void reloadDocuments() {
		if (selected >= 0 && selected < documents.size()) {
			selectedId = documents.get(selected).id();
		}
		documentsDirty = false;
		loading = CompletableFuture.supplyAsync(() -> {
			try {
				long modifications = repository.modifications();
				SkinRepository.Library library = repository.scan();
				// Flattened here: the freshly read documents are not shared with the render thread yet.
				List<int[]> flattened = library.documents().stream().map(SkinDocument::flatten).toList();
				return new Loaded(library, flattened, modifications);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io());
	}

	private void finishLoading() {
		try {
			Loaded loaded = loading.join();
			SkinRepository.Library library = loaded.library();
			documents = library.documents();
			previews = loaded.previews();
			modified = library.modified();
			created = library.created();
			loadedModifications = loaded.modifications();
			boolean indexChanged = false;
			for (SkinDocument document : documents) {
				indexChanged |= this.library.created(document.id(), created.getOrDefault(document.id(), 0L));
			}
			if (indexChanged) {
				McSkinsClient.saveLibrary();
			}
			var known = new java.util.HashSet<UUID>();
			documents.forEach(document -> known.add(document.id()));
			batch.retainAll(known);
			if (!library.quarantined().isEmpty()) {
				status = tr("mcskins.status.quarantined", library.quarantined().size());
			}
			if (!library.newer().isEmpty()) {
				status = tr("mcskins.status.newer_format", library.newer().size());
			}
		} catch (CompletionException exception) {
			documents = List.of();
			previews = List.of();
			failure(exception.getCause() instanceof Exception cause ? cause : exception);
		}
		loading = null;
		selected = -1;
		for (int index = 0; index < documents.size(); index++) {
			if (documents.get(index).id().equals(selectedId)) {
				selected = index;
			}
		}
		applyFilter();
		if (selected < 0) {
			renameDraft = null;
			selected = visible.isEmpty() ? documents.isEmpty() ? -1 : 0 : visible.getFirst();
		}
		rebuildWidgets();
	}

	/** Recomputes which skins the list shows, and in which order, from the search, filter and sort settings. */
	private void applyFilter() {
		var items = new ArrayList<LibraryIndex.Item>(documents.size());
		for (SkinDocument document : documents) {
			LibraryIndex.Entry entry = library.entry(document.id());
			long edited = modified.getOrDefault(document.id(), 0L);
			long born = entry.created() > 0 ? entry.created() : created.getOrDefault(document.id(), edited);
			items.add(new LibraryIndex.Item(document.id(), document.name(), born, edited, entry.favourite(), entry.tags()));
		}
		List<String> tags = library.allTags();
		String tag = filter >= 2 && filter - 2 < tags.size() ? tags.get(filter - 2) : null;
		List<LibraryIndex.Item> shown = LibraryIndex.query(items, search, filter == 1, tag, sort);
		var indices = new ArrayList<Integer>(shown.size());
		for (LibraryIndex.Item item : shown) {
			for (int index = 0; index < documents.size(); index++) {
				if (documents.get(index).id().equals(item.id())) {
					indices.add(index);
					break;
				}
			}
		}
		visible = List.copyOf(indices);
		scroll = Math.clamp(scroll, 0, Math.max(0, rows() * CARD - (listBottom() - listTop())));
	}

	private void editTags(SkinDocument document) {
		String current = String.join(", ", library.entry(document.id()).tags());
		GameCompat.setScreen(minecraft, new TextPromptScreen("mcskins.tags.title", this, current, "mcskins.tags.field", 240, value -> {
			library.tags(document.id(), LibraryIndex.parseTags(value));
			McSkinsClient.saveLibrary();
			filter = 0;
			applyFilter();
			return null;
		}));
	}

	/** Exports every skin selected with Ctrl+click to the exports folder. */
	private void exportBatch() {
		record Job(UUID id, String name, int[] pixels) {
		}
		var jobs = new ArrayList<Job>();
		for (SkinDocument document : documents) {
			if (batch.contains(document.id())) {
				jobs.add(new Job(document.id(), document.name(), document.flatten()));
			}
		}
		if (jobs.isEmpty() || fileTask != null) {
			return;
		}
		status = tr("mcskins.status.exporting");
		fileTask = CompletableFuture.supplyAsync(() -> {
			Path last = null;
			for (Job job : jobs) {
				try {
					last = repository.export(job.id(), job.name(), job.pixels());
				} catch (IOException exception) {
					throw new CompletionException(exception);
				}
			}
			return last;
		}, McSkinsClient.io()).whenComplete((path, error) -> minecraft.execute(() -> {
			fileTask = null;
			if (error == null) {
				status = tr("mcskins.batch.exported", jobs.size(), path == null ? "" : path.getParent());
				batch.clear();
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
			rebuildWidgets();
		}));
		rebuildWidgets();
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		Path png = paths.stream().filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).findFirst().orElse(null);
		if (png == null) {
			status = tr("mcskins.status.drop_png");
			return;
		}
		String fileName = png.getFileName().toString();
		String skinName = fileName.substring(0, fileName.length() - 4);
		if (skinName.isBlank() || skinName.length() > 64) {
			skinName = tr("mcskins.imported_skin");
		}
		String finalName = skinName;
		String layerName = tr("mcskins.layer.imported");
		status = tr("mcskins.status.importing", fileName);
		CompletableFuture.supplyAsync(() -> {
			try {
				byte[] bytes = SafeFiles.read(png, 1024 * 1024);
				AccountService.Image probe = AccountService.decode(bytes, false);
				boolean slim = probe.pixels()[20 * 64 + 54] >>> 24 == 0 && probe.pixels()[31 * 64 + 55] >>> 24 == 0;
				SkinDocument document = new SkinDocument(UUID.randomUUID(), finalName, slim,
					List.of(new SkinLayer(layerName, probe.pixels())), 0);
				repository.save(document);
				return document.id();
			} catch (IOException | RuntimeException exception) {
				throw new CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((id, error) -> minecraft.execute(() -> {
			if (error == null) {
				selectedId = id;
				selected = -1;
				status = tr("mcskins.status.imported_skin", finalName);
				reloadDocuments();
			} else {
				failure(error.getCause() instanceof Exception cause ? cause : new IOException(error));
			}
		}));
	}

	private int previewHeight() {
		return previewHeight;
	}

	private int listTop() {
		return 86;
	}

	private int listBottom() {
		return height - 18;
	}

	private int cardWidth() {
		return (listWidth - 8) / 2;
	}

	private int positions() {
		return visible.size() + 1;
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
		return selected >= 0 && selected < documents.size() && selected < previews.size();
	}

	private void confirm() {
		if (!minecraft.getUser().getProfileId().equals(operationPlayer)) {
			clearConfirmation();
			status = tr("mcskins.account.session_changed");
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
		cancelCapes();
		capeIcons.clear();
		status = tr("mcskins.account.running");
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
		if (loading != null && loading.isDone()) {
			finishLoading();
		}
		if (capeTask != null && capeTask.isDone()) {
			try {
				capeIcons.putAll(capeTask.join());
				if (previewCape == null) {
					previewCape = wornCape();
				}
			} catch (CompletionException exception) {
				Throwable cause = exception.getCause();
				status = cause instanceof IOException ? cause.getMessage() : tr("mcskins.cape.icons_failed");
			} finally {
				capeTask = null;
				updatePreview();
				rebuildWidgets();
			}
		}
		if (operation == null || !operation.isDone()) {
			return;
		}
		try {
			AccountService.Result result = operation.join();
			if (minecraft.getUser().getProfileId().equals(operationPlayer)) {
				profile = result.profile();
				activeSkin = result.active();
				status = result.message();
				loadCapeIcons();
			} else {
				status = tr("mcskins.account.session_changed");
			}
		} catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			status = cause instanceof IOException ? cause.getMessage() : tr("mcskins.account.failed");
			autoRefreshed = true;
		} finally {
			operation = null;
			// Imported account skins were written through the repository; only then is a rescan needed.
			if (loading == null && repository.modifications() != loadedModifications) {
				reloadDocuments();
			}
			rebuildWidgets();
		}
	}

	/**
	 * Loads cape icons on the network thread (never the disk thread, so saving and export do not wait behind slow
	 * downloads). Each icon shows up as soon as it is loaded; leaving the screen or a new account operation cancels
	 * the remaining downloads.
	 */
	private void loadCapeIcons() {
		cancelCapes();
		capeIcons.clear();
		capeScroll = 0;
		if (profile == null || profile.capes().isEmpty()) {
			return;
		}
		var capes = List.copyOf(profile.capes());
		var cancelled = new AtomicBoolean();
		capeCancel = cancelled;
		capeTask = CompletableFuture.supplyAsync(() -> {
			var loaded = new HashMap<String, int[]>();
			String failure = null;
			for (var cape : capes) {
				if (cancelled.get()) {
					break;
				}
				try {
					int[] icon = CapeCache.load(cape.texture());
					loaded.put(cape.id(), icon);
					minecraft.execute(() -> {
						if (!cancelled.get()) {
							capeIcons.put(cape.id(), icon);
							updatePreview();
						}
					});
				} catch (IOException exception) {
					failure = exception.getMessage();
				}
			}
			if (loaded.isEmpty() && failure != null && !cancelled.get()) {
				throw new CompletionException(new IOException(failure));
			}
			return Map.copyOf(loaded);
		}, McSkinsClient.network());
	}

	private void cancelCapes() {
		if (capeCancel != null) {
			capeCancel.set(true);
			capeCancel = null;
		}
		capeTask = null;
	}

	private boolean onPreview(double x, double y) {
		return x >= previewX && y >= 64 && x < previewX + previewWidth && y < 64 + previewHeight();
	}

	private boolean onList(double x, double y) {
		return x >= 10 && y >= listTop() && x < 10 + listWidth && y < listBottom();
	}

	private static final int SLOT_LEFT = -2;
	private static final int SLOT_RIGHT = -3;

	/** -1 nothing, 0 the "no cape" slot, n the n-th cape, or {@link #SLOT_LEFT}/{@link #SLOT_RIGHT} scroll arrows. */
	private int capeSlot(double x, double y) {
		int top = capeStripY();
		if (profile == null || y < top || y >= top + 34 || x < actionX || x >= actionX + actionWidth) {
			return -1;
		}
		if (x < actionX + 24) {
			return 0;
		}
		int first = firstCapeX();
		int visible = visibleCapes();
		if (capesScroll()) {
			if (x >= actionX + SLOT && x < first) {
				return SLOT_LEFT;
			}
			if (x >= first + visible * SLOT && x < first + visible * SLOT + ARROW) {
				return SLOT_RIGHT;
			}
		}
		if (x < first) {
			return -1;
		}
		int column = (int) ((x - first) / SLOT);
		if (column >= visible || x >= first + column * SLOT + 22) {
			return -1;
		}
		int index = capeScroll + column;
		return index < capeCount() ? index + 1 : -1;
	}

	private void scrollCapes(int delta) {
		capeScroll = Math.clamp(capeScroll + delta, 0, Math.max(0, capeCount() - visibleCapes()));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (confirmation != null || event.button() != InputCompat.MOUSE_BUTTON_LEFT) {
			return false;
		}
		clearFocus();
		if (onPreview(event.x(), event.y())) {
			previewDrag = true;
			return true;
		}
		int slot = capeSlot(event.x(), event.y());
		if (slot == SLOT_LEFT || slot == SLOT_RIGHT) {
			scrollCapes(slot == SLOT_LEFT ? -1 : 1);
			return true;
		}
		if (slot >= 0) {
			if (slot == 0) {
				previewCape = null;
			} else {
				String id = profile.capes().get(slot - 1).id();
				previewCape = id.equals(previewCape) ? null : id;
			}
			updatePreview();
			rebuildWidgets();
			return true;
		}
		if (onList(event.x(), event.y())) {
			int track = 10 + listWidth - 6;
			if (event.x() >= track && rows() * CARD > listBottom() - listTop()) {
				scrollDrag = true;
				scrollGrab = (int) event.y() - thumbTop();
				return true;
			}
			int column = (int) ((event.x() - 12) / cardWidth());
			int position = ((int) (event.y() - listTop()) + scroll) / CARD * 2 + Math.min(1, column);
			if (position == 0) {
				GameCompat.setScreen(minecraft, new CreateSkinScreen(this, repository));
				return true;
			}
			int index = position - 1 >= 0 && position - 1 < visible.size() ? visible.get(position - 1) : -1;
			if (index >= 0 && event.hasControlDown()) {
				UUID id = documents.get(index).id();
				if (!batch.remove(id)) {
					batch.add(id);
				}
				rebuildWidgets();
				return true;
			}
			if (index >= 0 && comparePick) {
				comparePick = false;
				status = "";
				if (hasSkin() && index != selected) {
					GameCompat.setScreen(minecraft, new CompareScreen(this, documents.get(selected), documents.get(index)));
				} else {
					rebuildWidgets();
				}
				return true;
			}
			if (index >= 0 && index < documents.size()) {
				if (selected != index) {
					pendingDelete = null;
					renameDraft = null;
				}
				selected = index;
				if (doubleClick) {
					GameCompat.setScreen(minecraft, new SkinEditorScreen(this, repository, documents.get(index)));
					return true;
				}
				updatePreview();
				rebuildWidgets();
			}
			return true;
		}
		return false;
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
			scroll = Math.clamp((int) (scroll - vertical * CARD / 2), 0, Math.max(0, rows() * CARD - (listBottom() - listTop())));
			return true;
		}
		if (onPreview(x, y)) {
			preview.zoom(vertical);
			return true;
		}
		if (capeSlot(x, y) != -1 || profile != null && y >= capeStripY() && y < capeStripY() + 34
			&& x >= actionX && x < actionX + actionWidth) {
			double amount = vertical != 0 ? vertical : horizontal;
			scrollCapes(amount > 0 ? -1 : amount < 0 ? 1 : 0);
			return true;
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	@Override
	public void removed() {
		renameDraft = null;
		preview.close();
		settings.flush();
		super.removed();
	}

	@Override
	public void onClose() {
		cancelCapes();
		super.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (confirmation != null) {
			int boxWidth = Math.min(320, width - 20);
			caption(graphics, (width - boxWidth) / 2, height / 2 - 60, boxWidth, tr("mcskins.account.change"));
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			centered(graphics, confirmation, width / 2, height / 2 - 40, 0xFFFFFFFF, boxWidth - 16);
			centered(graphics, tr("mcskins.account.warning"), width / 2, height / 2 - 26, WARNING, boxWidth - 16);
			centered(graphics, tr("mcskins.account.name", minecraft.getUser().getName()), width / 2, height / 2 - 12, MUTED, boxWidth - 16);
			return;
		}
		graphics.enableScissor(previewX, 64, previewX + previewWidth, 64 + previewHeight());
		if (hasSkin()) {
			preview.render(graphics);
		}
		graphics.disableScissor();
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		text(graphics, tr("mcskins.dressing_room.hint"), previewX + 5, 69, MUTED, previewWidth - 10);
		if (hasSkin()) {
			SkinDocument document = documents.get(selected);
			String info = tr(document.slim() ? "mcskins.slim" : "mcskins.classic") + " · " + tr("mcskins.layers.count", document.layers().size());
			text(graphics, info, previewX + 5, 64 + previewHeight() - 12, MUTED, previewWidth - 10);
		}
		drawList(graphics, mouseX, mouseY);
		drawCapes(graphics);
		if (loading != null && documents.isEmpty()) {
			text(graphics, tr("mcskins.loading"), 16, 140, MUTED, listWidth - 8);
		} else if (documents.isEmpty()) {
			text(graphics, tr("mcskins.dressing_room.empty"), 16, 140, MUTED, listWidth - 8);
		} else if (visible.isEmpty()) {
			text(graphics, tr("mcskins.search.none"), 16, 160, MUTED, listWidth - 8);
		}
		if (!batch.isEmpty() && status.isEmpty()) {
			text(graphics, tr("mcskins.batch.hint"), 10, height - 13, MUTED, width - 20);
		}
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
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
			int cardX = 12 + position % 2 * cardWidth();
			boolean hovered = mouseX >= cardX && mouseX < cardX + cardWidth() && mouseY >= cardY && mouseY < cardY + CARD
				&& mouseY >= top && mouseY < bottom;
			graphics.fill(cardX + 2, cardY + 2, cardX + cardWidth() - 2, cardY + CARD - 2, hovered ? 0x60000000 : 0x40000000);
			if (hovered) {
				graphics.outline(cardX + 2, cardY + 2, cardWidth() - 4, CARD - 4, 0xFF808080);
			}
			if (position == 0) {
				int centerX = cardX + cardWidth() / 2;
				int centerY = cardY + 24;
				graphics.fill(centerX - 2, centerY - 12, centerX + 2, centerY + 12, 0xFFFFFFFF);
				graphics.fill(centerX - 12, centerY - 2, centerX + 12, centerY + 2, 0xFFFFFFFF);
				centered(graphics, tr("mcskins.new_skin"), centerX, cardY + 48, 0xFFFFFFFF, cardWidth() - 8);
				continue;
			}
			int index = this.visible.get(position - 1);
			SkinPixels.head(graphics, previews.get(index), cardX + cardWidth() / 2 - 20, cardY + 5, 5);
			if (library.entry(documents.get(index).id()).favourite()) {
				text(graphics, "★", cardX + 6, cardY + 5, 0xFFFFD700, 10);
			}
			if (batch.contains(documents.get(index).id())) {
				graphics.outline(cardX + 3, cardY + 3, cardWidth() - 6, CARD - 6, 0xFF6FB7FF);
				text(graphics, "✔", cardX + 6, cardY + 16, 0xFF6FB7FF, 10);
			}
			centered(graphics, documents.get(index).name(), cardX + cardWidth() / 2, cardY + 49, 0xFFFFFFFF, cardWidth() - 8);
			if (activeSkin != null && activeSkin.matches(documents.get(index).slim(), previews.get(index))) {
				graphics.fill(cardX + cardWidth() - 12, cardY + 5, cardX + cardWidth() - 6, cardY + 11, SUCCESS);
			}
			if (index == selected) {
				graphics.outline(cardX + 2, cardY + 2, cardWidth() - 4, CARD - 4, ACCENT);
			}
		}
		graphics.disableScissor();
		if (rows() * CARD > visible) {
			int track = 10 + listWidth - 6;
			graphics.fill(track, top, track + 4, bottom, 0x80000000);
			int thumb = Math.max(12, (visible - 8) * visible / Math.max(1, rows() * CARD));
			graphics.fill(track, thumbTop(), track + 4, thumbTop() + thumb, 0xFFC0C0C0);
		}
	}

	private void drawCapes(GuiGraphicsExtractor graphics) {
		int base = actionBase;
		int label = Math.max(1, actionWidth - 80);
		text(graphics, tr("mcskins.capes"), actionX, base + 108, MUTED, label);
		int top = capeStripY();
		if (profile == null) {
			text(graphics, tr(operation != null ? "mcskins.loading" : "mcskins.capes.refresh"), actionX, top + 12, MUTED, actionWidth);
			return;
		}
		int x = actionX;
		graphics.fill(x, top, x + 24, top + 34, 0x40000000);
		centered(graphics, "✕", x + 12, top + 12, 0xFFFFFFFF, 20);
		if (previewCape == null) {
			graphics.outline(x, top, 24, 34, ACCENT);
		}
		String worn = wornCape();
		int first = firstCapeX();
		int visible = visibleCapes();
		var capes = profile.capes();
		if (capesScroll()) {
			int dim = 0xFF6F7780;
			centered(graphics, "‹", actionX + SLOT + ARROW / 2 - 1, top + 12, capeScroll > 0 ? 0xFFFFFFFF : dim, ARROW);
			centered(graphics, "›", first + visible * SLOT + ARROW / 2 - 1, top + 12,
				capeScroll + visible < capes.size() ? 0xFFFFFFFF : dim, ARROW);
		}
		for (int column = 0; column < visible && capeScroll + column < capes.size(); column++) {
			var cape = capes.get(capeScroll + column);
			int capeX = first + column * SLOT;
			int[] icon = capeIcons.get(cape.id());
			if (icon != null) {
				SkinPixels.cape(graphics, icon, capeX, top + 1, 2);
			} else {
				graphics.fill(capeX, top + 1, capeX + 20, top + 33, 0x40000000);
			}
			if (cape.id().equals(worn)) {
				graphics.fill(capeX + 15, top + 2, capeX + 19, top + 6, SUCCESS);
			}
			if (cape.id().equals(previewCape)) {
				graphics.outline(capeX - 2, top - 1, 24, 36, ACCENT);
			}
		}
		if (previewCape != null && !previewCape.equals(worn)) {
			text(graphics, tr("mcskins.cape.previewing"), actionX, top + 38, MUTED, actionWidth);
		}
	}

	/** Preferences that are not part of a single skin. */
	private static final class SettingsScreen extends SkinScreen {
		SettingsScreen(Screen parent) {
			super("mcskins.settings", parent);
		}

		@Override
		protected void init() {
			ClientSettings settings = McSkinsClient.settings();
			int x = (width - 260) / 2;
			int y = height / 2 - 40;
			button(tr("mcskins.settings.menu_spin", onOff(settings.menuSpin())), x, y, 260, 20, () -> {
				settings.menuSpin(!settings.menuSpin());
				rebuildWidgets();
			});
			button(tr("mcskins.settings.import", onOff(settings.importAccountSkins())), tr("mcskins.settings.import.tip"), x, y + 24, 260, 20, () -> {
				settings.importAccountSkins(!settings.importAccountSkins());
				rebuildWidgets();
			});
			button(tr("mcskins.settings.open_folder"), x, y + 48, 260, 20, () -> GameCompat.openPath(McSkinsClient.repository().root()));
			button(tr("mcskins.done"), x, y + 80, 260, 20, this::onClose);
		}

		private static String onOff(boolean value) {
			return tr(value ? "options.on" : "options.off");
		}
	}
}
