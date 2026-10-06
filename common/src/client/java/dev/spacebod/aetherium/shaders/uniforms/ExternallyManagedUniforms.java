package dev.spacebod.aetherium.shaders.uniforms;

import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformType;

public class ExternallyManagedUniforms {
	private ExternallyManagedUniforms() {
	}

	/** Names the engine fills itself (vanilla's transforms and fog): no uniform provider is needed for them. */
	public static void addExternallyManagedUniforms(UniformHolder uniformHolder) {
		addMat4(uniformHolder, "aeth_ModelViewMatrix");
		addMat4(uniformHolder, "u_ModelViewProjectionMatrix");
		addMat3(uniformHolder, "aeth_NormalMatrix");
		addFloat(uniformHolder, "darknessFactor");
		addFloat(uniformHolder, "darknessLightFactor");
		addFloat(uniformHolder, "aeth_FogStart");
		addFloat(uniformHolder, "aeth_FogEnd");
		addVec4(uniformHolder, "aeth_FogColor");
		addMat4(uniformHolder, "aeth_ProjectionMatrix");
		addFloat(uniformHolder, "aeth_TextureScale");
		addFloat(uniformHolder, "aeth_GlintAlpha");
		addFloat(uniformHolder, "aeth_ModelScale");
		addFloat(uniformHolder, "aeth_ModelOffset");
		addVec3(uniformHolder, "aeth_CameraTranslation");
		addVec3(uniformHolder, "u_RegionOffset");

		// Vanilla
		uniformHolder.externallyManagedUniform("aeth_TextureMat", UniformType.MAT4);
		uniformHolder.externallyManagedUniform("aeth_ModelViewMat", UniformType.MAT4);
		uniformHolder.externallyManagedUniform("aeth_ProjMat", UniformType.MAT4);
		uniformHolder.externallyManagedUniform("aeth_ModelOffset", UniformType.VEC3);
		uniformHolder.externallyManagedUniform("aeth_ColorModulator", UniformType.VEC4);
		uniformHolder.externallyManagedUniform("aeth_NormalMat", UniformType.MAT3);
		uniformHolder.externallyManagedUniform("aeth_FogStart", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("aeth_FogEnd", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("aeth_FogDensity", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("aeth_ScreenSize", UniformType.VEC2);
		uniformHolder.externallyManagedUniform("aeth_FogColor", UniformType.VEC4);
	}

	private static void addMat3(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.MAT3);
	}

	private static void addMat4(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.MAT4);
	}

	private static void addVec3(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.VEC3);
	}

	private static void addVec4(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.VEC4);
	}

	private static void addFloat(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.FLOAT);
	}
}
