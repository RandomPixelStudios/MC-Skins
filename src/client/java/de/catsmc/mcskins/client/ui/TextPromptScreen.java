package de.catsmc.mcskins.client.ui;

import de.catsmc.mcskins.client.compat.InputCompat;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/** A one-field dialog; the callback returns null on success or an error message to show. */
final class TextPromptScreen extends SkinScreen {
	private final Function<String, String> submit;
	private final String labelKey;
	private final int maxLength;
	private String value;
	private EditBox field;

	TextPromptScreen(String titleKey, Screen parent, String value, Function<String, String> submit) {
		this(titleKey, parent, value, "mcskins.name", 64, submit);
	}

	TextPromptScreen(String titleKey, Screen parent, String value, String labelKey, int maxLength, Function<String, String> submit) {
		super(titleKey, parent);
		this.value = value;
		this.submit = submit;
		this.labelKey = labelKey;
		this.maxLength = maxLength;
	}

	@Override
	protected void init() {
		int fieldWidth = Math.min(260, width - 40);
		int x = (width - fieldWidth) / 2;
		int y = height / 2 - 20;
		field = field(tr(labelKey), value, x, y, fieldWidth, maxLength);
		field.setResponder(text -> value = text);
		button(tr("mcskins.ok"), x, y + 28, fieldWidth / 2 - 2, this::confirm);
		button(tr("mcskins.cancel"), x + fieldWidth / 2 + 2, y + 28, fieldWidth / 2 - 2, this::onClose);
		setInitialFocus(field);
	}

	private void confirm() {
		String error = submit.apply(value);
		if (error == null) {
			onClose();
		} else {
			status = error;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputCompat.KEY_ENTER || event.key() == InputCompat.KEY_KP_ENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected boolean showTitle() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractBackground(graphics, mouseX, mouseY, delta);
		centered(graphics, title.getString(), width / 2, height / 2 - 42, 0xFFFFFFFF, width - 20);
	}
}
