package dev.spacebod.aetherium.client.mixin.access;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The backend pass behind vanilla's render pass (the shader engine sets a viewport on it for scaled passes). */
@Mixin(FrontendRenderPass.class)
public interface FrontendRenderPassAccessor {
	@Accessor("backend")
	RenderPassBackend aetherium$backend();
}
