package dev.spacebod.aetherium.client.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.lwjgl.system.MemoryUtil;

/**
 * Drops the names of a module's private code from its SPIR-V: functions, their locals and parameters, private and
 * workgroup globals, and plain structs ({@code OpName}, {@code OpMemberName}). MoltenVK translates SPIR-V to Metal
 * Shading Language with these names, Metal's language is C++, and a pack name that collides with a word Metal owns
 * ({@code bias}, {@code length_squared}, {@code new} ...) makes Apple's compiler refuse the whole stage. Without names
 * the translator makes up its own.
 * <p>
 * What vanilla reads by name stays named: every stage input and output, uniform and storage variable, sampler, push
 * constant, and the blocks those are (with their members). Those are also what the lowering keeps clear of Metal's
 * words ({@code VulkanLowering.reservedNames}).
 */
public final class DebugNames {
	private static final int MAGIC = 0x07230203;
	private static final int HEADER_WORDS = 5;
	private static final int OP_NAME = 5;
	private static final int OP_MEMBER_NAME = 6;
	private static final int OP_TYPE_ARRAY = 28;
	private static final int OP_TYPE_RUNTIME_ARRAY = 29;
	private static final int OP_TYPE_STRUCT = 30;
	private static final int OP_TYPE_POINTER = 32;
	private static final int OP_VARIABLE = 59;
	private static final int OP_DECORATE = 71;
	private static final int DECORATION_BLOCK = 2;
	private static final int DECORATION_BUFFER_BLOCK = 3;
	/** Storage classes vanilla's reflection and stage matching read: UniformConstant, Input, Uniform, Output, PushConstant, StorageBuffer. */
	private static final Set<Integer> NAMED_STORAGE = Set.of(0, 1, 2, 3, 9, 12);

	private DebugNames() {
	}

	/**
	 * {@code spirv} (native order, native memory from {@link MemoryUtil}) without private names: the same buffer when
	 * nothing changed, else a new {@link MemoryUtil#memCalloc} buffer, in which case {@code spirv} has been freed.
	 */
	public static ByteBuffer strip(ByteBuffer spirv) {
		int[] words = new int[spirv.remaining() / 4];
		spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
		int[] stripped = apply(words);
		if (stripped == words) {
			return spirv;
		}
		ByteBuffer out = MemoryUtil.memCalloc(stripped.length * 4);
		out.order(ByteOrder.nativeOrder()).asIntBuffer().put(stripped);
		MemoryUtil.memFree(spirv);
		return out;
	}

	/**
	 * The module without private names.
	 *
	 * @param words the module in the platform's word order, the magic number first
	 * @return the same array when there was nothing to drop or the words are not readable SPIR-V, a new one otherwise
	 */
	public static int[] apply(int[] words) {
		if (words.length < HEADER_WORDS || words[0] != MAGIC) {
			return words;
		}
		Map<Integer, Integer> pointee = new HashMap<>();
		Map<Integer, int[]> structMembers = new HashMap<>();
		Map<Integer, Integer> arrayElement = new HashMap<>();
		Set<Integer> kept = new HashSet<>();
		Set<Integer> roots = new HashSet<>();
		for (int at = HEADER_WORDS; at < words.length; ) {
			int count = words[at] >>> 16;
			int op = words[at] & 0xFFFF;
			if (count == 0 || at + count > words.length) {
				return words;
			}
			switch (op) {
				case OP_TYPE_POINTER -> pointee.put(words[at + 1], words[at + 3]);
				case OP_TYPE_STRUCT -> {
					int[] members = new int[count - 2];
					System.arraycopy(words, at + 2, members, 0, members.length);
					structMembers.put(words[at + 1], members);
				}
				case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> arrayElement.put(words[at + 1], words[at + 2]);
				case OP_VARIABLE -> {
					if (NAMED_STORAGE.contains(words[at + 3])) {
						kept.add(words[at + 2]);
						Integer type = pointee.get(words[at + 1]);
						if (type != null) {
							roots.add(type);
						}
					}
				}
				case OP_DECORATE -> {
					if (count >= 3 && (words[at + 2] == DECORATION_BLOCK || words[at + 2] == DECORATION_BUFFER_BLOCK)) {
						roots.add(words[at + 1]);
					}
				}
				default -> {
				}
			}
			at += count;
		}
		// The types of named variables, through arrays and nested structs, keep their names and member names.
		java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>(roots);
		while (!pending.isEmpty()) {
			int type = pending.pop();
			Integer element = arrayElement.get(type);
			if (element != null) {
				pending.push(element);
				continue;
			}
			int[] members = structMembers.get(type);
			if (members != null && kept.add(type)) {
				for (int member : members) {
					pending.push(member);
				}
			}
		}
		int[] out = new int[words.length];
		System.arraycopy(words, 0, out, 0, HEADER_WORDS);
		int length = HEADER_WORDS;
		boolean dropped = false;
		for (int at = HEADER_WORDS; at < words.length; ) {
			int count = words[at] >>> 16;
			int op = words[at] & 0xFFFF;
			if ((op == OP_NAME || op == OP_MEMBER_NAME) && !kept.contains(words[at + 1])) {
				dropped = true;
			} else {
				System.arraycopy(words, at, out, length, count);
				length += count;
			}
			at += count;
		}
		return dropped ? java.util.Arrays.copyOf(out, length) : words;
	}
}
