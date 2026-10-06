package dev.spacebod.aetherium.shaders.shaderpack.programs;

public interface ProgramSetInterface {
	class Empty implements ProgramSetInterface {

		public static final ProgramSetInterface INSTANCE = new Empty();
	}
}
