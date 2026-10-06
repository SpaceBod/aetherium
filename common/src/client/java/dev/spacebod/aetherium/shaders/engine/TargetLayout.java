package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;

/** The colour attachments a family of world programs renders into: the gbuffers (colortex) or the shadow maps (shadowcolor). */
public interface TargetLayout {
	/** The attachments as target indices (colortexN / shadowcolorN), in attachment order. */
	int[] attachments();

	GpuFormat format(int target);
}
