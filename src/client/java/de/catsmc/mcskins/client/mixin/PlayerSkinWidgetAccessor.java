package de.catsmc.mcskins.client.mixin;

import net.minecraft.client.gui.components.PlayerSkinWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerSkinWidget.class)
public interface PlayerSkinWidgetAccessor {
	@Accessor("rotationY")
	float mcskins$getRotationY();

	@Accessor("rotationY")
	void mcskins$setRotationY(float rotationY);
}
