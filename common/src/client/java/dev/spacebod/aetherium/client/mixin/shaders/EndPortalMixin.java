package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.spacebod.aetherium.shaders.engine.ShaderPackEngine;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aetherium Shaders: while a pack draws the world, end portals and gateways are plain entity-solid cubes with the portal
 * texture (scrolling slowly), the portal's tint and full brightness, as the pack format expects. Vanilla's portal
 * shader reads screen-space layers from a position-only vertex; pack programs would see default UV, normal and colour.
 * They draw as block-entity geometry ({@code gbuffers_block}).
 */
@Mixin(AbstractEndPortalRenderer.class)
abstract class EndPortalMixin {
	/** Vanilla's corners per face (its winding). */
	@Shadow
	@Final
	private static Map<Direction, List<Vector3fc>> FACES;
	/**
	 * The portal's tint, 1.5 times the usual (0.075, 0.15, 0.2): the star field is black with pale specks, so only the
	 * specks brighten, a faint glow (packs with bloom pick it up), drawn at full brightness.
	 */
	@Unique
	private static final float RED = 0.1125F;
	@Unique
	private static final float GREEN = 0.225F;
	@Unique
	private static final float BLUE = 0.3F;
	/**
	 * Texture coordinates of each face's four corners, in vanilla's winding: a tenth of the 256-texel star field per face.
	 * A fifth (the usual span) leaves the specks too small to read; a tenth doubles them.
	 */
	@Unique
	private static final float[] CORNER_U = {0.0F, 0.0F, 0.1F, 0.1F};
	@Unique
	private static final float[] CORNER_V = {0.0F, 0.1F, 0.1F, 0.0F};

	@Inject(method = "submitCube(Ljava/util/Collection;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
			at = @At("HEAD"), cancellable = true)
	private static void aetherium$packPortal(Collection<Direction> facesToShow, RenderType renderType, PoseStack poseStack, SubmitNodeCollector collector,
			CallbackInfo ci) {
		if (!ShaderPackEngine.get().worldActive() || renderType != RenderTypes.endPortal() && renderType != RenderTypes.endGateway()) {
			return;
		}
		ci.cancel();
		if (facesToShow.isEmpty()) {
			return;
		}
		float progress = SystemTimeUniforms.TIMER.getFrameTimeCounter() * 0.01F % 1.0F;
		collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(AbstractEndPortalRenderer.END_PORTAL_LOCATION), (pose, buffer) -> {
			for (Direction face : facesToShow) {
				float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
				List<Vector3fc> corners = FACES.get(face);
				for (int i = 0; i < 4; i++) {
					// Scrolled; the texture repeats.
					buffer.addVertex(pose, corners.get(i))
							.setColor(RED, GREEN, BLUE, 1.0F)
							.setUv(CORNER_U[i] + progress, CORNER_V[i] + progress)
							.setOverlay(OverlayTexture.NO_OVERLAY)
							.setLight(0xF000F0)
							.setNormal(pose, nx, ny, nz);
				}
			}
		});
	}
}
