package dev.spacebod.aetherium.shaders.shaderpack.materialmap;

import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class WorldRenderingSettings {
	public static final WorldRenderingSettings INSTANCE = new WorldRenderingSettings();

	private boolean reloadRequired;
	private Object2IntMap<BlockState> blockStateIds;
	/** Read by chunk-build threads ({@code layer.*} overrides). */
	private volatile Map<Block, BlockRenderType> blockTypeIds;
	private Object2IntFunction<NamespacedId> entityIds;
	private Object2IntFunction<NamespacedId> itemIds;
	/** Read by chunk-build threads, as the next two (vanilla's block lighting hooks). */
	private volatile float ambientOcclusionLevel;
	private volatile boolean disableDirectionalShading;
	private boolean hasVillagerConversionId;
	private volatile boolean useSeparateAo;
	private boolean separateEntityDraws;
	private boolean voxelizeLightBlocks;
	private boolean breaksAnisotropy = false;

	public WorldRenderingSettings() {
		reloadRequired = false;
		blockStateIds = null;
		blockTypeIds = null;
		ambientOcclusionLevel = 1.0F;
		disableDirectionalShading = false;
		useSeparateAo = false;
		separateEntityDraws = false;
		voxelizeLightBlocks = false;
		hasVillagerConversionId = false;
	}

	public boolean isReloadRequired() {
		return reloadRequired;
	}

	public void clearReloadRequired() {
		reloadRequired = false;
	}

	@Nullable
	public Object2IntMap<BlockState> getBlockStateIds() {
		return blockStateIds;
	}

	public void setBlockStateIds(Object2IntMap<BlockState> blockStateIds) {
		if (this.blockStateIds != null && this.blockStateIds.equals(blockStateIds)) {
			return;
		}

		this.reloadRequired = true;
		this.blockStateIds = blockStateIds;
	}

	public Map<Block, BlockRenderType> getBlockTypeIds() {
		return blockTypeIds;
	}

	public void setBlockTypeIds(Map<Block, BlockRenderType> blockTypeIds) {
		if (this.blockTypeIds != null && this.blockTypeIds.equals(blockTypeIds)) {
			return;
		}

		this.reloadRequired = true;
		this.blockTypeIds = blockTypeIds;
	}

	@Nullable
	public Object2IntFunction<NamespacedId> getEntityIds() {
		return entityIds;
	}

	public void setEntityIds(Object2IntFunction<NamespacedId> entityIds) {
		// No reload needed: entities are rebuilt every frame.
		this.entityIds = entityIds;
		this.hasVillagerConversionId = entityIds.containsKey(new NamespacedId("minecraft", "zombie_villager_converting"));
	}

	@Nullable
	public Object2IntFunction<NamespacedId> getItemIds() {
		return itemIds;
	}

	public void setItemIds(Object2IntFunction<NamespacedId> itemIds) {
		// No reload needed: items are rebuilt every frame.
		this.itemIds = itemIds;
	}

	public float getAmbientOcclusionLevel() {
		return ambientOcclusionLevel;
	}

	public void setAmbientOcclusionLevel(float ambientOcclusionLevel) {
		if (ambientOcclusionLevel == this.ambientOcclusionLevel) {
			return;
		}

		this.reloadRequired = true;
		this.ambientOcclusionLevel = ambientOcclusionLevel;
	}

	public boolean shouldDisableDirectionalShading() {
		return disableDirectionalShading;
	}

	public void setDisableDirectionalShading(boolean disableDirectionalShading) {
		if (disableDirectionalShading == this.disableDirectionalShading) {
			return;
		}

		this.reloadRequired = true;
		this.disableDirectionalShading = disableDirectionalShading;
	}

	public boolean shouldUseSeparateAo() {
		return useSeparateAo;
	}

	public void setUseSeparateAo(boolean useSeparateAo) {
		if (useSeparateAo == this.useSeparateAo) {
			return;
		}

		this.reloadRequired = true;
		this.useSeparateAo = useSeparateAo;
	}

	public boolean shouldVoxelizeLightBlocks() {
		return voxelizeLightBlocks;
	}

	public void setVoxelizeLightBlocks(boolean voxelizeLightBlocks) {
		if (voxelizeLightBlocks == this.voxelizeLightBlocks) {
			return;
		}

		this.reloadRequired = true;
		this.voxelizeLightBlocks = voxelizeLightBlocks;
	}

	public boolean shouldSeparateEntityDraws() {
		return separateEntityDraws;
	}

	public void setSeparateEntityDraws(boolean separateEntityDraws) {
		this.separateEntityDraws = separateEntityDraws;
	}

	public boolean hasVillagerConversionId() {
		return hasVillagerConversionId;
	}

	public void setBreaksAnisotropy(boolean b) {
		this.breaksAnisotropy = b;
	}

	public boolean breaksAnisotropy() {
		return breaksAnisotropy;
	}
}
