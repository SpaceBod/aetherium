package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.spacebod.aetherium.client.gpu.PipelineCacheStore;
import dev.spacebod.aetherium.client.gpu.ShaderCompiler;
import dev.spacebod.aetherium.client.gpu.VulkanCompute;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.state.FogMode;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.TransformPatcher;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.TargetImages;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.properties.IndirectPointer;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import dev.spacebod.aetherium.shaders.uniforms.CommonUniforms;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joml.Vector2f;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * One compute program of a pack ({@code shadowcomp.csh}, {@code composite3_a.csh}, {@code setup.csh} ...): transformed like
 * the pack's other programs, then built as a Vulkan compute pipeline of its own with two descriptor sets: set 0 its
 * uniform block (binding 0) and the textures it samples (bindings 1..n), set 1 the pack's storage set (custom images and
 * storage buffers, {@link StorageBindings}). Dispatched between render passes, into vanilla's frame command buffer (or
 * command buffers spliced into the frame); the engine orders dispatches with barriers.
 */
public final class ComputePass implements AutoCloseable {
	private static final Pattern LOCAL_SIZE = Pattern.compile("layout\\s*\\(([^)]*local_size_[xyz][^)]*)\\)\\s*in\\s*;");
	/** Uniform block copies kept for frames still in flight (vanilla keeps two). */
	private static final int SLOTS = 3;

	private final String name;
	private final VulkanDevice vulkan;
	private final VkDevice device;
	private final BlockProgramUniforms uniforms;
	private final List<String> samplers;
	private final int[] localSize;
	private final @Nullable Vector3i workGroups;
	private final Vector2f workGroupRelative;
	private final @Nullable IndirectPointer indirect;
	private final long setLayout;
	private final long layout;
	private final long pipeline;
	private final long pool;
	private final long[] sets = new long[SLOTS];
	private final int blockSize;
	private final long uniformBuffer;
	private final long uniformAllocation;
	private final ByteBuffer uniformMemory;
	private final TextureStage stage;
	private int slot;

