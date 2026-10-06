package dev.spacebod.aetherium.client.mixin.access;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Draws the first-person hand from inside the level (the shader engine draws its solid part before the deferred passes),
 * and reads the game's block-outline rule.
 */
@Mixin(GameRenderer.class)
public interface GameRendererHandAccessor {
	@Accessor("hudProjection")
	Projection aetherium$hudProjection();

	@Accessor("hud3dProjectionMatrixBuffer")
	ProjectionMatrixBuffer aetherium$hud3dProjectionMatrixBuffer();

	@Invoker("renderItemInHand")
	void aetherium$renderItemInHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView);

	/** Vanilla's rule for drawing the block outline (hidden HUD, spectator, adventure mode): {@code currentSelectedBlock*}. */
	@Invoker("shouldRenderBlockOutline")
	boolean aetherium$shouldRenderBlockOutline();
}
