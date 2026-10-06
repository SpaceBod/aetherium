package dev.spacebod.aetherium.client.mixin.shaders;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import dev.spacebod.aetherium.client.gpu.GeometryStages;
import dev.spacebod.aetherium.client.gpu.PackModules;
import java.nio.ByteBuffer;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Aetherium Shaders' modules in vanilla's GLSL compiler ({@link PackModules}): a pack's geometry stage compiled as one
 * ({@link GeometryStages}), no debug information, and read-before-written variables zeroed before the module is
 * reflected. Other modules compile as vanilla compiles them.
 */
@Mixin(GlslCompiler.class)
abstract class GlslCompilerMixin {
	/** Set while the options for one of our modules are built (they are built per compile, on the compiling thread). */
	@Unique
	private static final ThreadLocal<Boolean> aetherium$ours = ThreadLocal.withInitial(() -> false);

	@ModifyArg(method = "compileToSpv", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_into_spv(JLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;J)J"),
			index = 2)
	private int aetherium$geometryKind(int kind) {
		return GeometryStages.compilingStage() ? Shaderc.shaderc_geometry_shader : kind;
	}

	@WrapOperation(method = "compileToSpv", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;createBaseShaderOptions()J"))
	private long aetherium$options(GlslCompiler compiler, Operation<Long> original, @Local(argsOnly = true, ordinal = 0) String name) {
		aetherium$ours.set(PackModules.ours(name));
		try {
			return original.call(compiler);
		} finally {
			aetherium$ours.set(false);
		}
	}

	@WrapOperation(method = "createBaseShaderOptions", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_options_set_generate_debug_info(J)V"))
	private void aetherium$debugInfo(long options, Operation<Void> original) {
		if (!aetherium$ours.get() || PackModules.debugInfo()) {
			original.call(options);
		}
	}

	@WrapOperation(method = "compileToSpv", at = @At(value = "NEW", target = "com/mojang/renderpearl/frontend/shaders/SPIRVModule"))
	private SPIRVModule aetherium$zeroLocals(ByteBuffer spirv, ShaderType type, Operation<SPIRVModule> original,
			@Local(argsOnly = true, ordinal = 0) String name) {
		return original.call(PackModules.ours(name) ? PackModules.patch(spirv) : spirv, type);
	}
}