	private ComputePass(String name, TextureStage stage, VulkanDevice vulkan, BlockProgramUniforms uniforms, List<String> samplers, int[] localSize,
			@Nullable Vector3i workGroups, Vector2f workGroupRelative, @Nullable IndirectPointer indirect, long setLayout, long layout, long pipeline,
			int blockSize) {
		this.name = name;
		this.stage = stage;
		this.vulkan = vulkan;
		this.device = vulkan.vkDevice();
		this.uniforms = uniforms;
		this.samplers = samplers;
		this.localSize = localSize;
		this.workGroups = workGroups;
		this.workGroupRelative = workGroupRelative;
		this.indirect = indirect;
		this.setLayout = setLayout;
		this.layout = layout;
		this.pipeline = pipeline;
		this.blockSize = blockSize;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long images = samplers.stream().filter(s -> targetImage(s) != null).count();
			int samplerCount = (int) Math.max(1, (samplers.size() - images) * SLOTS);
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SLOTS);
			sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(samplerCount);
			sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount((int) Math.max(1, images * SLOTS));
			LongBuffer out = stack.mallocLong(1);
			VulkanCompute.check(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SLOTS).pPoolSizes(sizes),
					null, out), name + " descriptor pool");
			this.pool = out.get(0);
			for (int i = 0; i < SLOTS; i++) {
				sets[i] = VulkanCompute.allocate(device, pool, setLayout);
			}
			long bufferSize = (long) Math.max(16, blockSize) * SLOTS;
			VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
					.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
			PointerBuffer allocOut = stack.mallocPointer(1);
			VulkanCompute.check(Vma.vmaCreateBuffer(vulkan.vma(), VkBufferCreateInfo.calloc(stack).sType$Default().size(bufferSize)
					.usage(VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE), alloc, out, allocOut, info), name + " uniforms");
			this.uniformBuffer = out.get(0);
			this.uniformAllocation = allocOut.get(0);
			this.uniformMemory = MemoryUtil.memByteBuffer(info.pMappedData(), (int) bufferSize);
		}
	}

	/**
	 * A program transformed, compiled and with its Vulkan pipeline built ({@link #prepare}): what a pack load does off the
	 * render thread (pipeline creation is where the driver compiles, the slow part). {@link #create} takes it over;
	 * {@link #discard} frees one that is not used.
	 */
	public record Prepared(Lowered lowered, VkDevice device, long setLayout, long layout, long pipeline) {
		public void discard() {
			VK10.vkDestroyPipeline(device, pipeline, null);
			VK10.vkDestroyPipelineLayout(device, layout, null);
			VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
		}
	}

	/**
	 * The thread-safe half of {@link #create}: transform, SPIR-V and pipeline (any thread); null (logged) when it cannot
	 * be used. Needs the storage set's layout (0 = none).
	 */
	public static @Nullable Prepared prepare(ComputeSource source, TextureStage stage, VkDevice device, long storageSetLayout) {
		String name = source.getName();
		if (source.getSource().isEmpty()) {
			return null;
		}
		try {
			Lowered lowered = lower(name, source.getSource().get(), stage);
			if (lowered == null) {
				return null;
			}
			List<String> samplers = lowered.samplers();
			long compileStart = System.nanoTime();
			ByteBuffer spirv = ShaderCompiler.packCompute(name, lowered.glsl());
			LoadTimings.add(LoadTimings.Kind.SHADERC, System.nanoTime() - compileStart);
			long setLayout = 0, layout = 0;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1 + samplers.size(), stack);
				bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
				for (int i = 0; i < samplers.size(); i++) {
					bindings.get(1 + i).binding(1 + i).descriptorType(targetImage(samplers.get(i)) != null ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
							: VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
				}
				LongBuffer handle = stack.mallocLong(1);
				VulkanCompute.check(VK10.vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),
						null, handle), name + " set layout");
				setLayout = handle.get(0);
				LongBuffer setLayouts = storageSetLayout != 0 ? stack.longs(setLayout, storageSetLayout) : stack.longs(setLayout);
				VulkanCompute.check(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(setLayouts),
						null, handle), name + " pipeline layout");
				layout = handle.get(0);
				VulkanCompute.check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, handle),
						name + " module");
				long module = handle.get(0);
				VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
				long pipelineLayout = layout;
				info.get(0).sType$Default().layout(pipelineLayout)
						.stage(s -> s.sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
				long pipelineStart = System.nanoTime();
				int result = VK10.vkCreateComputePipelines(device, PipelineCacheStore.handle(), info, null, handle);
				LoadTimings.add(LoadTimings.Kind.COMPUTE_PIPELINE, System.nanoTime() - pipelineStart);
				VK10.vkDestroyShaderModule(device, module, null);
				VulkanCompute.check(result, name + " pipeline");
				return new Prepared(lowered, device, setLayout, layout, handle.get(0));
			} catch (RuntimeException e) {
				if (layout != 0) {
					VK10.vkDestroyPipelineLayout(device, layout, null);
				}
				if (setLayout != 0) {
					VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
				}
				throw e;
			} finally {
				MemoryUtil.memFree(spirv);
			}
		} catch (RuntimeException e) {
			DeviceLoss.rethrow(e);
			AetheriumShaders.logger.error("[{}] compute program could not be built; skipped", name, e);
			return null;
		}
	}

	/** Transforms and builds {@code source}; null (logged) when it cannot be used. Needs the storage set's layout (0 = none). */
	public static @Nullable ComputePass create(ComputeSource source, TextureStage stage, CustomUniforms customUniforms, VulkanDevice vulkan,
			long storageSetLayout) {
		Prepared prepared = prepare(source, stage, vulkan.vkDevice(), storageSetLayout);
		return prepared == null ? null : create(source, stage, prepared, customUniforms, vulkan);
	}

	/** The render-thread half: descriptors, uniform buffer and uniforms for a {@link #prepare}d program (which it takes over). */
	public static @Nullable ComputePass create(ComputeSource source, TextureStage stage, Prepared prepared, CustomUniforms customUniforms,
			VulkanDevice vulkan) {
		String name = source.getName();
		try {
			Lowered lowered = prepared.lowered();
			UniformBlock.Layout blockLayout = lowered.layout();
			List<String> samplers = lowered.samplers();
			int[] localSize = localSize(lowered.glsl());
			BlockProgramUniforms uniforms = new BlockProgramUniforms(name, blockLayout);
			CommonUniforms.addDynamicUniforms(uniforms, FogMode.OFF);
			customUniforms.assignTo(uniforms);
			Vector2f relative = source.getWorkGroupRelative() != null ? source.getWorkGroupRelative() : new Vector2f(1, 1);
			ComputePass pass = new ComputePass(name, stage, vulkan, uniforms, samplers, localSize, source.getWorkGroups(), relative,
					source.getIndirectPointer(), prepared.setLayout(), prepared.layout(), prepared.pipeline(), blockLayout.size());
			customUniforms.mapholderToPass(uniforms, pass);
			List<String> missing = uniforms.unprovided();
			if (!missing.isEmpty()) {
				AetheriumShaders.logger.debug("[{}] uniforms without a provider (read as 0): {}", name, missing);
			}
			AetheriumShaders.logger.info("compute {} (local size {}x{}x{})", name, localSize[0], localSize[1], localSize[2]);
			return pass;
		} catch (RuntimeException e) {
			DeviceLoss.rethrow(e);
			AetheriumShaders.logger.error("[{}] compute program could not be built; skipped", name, e);
			prepared.discard();
			return null;
		}
	}

	/**
	 * For a target bound as an image ({@code colorimgN}, {@code shadowcolorimgN}; the lowering lists them after the
	 * samplers), the target's sampler name ({@code colortexN}, {@code shadowcolorN}); null for a sampler.
	 */
	private static @Nullable String targetImage(String name) {
		Matcher m = TargetImages.matcher(name);
		if (!m.matches()) {
			return null;
		}
		return (TargetImages.shadow(m) ? "shadowcolor" : "colortex") + TargetImages.index(m);
	}

	/** A compute program after the transform and the Vulkan lowering, with set 0 assigned (block at 0, samplers 1..n). */
	public record Lowered(String glsl, UniformBlock.Layout layout, List<String> samplers) {
	}

	/** Transforms and lowers one compute program (no game needed: the compile harness uses it too); null when empty. */
	public static @Nullable Lowered lower(String name, @Nullable String computeSource, TextureStage stage) {
		if (computeSource == null) {
			return null;
		}
		// Set 0 (the block at binding 0, samplers 1..n) is assigned by the lowering, in this sampler order.
		var program = TransformPatcher.patchComputeLowered(name, computeSource, stage, TexturePatching.current(), Lowering.compute());
		return new Lowered(program.stages().get(PatchShaderType.COMPUTE), program.layout(), program.samplers());
	}

	public String name() {
		return name;
	}

	/** The custom-texture stage the program belongs to. */
	public TextureStage stage() {
		return stage;
	}

	/**
	 * Records this dispatch into {@code cb}: uniforms for this frame, set 0 (uniforms and textures), the storage set,
	 * then the dispatch ({@code workGroups}, else {@code workGroupsRender} x screen, else indirect from a storage buffer).
	 */
	public void dispatch(VkCommandBuffer cb, CustomUniforms customUniforms, Function<String, SamplerTable.Bound> textures, long storageSet,
			@Nullable PackStorage storage, int screenWidth, int screenHeight) {
		uniforms.update();
		UniformBlockBuffer.begin(uniforms.buffer());
		try {
			customUniforms.push(this);
		} finally {
			UniformBlockBuffer.end();
		}
		slot = (slot + 1) % SLOTS;
		long set = sets[slot];
		int offset = Math.max(16, blockSize) * slot;
		if (blockSize > 0) {
			ByteBuffer bytes = uniforms.buffer().duplicate().clear();
			bytes.limit(Math.min(bytes.capacity(), blockSize));
			uniformMemory.put(offset, bytes, 0, bytes.remaining());
			Vma.vmaFlushAllocation(vulkan.vma(), uniformAllocation, offset, blockSize);
		}
		// Set 0 in one update: the uniform block, then each sampler or target image this dispatch binds.
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1 + samplers.size(), stack);
			VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
			bufferInfo.get(0).buffer(uniformBuffer).offset(offset).range(Math.max(16, blockSize));
			writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(bufferInfo);
			int count = 1;
			for (int i = 0; i < samplers.size(); i++) {
				String image = targetImage(samplers.get(i));
				long view;
				long sampler;
				if (image != null) {
					// The target's current side, as its sampler reads it this pass (image bindings follow buffer flips).
					SamplerTable.Bound t = textures.apply(image);
					if (t == null || !(t.view() instanceof VulkanGpuTextureView v)) {
						continue;
					}
					view = v.vkImageView();
					sampler = 0;
				} else {
					SamplerTable.Bound t = textures.apply(samplers.get(i));
					if (t == null || !(t.view() instanceof VulkanGpuTextureView v) || !(t.sampler() instanceof VulkanGpuSampler s)) {
						continue;
					}
					view = v.vkImageView();
					sampler = s.vkSampler();
				}
				VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
				info.get(0).imageView(view).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
				writes.get(count++).sType$Default().dstSet(set).dstBinding(1 + i).descriptorCount(1)
						.descriptorType(sampler == 0 ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
						.pImageInfo(info);
			}
			writes.limit(count);
			VK10.vkUpdateDescriptorSets(device, writes, null);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkCmdBindPipeline(cb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			LongBuffer bound = storageSet != 0 ? stack.longs(set, storageSet) : stack.longs(set);
			VK10.vkCmdBindDescriptorSets(cb, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, bound, null);
			if (indirect != null && storage != null && storage.vkBuffer(indirect.buffer()) != 0) {
				VK10.vkCmdDispatchIndirect(cb, storage.vkBuffer(indirect.buffer()), indirect.offset());
			} else if (workGroups != null) {
				VK10.vkCmdDispatch(cb, Math.max(1, workGroups.x), Math.max(1, workGroups.y), Math.max(1, workGroups.z));
			} else {
				int x = (int) Math.ceil(screenWidth * workGroupRelative.x / (double) localSize[0]);
				int y = (int) Math.ceil(screenHeight * workGroupRelative.y / (double) localSize[1]);
				VK10.vkCmdDispatch(cb, Math.max(1, x), Math.max(1, y), 1);
			}
		}
	}

	private static int[] localSize(String glsl) {
		int[] size = {1, 1, 1};
		Matcher m = LOCAL_SIZE.matcher(glsl);
		if (m.find()) {
			for (String part : m.group(1).split(",")) {
				String[] kv = part.split("=");
				if (kv.length != 2) {
					continue;
				}
				String key = kv[0].trim();
				try {
					int value = Integer.parseInt(kv[1].trim());
					switch (key) {
						case "local_size_x" -> size[0] = value;
						case "local_size_y" -> size[1] = value;
						case "local_size_z" -> size[2] = value;
						default -> {
						}
					}
				} catch (NumberFormatException e) {
					// a specialisation constant or macro the preprocessor left: keep 1
				}
			}
		}
		return size;
	}

	@Override
	public void close() {
		VK10.vkDestroyPipeline(device, pipeline, null);
		VK10.vkDestroyPipelineLayout(device, layout, null);
		VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
		VK10.vkDestroyDescriptorPool(device, pool, null);
		Vma.vmaDestroyBuffer(vulkan.vma(), uniformBuffer, uniformAllocation);
	}
}
