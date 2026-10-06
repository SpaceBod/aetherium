package dev.spacebod.aetherium.mixin.opt.mem.component_patch;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code mem.component_patch}: once an item stack's component patch becomes empty again (a component set
 * back to its default, removed, or the patch cleared), vanilla keeps the emptied
 * {@code Reference2ObjectArrayMap} it copied on first write. Swap it for the shared empty map, flagged
 * copy-on-write so the next write copies it, as vanilla does for a fresh stack.
 */
@Mixin(PatchedDataComponentMap.class)
abstract class PatchedDataComponentMapMixin {
	@Shadow
	private Reference2ObjectMap<DataComponentType<?>, Object> patch;

	@Shadow
	private boolean copyOnWrite;

	@Inject(method = {"applyPatch(Lnet/minecraft/core/component/DataComponentPatch;)V", "restorePatch", "clearPatch"}, at = @At("RETURN"))
	private void aetherium$shareEmpty(CallbackInfo ci) {
		aetherium$shareIfEmpty();
	}

	@Inject(method = {"set(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;", "remove"}, at = @At("RETURN"))
	private void aetherium$shareEmptyReturning(CallbackInfoReturnable<?> cir) {
		aetherium$shareIfEmpty();
	}

	@Unique
	private void aetherium$shareIfEmpty() {
		if (!this.copyOnWrite && this.patch.isEmpty()) {
			this.patch = Reference2ObjectMaps.emptyMap();
			this.copyOnWrite = true;
		}
	}
}
