package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.LayerGroup;
import de.catsmc.mcskins.skin.SkinDocument;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Layer group actions: group layers, join or leave a group, and a group's name, visibility and opacity. */
final class LayerGroupScreen extends SkinScreen {
	private final SkinEditorScreen editor;
	private final int requestedGroup;

	LayerGroupScreen(SkinEditorScreen editor) {
		this(editor, 0);
	}

	LayerGroupScreen(SkinEditorScreen editor, int group) {
		super("mcskins.group.title", editor);
		this.editor = editor;
		this.requestedGroup = group;
	}

	private int group() {
		SkinDocument document = editor.document();
		if (requestedGroup != 0 && document.group(requestedGroup) != null) {
			return requestedGroup;
		}
		return document.layers().get(document.activeLayer()).group();
	}

	@Override
	protected void init() {
		SkinDocument document = editor.document();
		int buttonWidth = Math.min(260, width - 40);
		int x = (width - buttonWidth) / 2;
		int y = Math.max(40, height / 2 - 96);
		int activeGroup = document.layers().get(document.activeLayer()).group();
		int index = document.activeLayer();
		boolean belowGrouped = index > 0 && document.layers().get(index - 1).group() != 0
			&& document.layers().get(index - 1).group() != activeGroup;
		button(tr("mcskins.group.create"), tr("mcskins.group.create.tip"), x, y, buttonWidth, 20, () -> {
			editor.change("mcskins.history.group", () -> document.createGroup(tr("mcskins.group.default_name", document.groups().size() + 1)));
			rebuildWidgets();
		}).active = document.groups().size() < LayerGroup.MAX_GROUPS;
		button(tr("mcskins.group.join_below"), tr("mcskins.group.join_below.tip"), x, y + 24, buttonWidth, 20, () -> {
			editor.change("mcskins.history.group", document::joinGroupBelow);
			rebuildWidgets();
		}).active = belowGrouped;
		button(tr("mcskins.group.leave"), x, y + 48, buttonWidth, 20, () -> {
			editor.change("mcskins.history.group", () -> document.setLayerGroup(document.activeLayer(), 0));
			rebuildWidgets();
		}).active = activeGroup != 0;
		int group = group();
		LayerGroup info = group == 0 ? null : document.group(group);
		int groupY = y + 84;
		if (info != null) {
			int half = buttonWidth / 2 - 2;
			button(tr("mcskins.group.rename"), x, groupY, half, 20, () -> GameCompat.setScreen(minecraft,
				new TextPromptScreen("mcskins.group.rename.title", this, info.name(), value -> {
					try {
						document.label(tr("mcskins.history.group"));
						document.renameGroup(group, value);
						editor.refresh();
						return null;
					} catch (RuntimeException exception) {
						return exception.getMessage();
					}
				})));
			selected(button(tr(info.visible() ? "mcskins.group.shown" : "mcskins.group.hidden"), x + half + 4, groupY, half, 20, () -> {
				editor.change("mcskins.history.group_visibility", () -> document.setGroupVisible(group, !info.visible()));
				rebuildWidgets();
			}), info.visible());
			button("−", tr("mcskins.group.opacity.tip"), x, groupY + 24, 20, 20, () -> opacity(group, info.opacity() - 26)).active = info.opacity() > 0;
			button("+", tr("mcskins.group.opacity.tip"), x + buttonWidth - 20, groupY + 24, 20, 20, () -> opacity(group, info.opacity() + 26)).active = info.opacity() < 255;
			button(tr("mcskins.group.dissolve"), tr("mcskins.group.dissolve.tip"), x, groupY + 48, buttonWidth, 20, () -> {
				editor.change("mcskins.history.group", () -> document.dissolveGroup(group));
				rebuildWidgets();
			});
		}
		button(tr("mcskins.back"), x, groupY + 80, buttonWidth, 20, this::onClose);
	}

	private void opacity(int group, int value) {
		editor.change("mcskins.history.group_opacity", () -> editor.document().setGroupOpacity(group, value));
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		SkinDocument document = editor.document();
		int y = Math.max(40, height / 2 - 96);
		centered(graphics, tr("mcskins.group.layer", document.layers().get(document.activeLayer()).name()), width / 2, y - 12, MUTED, width - 20);
		int group = group();
		LayerGroup info = group == 0 ? null : document.group(group);
		if (info != null) {
			centered(graphics, tr("mcskins.group.current", info.name()), width / 2, y + 72, 0xFFFFE6A0, width - 20);
			centered(graphics, tr("mcskins.group.opacity", Math.round(info.opacity() * 100 / 255.0)), width / 2, y + 114, 0xFFFFFFFF, 200);
		}
	}
}
