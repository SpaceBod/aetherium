package dev.spacebod.aetherium.mixin.opt.mem.state_cache_dedup;

import dev.spacebod.aetherium.memory.StateCacheDedup;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VoxelShape.class)
public interface VoxelShapeAccessor extends StateCacheDedup.VoxelShapeAccess {
	@Override
	@Accessor("shape")
	DiscreteVoxelShape aetherium$shape();

	@Override
	@Mutable
	@Accessor("shape")
	void aetherium$setShape(DiscreteVoxelShape shape);
}
