package dev.spacebod.aetherium.client.mixin.access;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block state a block entity renders (its id in the shader pack's block.properties). */
@Mixin(BlockEntityRenderState.class)
public interface BlockEntityRenderStateAccessor {
	@Accessor("blockState")
	BlockState aetherium$blockState();
}
