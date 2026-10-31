package dev.spacebod.aetherium.client.gpu;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Shader-pack pipelines with independent attachment blending on devices that enabled it. */
public final class PackRenderPipeline extends RenderPipeline {
	private PackRenderPipeline(RenderPipeline validated, ColorTargetState[] targets) {
		super(validated.getLocation(), validated.getShaders(), validated.getShaderDefines(), validated.getBindGroupLayouts(),
				targets.clone(), validated.getDepthStencilState(), validated.getPolygonMode(), validated.isCull(),
				validated.getVertexFormatBindings().toArray(VertexFormat[]::new), validated.getPrimitiveTopology(),
				validated.pushConstantSize(), validated.getSortKey());
	}

	/**
	 * Vanilla's builder rejects distinct blend functions, though its Vulkan backend already applies each attachment's
	 * state independently. Validate the rest of the pipeline with blending off, then keep the pack's actual states in
	 * the resulting pipeline. The device feature is required before constructing such a pipeline.
	 */
	public static RenderPipeline build(RenderPipeline.Builder builder, ColorTargetState[] targets) {
		Set<BlendFunction> blends = new HashSet<>();
		for (ColorTargetState target : targets) {
			if (target != null) {
				target.blendFunction().ifPresent(blends::add);
			}
		}
		boolean independent = blends.size() > 1;
		if (independent && !StorageFeatures.has(StorageFeatures.INDEPENDENT_BLEND)) {
			throw new IllegalStateException("The shader program needs independent attachment blending, but the device did not enable it");
		}
		for (int i = 0; i < targets.length; i++) {
			ColorTargetState target = targets[i];
			builder.withColorTargetState(i, independent && target != null
					? new ColorTargetState(Optional.empty(), target.format(), target.writeMask()) : target);
		}
		RenderPipeline validated = builder.build();
		return independent ? new PackRenderPipeline(validated, targets) : validated;
	}
}
