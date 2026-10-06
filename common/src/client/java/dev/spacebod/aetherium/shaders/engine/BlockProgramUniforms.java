package dev.spacebod.aetherium.shaders.engine;

import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.state.ValueUpdateNotifier;
import dev.spacebod.aetherium.shaders.gl.uniform.DynamicLocationalUniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.Uniform;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformHolder;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformType;
import dev.spacebod.aetherium.shaders.gl.uniform.UniformUpdateFrequency;
import dev.spacebod.aetherium.shaders.uniforms.SystemTimeUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/**
 * One pack program's uniforms. The uniform definitions register against this holder; a "location" is the member's
 * byte offset in the program's std140 {@code AetheriumUniforms} block (from {@link UniformBlock}'s layout), and the
 * provided type is checked against the member's GLSL type. {@link #update()} runs the schedule (once, per tick, per
 * frame, dynamic every time) into {@link #buffer()}, which the engine binds as {@code AetheriumUniforms}.
 */
public final class BlockProgramUniforms implements DynamicLocationalUniformHolder {
	private final String programName;
	private final Map<String, UniformBlock.Member> members;
	private final ByteBuffer buffer;
	private final Set<String> assigned = new HashSet<>();
	private final List<Uniform> once = new ArrayList<>();
	private final List<Uniform> perTick = new ArrayList<>();
	private final List<Uniform> perFrame = new ArrayList<>();
	private final List<Uniform> dynamic = new ArrayList<>();
	private final List<ValueUpdateNotifier> notifiers = new ArrayList<>();
	private boolean first = true;
	private long lastTick = -1;
	private long lastFrame = -1;

	public BlockProgramUniforms(String programName, UniformBlock.Layout layout) {
		this.programName = programName;
		this.members = layout.byName();
		this.buffer = ByteBuffer.allocateDirect(Math.max(16, layout.size())).order(ByteOrder.nativeOrder());
	}

	/** Whether the program has an {@code AetheriumUniforms} block at all (no block: nothing to upload or bind). */
	public boolean hasBlock() {
		return !members.isEmpty();
	}

	/** The std140 bytes, current after {@link #update()}. */
	public ByteBuffer buffer() {
		return buffer;
	}

	/** Names the program declares that nothing provides (reported once at load). */
	public List<String> unprovided() {
		List<String> missing = new ArrayList<>();
		for (String name : members.keySet()) {
			if (!assigned.contains(name)) {
				missing.add(name);
			}
		}
		return missing;
	}

	@Override
	public BlockProgramUniforms addUniform(UniformUpdateFrequency updateFrequency, Uniform uniform) {
		switch (updateFrequency) {
			case ONCE -> once.add(uniform);
			case PER_TICK -> perTick.add(uniform);
			case PER_FRAME -> perFrame.add(uniform);
		}
		return this;
	}

	@Override
	public OptionalInt location(String name, UniformType type) {
		UniformBlock.Member member = members.get(name);
		if (member == null) {
			return OptionalInt.empty();
		}
		if (!assigned.add(name)) {
			AetheriumShaders.logger.warn("[{}] Duplicate uniform: {} {}", programName, type.toString().toLowerCase(), name);
			return OptionalInt.empty();
		}
		if (!compatible(type, member.type())) {
			AetheriumShaders.logger.error("[{}] Wrong uniform type for {}: provided {} but the program declares {}. Disabling that uniform.",
					programName, name, type, member.type());
			return OptionalInt.empty();
		}
		return OptionalInt.of(member.offset());
	}

	@Override
	public BlockProgramUniforms addDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier) {
		dynamic.add(uniform);
		notifiers.add(notifier);
		return this;
	}

	@Override
	public UniformHolder externallyManagedUniform(String name, UniformType type) {
		// Vanilla-managed values (matrices, fog) are provided by the engine directly.
		return this;
	}

	/**
	 * Runs the schedule into {@link #buffer()}. Returns whether any value was written (false: the bytes are what the last
	 * update left).
	 */
	public boolean update() {
		UniformBlockBuffer.begin(buffer);
		try {
			run(dynamic);
			boolean wrote = !dynamic.isEmpty();
			if (first) {
				first = false;
				writeInitializers();
				run(once);
				run(perTick);
				run(perFrame);
				lastTick = currentTick();
				lastFrame = SystemTimeUniforms.COUNTER.frameId();
				return true;
			}
			long tick = currentTick();
			if (tick != lastTick) {
				lastTick = tick;
				run(perTick);
				wrote |= !perTick.isEmpty();
			}
			long frame = SystemTimeUniforms.COUNTER.frameId();
			if (frame != lastFrame) {
				lastFrame = frame;
				run(perFrame);
				wrote |= !perFrame.isEmpty();
			}
			return wrote;
		} finally {
			UniformBlockBuffer.end();
		}
	}

	private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?|true|false");

	/**
	 * Declared initial values ({@code uniform float x = 1.0;}) of members nothing provides. Literal scalars and vector
	 * constructors only ({@code vec3(1.0)} broadcasts); anything else stays 0 and is logged.
	 */
	private void writeInitializers() {
		for (UniformBlock.Member member : members.values()) {
			String init = member.initializer();
			if (init == null || assigned.contains(member.name()) || member.type().startsWith("mat")) {
				continue;
			}
			Matcher m = NUMBER.matcher(init.replaceAll("[A-Za-z_]\\w*\\s*\\(", "("));
			List<String> values = new ArrayList<>();
			while (m.find()) {
				values.add(m.group());
			}
			String type = member.type();
			int components = Character.isDigit(type.charAt(type.length() - 1)) ? type.charAt(type.length() - 1) - '0' : 1;
			if (values.isEmpty() || values.size() != 1 && values.size() != components) {
				AetheriumShaders.logger.info("[{}] initial value of {} not understood ({}); reads 0", programName, member.name(), init);
				continue;
			}
			boolean integer = type.startsWith("i") || type.startsWith("u") || type.startsWith("b");
			for (int c = 0; c < components; c++) {
				String v = values.get(values.size() == 1 ? 0 : c);
				double d = v.equals("true") ? 1 : v.equals("false") ? 0 : Double.parseDouble(v);
				int at = member.offset() + c * 4;
				if (integer) {
					buffer.putInt(at, (int) d);
				} else {
					buffer.putFloat(at, (float) d);
				}
			}
		}
	}

	private static void run(List<Uniform> uniforms) {
		for (Uniform uniform : uniforms) {
			uniform.update();
		}
	}

	private static long currentTick() {
		return Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
	}

	private static boolean compatible(UniformType provided, String glslType) {
		return switch (provided) {
			case FLOAT -> glslType.equals("float");
			case INT -> glslType.equals("int") || glslType.equals("bool") || glslType.equals("uint");
			case VEC2 -> glslType.equals("vec2");
			case VEC2I -> glslType.equals("ivec2") || glslType.equals("bvec2");
			case VEC3 -> glslType.equals("vec3");
			case VEC3I -> glslType.equals("ivec3") || glslType.equals("bvec3");
			case VEC4 -> glslType.equals("vec4");
			case MAT3 -> glslType.equals("mat3");
			case MAT4 -> glslType.equals("mat4");
			default -> glslType.equals("ivec4") || glslType.equals("bvec4");
		};
	}
}
