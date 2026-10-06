package dev.spacebod.aetherium.client.mixin.access;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The four textures inside a render target, so the render scale can hand the game's main target a smaller set for the
 * world phase and put the window-sized set back before the interface.
 */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTexture")
	@Nullable GpuTexture aetherium$colorTexture();

	@Accessor("colorTexture")
	void aetherium$setColorTexture(@Nullable GpuTexture texture);

	@Accessor("colorTextureView")
	@Nullable GpuTextureView aetherium$colorTextureView();

	@Accessor("colorTextureView")
	void aetherium$setColorTextureView(@Nullable GpuTextureView view);

	@Accessor("depthTexture")
	@Nullable GpuTexture aetherium$depthTexture();

	@Accessor("depthTexture")
	void aetherium$setDepthTexture(@Nullable GpuTexture texture);

	@Accessor("depthTextureView")
	@Nullable GpuTextureView aetherium$depthTextureView();

	@Accessor("depthTextureView")
	void aetherium$setDepthTextureView(@Nullable GpuTextureView view);
}
