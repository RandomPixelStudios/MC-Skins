package de.catsmc.mcskins.client.mixin;

import de.catsmc.mcskins.client.preview.PreviewSession;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class PreviewPlayerSkinMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void mcskins$previewSkin(CallbackInfoReturnable<PlayerSkin> callback) {
		if (PreviewSession.isPreviewPlayer(this)) {
			callback.setReturnValue(PreviewSession.skin(this, callback.getReturnValue()));
		}
	}
}
