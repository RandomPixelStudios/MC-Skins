package de.catsmc.mcskins.client.mixin;

import de.catsmc.mcskins.client.preview.PreviewSession;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class PreviewCameraMixin {
	@Shadow
	public abstract Entity entity();

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void mcskins$orbitCamera(float partialTick, CallbackInfo callback) {
		PreviewSession.CameraPose pose = PreviewSession.camera(entity(), partialTick);
		if (pose != null) {
			setRotation(pose.yaw(), pose.pitch());
			setPosition(pose.x(), pose.y(), pose.z());
		}
	}
}
