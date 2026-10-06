package dev.spacebod.aetherium.client.mixin.access;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Runs single feature phases of a prepared frame (the shader engine splits the hand pass around the deferred passes). */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface PreparedFrameAccessor {
	@Accessor("context")
	FeatureFrameContext aetherium$context();

	@Accessor("submitNodeStorage")
	SubmitNodeStorage aetherium$submitNodeStorage();

	@Invoker("executePhase")
	void aetherium$executePhase(FeatureRenderPhase<?> phase, FeatureFrameContext context, RenderPass renderPass);
}
