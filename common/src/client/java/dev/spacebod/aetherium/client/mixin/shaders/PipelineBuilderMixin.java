package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.renderpearl.util.ShaderCompileException;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.engine.StorageBindings;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aetherium Shaders in vanilla's pipeline builder:
 * <ul>
 *   <li>Vanilla moves every descriptor a shader declares into its per-pipeline set 0 and fails on names its bind groups
 *       do not list. A pack's custom images and storage buffers are declared in set 1 (the shared storage set, see
 *       {@code StorageBindings}) and are left where they are.</li>
 *   <li>A pack program's geometry stage ({@link GeometryStages}) is compiled with vanilla's compiler and joins the other
 *       stages, so its descriptors are numbered with theirs; the fragment inputs are matched against its outputs instead
 *       of the vertex stage's.</li>
 * </ul>
 */
@Mixin(PipelineBuilder.class)
abstract class PipelineBuilderMixin {
	@Shadow
	@Final
	private GlslCompiler compiler;

	@Unique
	private static final ThreadLocal<SpvModule> AETHERIUM$GEOMETRY = new ThreadLocal<>();

	@ModifyExpressionValue(method = "generateBackendCreateInfo",
			at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/api/SpvModule$Reflection;descriptors()Ljava/util/List;"))
	private List<SpvModule.Reflection.Descriptor> aetherium$skipStorageSet(List<SpvModule.Reflection.Descriptor> descriptors) {
		for (SpvModule.Reflection.Descriptor d : descriptors) {
			if (d.descriptorSetIndex() == StorageBindings.SET) {
				return descriptors.stream().filter(x -> x.descriptorSetIndex() != StorageBindings.SET).toList();
			}
		}
		return descriptors;
	}

	@Inject(method = "generateBackendCreateInfo", at = @At("HEAD"), cancellable = true)
	private void aetherium$geometryStage(RenderPipeline pipeline, ShaderSource shaderSource,
			ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaderCreateInfos, Object2IntOpenHashMap<String> uniformBindings,
			CallbackInfoReturnable<BackendRenderPipeline.CreateInfo> cir) {
		AETHERIUM$GEOMETRY.remove();
		String source = GeometryStages.source(pipeline.getLocation());
		if (source == null) {
			return;
		}
		String name = pipeline.getLocation() + " (geometry)";
		try {
			SpvModule module = GeometryStages.compileStage(() -> compiler.compileToSpv(name, source, ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource));
			shaderCreateInfos.add(new BackendRenderPipeline.CreateInfo.Shader(name, "main", module));
			AETHERIUM$GEOMETRY.set(module);
		} catch (ShaderCompileException e) {
			AetheriumShaders.logger.error("Couldn't compile pipeline ({}): ", pipeline.getLocation(), e);
			cir.setReturnValue(null);
		}
	}

	/** The interface the fragment stage reads: the geometry stage's outputs when there is one. */
	@ModifyArg(method = "generateBackendCreateInfo", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/frontend/shaders/PipelineBuilder;generateSlotMap(Ljava/util/List;Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;)Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;",
			ordinal = 0), index = 0)
	private List<SpvModule.Reflection.InterfaceVariable> aetherium$geometryOutputs(List<SpvModule.Reflection.InterfaceVariable> vertexOutputs) {
		SpvModule geometry = AETHERIUM$GEOMETRY.get();
		if (geometry == null) {
			return vertexOutputs;
		}
		try {
			return geometry.reflect().outputs();
		} catch (ShaderCompileException e) {
			return vertexOutputs;
		}
	}

	@Inject(method = "generateBackendCreateInfo", at = @At("RETURN"))
	private void aetherium$geometryDone(RenderPipeline pipeline, ShaderSource shaderSource,
			ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaderCreateInfos, Object2IntOpenHashMap<String> uniformBindings,
			CallbackInfoReturnable<BackendRenderPipeline.CreateInfo> cir) {
		AETHERIUM$GEOMETRY.remove();
	}
}
