package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformType;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import dev.spacebod.aetherium.shaders.uniforms.custom.CustomUniforms;
import java.util.List;

public final class UniformProviderFixtures {
	private UniformProviderFixtures() {
	}

	public static void main(String[] args) {
		if (args.length == 0) {
			run();
		} else if (args.length == 1 && args[0].equals("locations")) {
			rawLocations();
		} else {
			throw new IllegalArgumentException("Unknown uniform provider check");
		}
	}

	public static void run() {
		rawLocations();
		scheduledProviders();
		dynamicProviders();
		cachedProviders();
		System.out.println("uniform providers: raw offsets, scheduled/dynamic scalar conversion and cached custom uploads pass");
	}

	private static void rawLocations() {
		BlockProgramUniforms uniforms = new BlockProgramUniforms("fixture/raw_locations", layout("float", "bool"));
		if (uniforms.location("count", UniformType.INT).isPresent()) {
			throw new AssertionError("an incompatible raw location lookup returned a tagged offset");
		}
		if (uniforms.location("flag", UniformType.INT).orElseThrow() != 4) {
			throw new AssertionError("a boolean location is not its byte offset");
		}
		BlockProgramUniforms floats = new BlockProgramUniforms("fixture/raw_float_locations", layout("float", "float"));
		if (floats.location("count", UniformType.FLOAT).orElseThrow() != 0
				|| floats.location("flag", UniformType.FLOAT).orElseThrow() != 4) {
			throw new AssertionError("float locations are not their byte offsets");
		}
	}

	private static void scheduledProviders() {
		for (UniformUpdateFrequency frequency : List.of(UniformUpdateFrequency.ONCE, UniformUpdateFrequency.PER_TICK, UniformUpdateFrequency.PER_FRAME)) {
			int[] count = {-7};
			boolean[] flag = {true};
			BlockProgramUniforms floats = new BlockProgramUniforms("fixture/scheduled_floats", layout("float", "float"));
			BlockProgramUniforms integers = new BlockProgramUniforms("fixture/scheduled_integers", layout("int", "bool"));
			for (BlockProgramUniforms uniforms : List.of(floats, integers)) {
				uniforms.uniform1i(frequency, "count", () -> count[0]);
				uniforms.uniform1b(frequency, "flag", () -> flag[0]);
				uniforms.update();
				if (!uniforms.unprovided().isEmpty()) {
					throw new AssertionError("a scheduled scalar provider was dropped");
				}
			}
			assertValues(floats, integers, count[0], flag[0]);
			count[0] = 37;
			flag[0] = false;
			floats.update();
			integers.update();
			assertValues(floats, integers, -7, true);
			SystemTimeUniforms.COUNTER.beginFrame();
			floats.update();
			integers.update();
			boolean perFrame = frequency == UniformUpdateFrequency.PER_FRAME;
			assertValues(floats, integers, perFrame ? 37 : -7, !perFrame);
		}
		for (int value : new int[]{0, 1, -1, 16_777_217, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			BlockProgramUniforms uniforms = new BlockProgramUniforms("fixture/numeric_conversion", layout("float", "uint"));
			uniforms.uniform1i(UniformUpdateFrequency.ONCE, "count", () -> value);
			uniforms.uniform1i(UniformUpdateFrequency.ONCE, "flag", () -> value);
			uniforms.update();
			if (uniforms.buffer().getFloat(0) != (float) value || uniforms.buffer().getInt(4) != value) {
				throw new AssertionError("scalar conversion changed the numeric value or integer bits: " + value);
			}
		}
	}

	private static void dynamicProviders() {
		int[] value = {19};
		Notifier notifier = new Notifier();
		BlockProgramUniforms floats = new BlockProgramUniforms("fixture/dynamic_float", layout("float", "bool"));
		floats.uniform1i("count", () -> value[0], notifier);
		floats.update();
		if (floats.buffer().getFloat(0) != 19.0f || notifier.registrations != 1) {
			throw new AssertionError("dynamic float conversion or notifier registration failed");
		}
		for (int next : new int[]{-37, 0, Integer.MAX_VALUE}) {
			value[0] = next;
			UniformBlockBuffer.begin(floats.buffer());
			try {
				notifier.fire();
			} finally {
				UniformBlockBuffer.end();
			}
			if (floats.buffer().getFloat(0) != (float) next) {
				throw new AssertionError("a notified integer provider did not update its float declaration");
			}
			floats.update();
		}
		if (notifier.registrations != 1) {
			throw new AssertionError("dynamic conversion repeatedly registered its listener");
		}
	}

	private static void cachedProviders() {
		int[] count = {-13};
		boolean[] flag = {true};
		CustomUniforms.Builder builder = new CustomUniforms.Builder();
		builder.addVariable("int", "count", "sourceCount", true);
		builder.addVariable("bool", "flag", "sourceFlag", true);
		CustomUniforms custom = builder.build(inputs -> {
			inputs.uniform1i(UniformUpdateFrequency.PER_FRAME, "sourceCount", () -> count[0]);
			inputs.uniform1b(UniformUpdateFrequency.PER_FRAME, "sourceFlag", () -> flag[0]);
		});
		BlockProgramUniforms floats = new BlockProgramUniforms("fixture/cached_floats", layout("float", "float"));
		BlockProgramUniforms integers = new BlockProgramUniforms("fixture/cached_integers", layout("int", "bool"));
		Object floatPass = new Object();
		Object integerPass = new Object();
		custom.assignTo(floats);
		custom.assignTo(integers);
		custom.mapholderToPass(floats, floatPass);
		custom.mapholderToPass(integers, integerPass);
		for (int value : new int[]{-13, 0, 16_777_217, Integer.MIN_VALUE}) {
			count[0] = value;
			flag[0] = value != 0;
			custom.update();
			push(custom, floatPass, floats);
			push(custom, integerPass, integers);
			assertValues(floats, integers, count[0], flag[0]);
		}
		BlockProgramUniforms late = new BlockProgramUniforms("fixture/late_cached_floats", layout("float", "float"));
		custom.assignTo(late);
		push(custom, late, late);
		if (!late.unprovided().isEmpty() || late.buffer().getFloat(0) != (float) count[0] || late.buffer().getFloat(4) != 1.0f) {
			throw new AssertionError("a late program did not receive the cached scalar values");
		}
	}

	private static void push(CustomUniforms custom, Object pass, BlockProgramUniforms uniforms) {
		UniformBlockBuffer.begin(uniforms.buffer());
		try {
			custom.push(pass);
		} finally {
			UniformBlockBuffer.end();
		}
	}

	private static UniformBlock.Layout layout(String countType, String flagType) {
		return new UniformBlock.Layout(List.of(new UniformBlock.Member(countType, "count", 0, 0, null),
				new UniformBlock.Member(flagType, "flag", 0, 4, null)), 16);
	}

	private static void assertValues(BlockProgramUniforms floats, BlockProgramUniforms integers, int count, boolean flag) {
		if (floats.buffer().getFloat(0) != (float) count || floats.buffer().getFloat(4) != (flag ? 1.0f : 0.0f)
				|| integers.buffer().getInt(0) != count || integers.buffer().getInt(4) != (flag ? 1 : 0)) {
			throw new AssertionError("scalar uniforms were not stored using their declared types");
		}
	}

	private static final class Notifier implements ValueUpdateNotifier {
		private Runnable listener;
		private int registrations;

		@Override
		public void setListener(Runnable listener) {
			this.listener = listener;
			registrations++;
		}

		void fire() {
			listener.run();
		}
	}
}
