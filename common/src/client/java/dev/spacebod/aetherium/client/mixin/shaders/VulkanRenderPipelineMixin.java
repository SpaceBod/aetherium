package dev.spacebod.aetherium.client.mixin.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.spacebod.aetherium.client.gpu.AllocatedSets;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.shaders.engine.StorageSet;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pack pipelines ({@code aetherium_shaders:} ids) built while the active pack has custom images or storage buffers get
 * the pack's storage set layout as set 1, after vanilla's push-descriptor set 0; their pipeline layouts are
 * registered so binding them also binds the storage set ({@code VulkanRenderPassMixin}). A pack program's geometry
 * stage ({@link GeometryStages.Module}) is created as one, and the pipeline's set 0 is visible to it. On MoltenVK a pack
 * pipeline reading more samplers than can be pushed gets an ordinary set 0, allocated at each draw ({@link AllocatedSets}).
 */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanRenderPipelineMixin implements AllocatedSets.Holder {
	/** Set 0's layout when it is allocated rather than pushed; 0 = pushed. */
	@Unique
	private long aetherium$allocatedSet;
	@Unique
	private static final ThreadLocal<Boolean> AETHERIUM$ALLOCATED = ThreadLocal.withInitial(() -> false);
	@Unique
	private static final ThreadLocal<Long> AETHERIUM$SET0 = new ThreadLocal<>();

	@Override
	public long aetherium$allocatedSetLayout() {
		return aetherium$allocatedSet;
	}

	@Override
	public void aetherium$allocatedSetLayout(long setLayout) {
		aetherium$allocatedSet = setLayout;
	}

	@Unique
	private static final ThreadLocal<String> AETHERIUM$NAME = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<Boolean> AETHERIUM$APPENDED = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<Boolean> AETHERIUM$GEOMETRY = ThreadLocal.withInitial(() -> false);
	/** The module whose stage is being described (vanilla reads its type, then converts that to a stage bit). */
	@Unique
	private static final ThreadLocal<SpvModule> AETHERIUM$MODULE = new ThreadLocal<>();

	@Inject(method = "compile", at = @At("HEAD"))
	private static void aetherium$name(VulkanDevice device, BackendRenderPipeline.CreateInfo info, CallbackInfoReturnable<VulkanRenderPipeline> cir) {
		AETHERIUM$NAME.set(info.name());
		AETHERIUM$APPENDED.set(false);
		AETHERIUM$GEOMETRY.set(info.shaders().stream().anyMatch(s -> s.module() instanceof GeometryStages.Module));
		// Every binding of vanilla's set 0 is visible to every stage, so each stage sees all of its samplers.
		int samplers = (int) info.uniforms().stream().filter(u -> u.type() == UniformType.COMBINED_IMAGE_SAMPLER).count();
		AETHERIUM$ALLOCATED.set(info.name().startsWith("aetherium_shaders:") && AllocatedSets.wanted(info.name(), samplers));
	}

	@ModifyArg(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;flags(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;"))
	private static int aetherium$allocatedSet(int flags) {
		return AETHERIUM$ALLOCATED.get() ? flags & ~AllocatedSets.PUSH_FLAG : flags;
	}

	/** Set 0 (push descriptors, vertex and fragment in vanilla) is read by the geometry stage too. */
	@ModifyArg(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;stageFlags(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
	private static int aetherium$geometryUniforms(int stages) {
		return AETHERIUM$GEOMETRY.get() ? stages | GeometryStages.STAGE_BIT : stages;
	}

	@Redirect(method = "compile", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/SpvModule;type()Lcom/mojang/renderpearl/api/pipeline/ShaderType;"))
	private static ShaderType aetherium$stageModule(SpvModule module) {
		AETHERIUM$MODULE.set(module);
		return module.type();
	}

	@Redirect(method = "compile", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanConst;toVk(Lcom/mojang/renderpearl/api/pipeline/ShaderType;)I"))
	private static int aetherium$geometryStage(ShaderType type) {
		return AETHERIUM$MODULE.get() instanceof GeometryStages.Module ? GeometryStages.STAGE_BIT : VulkanConst.toVk(type);
	}

	@ModifyArg(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;pSetLayouts(Ljava/nio/LongBuffer;)Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;"))
	private static LongBuffer aetherium$storageSet(LongBuffer layouts) {
		AETHERIUM$SET0.set(layouts.get(layouts.position()));
		String name = AETHERIUM$NAME.get();
		long storage = StorageSet.setLayout();
		if (storage == 0 || name == null || !name.startsWith("aetherium_shaders:") || layouts.remaining() != 1) {
			return layouts;
		}
		AETHERIUM$APPENDED.set(true);
		// Same stack frame as vanilla's own buffer: alive until the layout is created.
		return MemoryStack.stackGet().longs(layouts.get(layouts.position()), storage);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private static void aetherium$register(VulkanDevice device, BackendRenderPipeline.CreateInfo info, CallbackInfoReturnable<VulkanRenderPipeline> cir) {
		if (Boolean.TRUE.equals(AETHERIUM$APPENDED.get()) && cir.getReturnValue() != null) {
			StorageSet.registerPipelineLayout(cir.getReturnValue().pipelineLayout());
		}
		Long set0 = AETHERIUM$SET0.get();
		if (AETHERIUM$ALLOCATED.get() && set0 != null && (Object) cir.getReturnValue() instanceof AllocatedSets.Holder holder) {
			holder.aetherium$allocatedSetLayout(set0);
		}
		AETHERIUM$NAME.remove();
		AETHERIUM$APPENDED.remove();
		AETHERIUM$GEOMETRY.remove();
		AETHERIUM$MODULE.remove();
		AETHERIUM$ALLOCATED.remove();
		AETHERIUM$SET0.remove();
	}
}
