package dev.spacebod.aetherium.client.mixin;

import dev.spacebod.aetherium.client.VulkanPrompt;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Checks the graphics API once the title screen is up ({@link VulkanPrompt}). */
@Mixin(Minecraft.class)
abstract class VulkanPromptMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void aetherium$vulkanPrompt(CallbackInfo ci) {
		VulkanPrompt.tick((Minecraft) (Object) this);
	}
}
