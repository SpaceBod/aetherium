package dev.spacebod.aetherium.shaders.engine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.level.biome.Biome;

/** Vanilla 26.3 state the pack uniforms read. */
public final class VanillaWorldState {
	private static final VarHandle CLIMATE;
	private static final MethodHandle DOWNFALL;

	static {
		try {
			CLIMATE = MethodHandles.privateLookupIn(Biome.class, MethodHandles.lookup())
					.findVarHandle(Biome.class, "climateSettings", Class.forName(Biome.class.getName() + "$ClimateSettings"));
			Class<?> climate = CLIMATE.varType();
			DOWNFALL = MethodHandles.privateLookupIn(climate, MethodHandles.lookup())
					.findVirtual(climate, "downfall", MethodType.methodType(float.class)).asType(MethodType.methodType(float.class, Object.class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private VanillaWorldState() {
	}

	/** This frame's fog, as vanilla's level renderer computed it. */
	public static FogData fog() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
	}

	/** A biome's downfall (vanilla keeps it in a private record). */
	public static float downfall(Biome biome) {
		try {
			return (float) DOWNFALL.invokeExact((Object) CLIMATE.get(biome));
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}
}
