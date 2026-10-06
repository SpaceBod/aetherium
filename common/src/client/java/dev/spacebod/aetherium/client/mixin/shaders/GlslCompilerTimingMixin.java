package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import dev.spacebod.aetherium.shaders.engine.LoadTimings;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;

/** Aetherium Shaders' load timing ({@link LoadTimings}): time spent compiling each shader stage to SPIR-V. */
@Mixin(GlslCompiler.class)
abstract class GlslCompilerTimingMixin {
	@WrapMethod(method = "compileToSpv")
	private SpvModule aetherium$time(String name, String source, ShaderType type, ShaderDefines defines, ShaderSource shaderSource,
			Operation<SpvModule> original) {
		long start = System.nanoTime();
		try {
			return original.call(name, source, type, defines, shaderSource);
		} finally {
			LoadTimings.add(LoadTimings.Kind.SHADERC, System.nanoTime() - start);
		}
	}
}
