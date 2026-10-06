package dev.spacebod.aetherium.client.mixin.access;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A render type's name and setup, and vanilla's factory (the shader engine copies render types for block entities). */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Accessor("name")
	String aetherium$name();

	@Accessor("state")
	RenderSetup aetherium$state();

	@Invoker("create")
	static RenderType aetherium$create(String name, RenderSetup state) {
		throw new AssertionError();
	}
}
