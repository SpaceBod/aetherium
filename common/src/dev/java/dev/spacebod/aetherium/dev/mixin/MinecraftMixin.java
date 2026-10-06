package dev.spacebod.aetherium.dev.mixin;

import dev.spacebod.aetherium.dev.bench.BenchDriver;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame and tick hooks for the benchmark driver. Each is a null check when no benchmark is running. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/** Right after vanilla's own frame-start timestamp and before extraction, so the camera pose applies to this frame. */
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseIfInactive()V"))
	private void aetherium$frameBegin(boolean advanceGameTime, CallbackInfo ci) {
		BenchDriver driver = BenchDriver.active();
		if (driver != null) {
			driver.frameBegin((Minecraft) (Object) this);
		}
	}

	/** After the swapchain blit, before the frame's command submission (where vanilla's GPU timer query ends). */
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
	private void aetherium$beforeSubmit(boolean advanceGameTime, CallbackInfo ci) {
		BenchDriver driver = BenchDriver.active();
		if (driver != null) {
			driver.beforeSubmit();
		}
	}

	/** After present and the frame limiter: the frame interval is measured end to end, like vanilla's FPS. */
	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void aetherium$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		BenchDriver driver = BenchDriver.active();
		if (driver != null) {
			driver.frameEnd((Minecraft) (Object) this);
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void aetherium$tick(CallbackInfo ci) {
		BenchDriver driver = BenchDriver.active();
		if (driver != null) {
			driver.clientTick((Minecraft) (Object) this);
		}
	}
}
