package dev.spacebod.aetherium.mixin.opt.mem.state_cache_dedup;

import dev.spacebod.aetherium.memory.StateCacheDedup;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ArrayVoxelShape.class)
public interface ArrayVoxelShapeAccessor extends StateCacheDedup.ArrayVoxelShapeAccess {
	@Override
	@Accessor("xs")
	DoubleList aetherium$xs();

	@Override
	@Accessor("ys")
	DoubleList aetherium$ys();

	@Override
	@Accessor("zs")
	DoubleList aetherium$zs();

	@Override
	@Mutable
	@Accessor("xs")
	void aetherium$setXs(DoubleList xs);

	@Override
	@Mutable
	@Accessor("ys")
	void aetherium$setYs(DoubleList ys);

	@Override
	@Mutable
	@Accessor("zs")
	void aetherium$setZs(DoubleList zs);
}
