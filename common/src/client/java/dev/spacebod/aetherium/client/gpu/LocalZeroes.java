package dev.spacebod.aetherium.client.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Gives every variable of a compiled pack module that some path reads before writing the value zero, and every
 * undefined value the zero of its type.
 * <p>
 * GLSL leaves a variable declared without an initialiser undefined until it is written. OpenGL drivers hand packs a
 * zero there in practice, and packs came to rely on it (an accumulator never cleared, a colour only set on some
 * branches); shaderc keeps the {@code OpVariable} bare, and a Vulkan driver then returns whatever the register held,
 * which shows as black faces or flickering lines. The pass works on the SPIR-V because it already knows every
 * variable's type (arrays and structs included) and {@code OpConstantNull} zeroes any of them.
 * <p>
 * Only the variables that need it get the zero (zeroing every bare variable costs measurable frame time: most of them
 * are the compiler's temporaries): a must-be-stored walk over each function's blocks finds the ones some path reads
 * while not yet stored. Calls are summarised: shaderc passes every argument as a pointer to a temporary, so each
 * function is asked which of its parameters and which globals it may read before writing, and which it writes on every
 * path that returns. Summaries start at the most conservative answer (reads everything, defines nothing) and the
 * module is walked again until none moves; GLSL has no recursion, so this ends. Globals are judged from the entry
 * point.
 * <p>
 * Conservative where it cannot see: a store through an access chain writes part of a variable and so defines nothing;
 * a callee the module does not define reads everything and defines nothing. Both err towards the zero. A pointer the
 * walk cannot trace to a variable is left alone (shaderc emits none for GLSL, which has no pointer values).
 * <p>
 * A module optimised before the pass has its variables rewritten into values, where a read of one never written is an
 * {@code OpUndef}: those become the null constant of their type too.
 */
public final class LocalZeroes {
	/**
	 * What one pass did to one module.
	 *
	 * @param variables the bare variables given an initialiser
	 * @param undefs    the undefined values replaced by a constant
	 */
	public record Result(int[] words, int variables, int undefs) {
		public boolean changed() {
			return variables > 0 || undefs > 0;
		}
	}

	private static final int MAGIC = 0x07230203;
	private static final int HEADER_WORDS = 5;

	private static final int OP_UNDEF = 1;
	private static final int OP_ENTRY_POINT = 15;
	private static final int OP_TYPE_FIRST = 19;
	private static final int OP_TYPE_LAST = 39;
	private static final int OP_TYPE_IMAGE = 25;
	private static final int OP_TYPE_SAMPLER = 26;
	private static final int OP_TYPE_SAMPLED_IMAGE = 27;
	private static final int OP_TYPE_ARRAY = 28;
	private static final int OP_TYPE_RUNTIME_ARRAY = 29;
	private static final int OP_TYPE_STRUCT = 30;
	private static final int OP_TYPE_OPAQUE = 31;
	private static final int OP_TYPE_POINTER = 32;
	private static final int OP_TYPE_FORWARD_POINTER = 39;
	private static final int OP_CONSTANT_NULL = 46;
	private static final int OP_FUNCTION = 54;
	private static final int OP_FUNCTION_PARAMETER = 55;
	private static final int OP_FUNCTION_END = 56;
	private static final int OP_FUNCTION_CALL = 57;
	private static final int OP_VARIABLE = 59;
	private static final int OP_LOAD = 61;
	private static final int OP_STORE = 62;
	private static final int OP_COPY_MEMORY = 63;
	private static final int OP_ACCESS_CHAIN = 65;
	private static final int OP_IN_BOUNDS_ACCESS_CHAIN = 66;
	private static final int OP_PTR_ACCESS_CHAIN = 67;
	private static final int OP_COPY_OBJECT = 83;
	private static final int OP_LABEL = 248;
	private static final int OP_BRANCH = 249;
	private static final int OP_BRANCH_CONDITIONAL = 250;
	private static final int OP_SWITCH = 251;
	private static final int OP_KILL = 252;
	private static final int OP_RETURN = 253;
	private static final int OP_RETURN_VALUE = 254;
	private static final int OP_UNREACHABLE = 255;
	private static final int OP_TERMINATE_INVOCATION = 4416;

	private static final int STORAGE_PRIVATE = 6;
	private static final int STORAGE_FUNCTION = 7;

	/** The instruction header of an OpConstantNull (three words). */
	private static final int CONSTANT_NULL_HEAD = (3 << 16) | OP_CONSTANT_NULL;
	/** The instruction header of an OpVariable with an initialiser (five words). */
	private static final int VARIABLE_WITH_INITIALISER_HEAD = (5 << 16) | OP_VARIABLE;
	/**
	 * Rounds the summaries get before the pass stops refining them. Each round settles at least one more level of
	 * callers; a module that has not settled by then keeps conservative summaries and zeroes more, never less.
	 */
	private static final int MOST_ROUNDS = 32;

	private LocalZeroes() {
	}

	/**
	 * {@code spirv} (native order, position 0, native memory from {@link MemoryUtil}) with its read-before-written
	 * variables zeroed: the same buffer when nothing changed, else a new {@link MemoryUtil#memCalloc} buffer, in which
	 * case {@code spirv} has been freed.
	 */
	public static ByteBuffer patch(ByteBuffer spirv) {
		int[] words = new int[spirv.remaining() / 4];
		spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
		Result result = apply(words);
		if (!result.changed()) {
			return spirv;
		}
		ByteBuffer zeroed = MemoryUtil.memCalloc(result.words().length * 4);
		zeroed.order(ByteOrder.nativeOrder()).asIntBuffer().put(result.words());
		MemoryUtil.memFree(spirv);
		return zeroed;
	}

	/**
	 * The module with every variable some path reads before writing initialised to zero, and every undefined value
	 * replaced by the zero of its type.
	 *
	 * @param words the module in the platform's word order, the magic number first
	 * @return the same array when nothing needed changing or the words are not readable SPIR-V, a new one otherwise
	 */
	public static Result apply(int[] words) {
		if (!readable(words)) {
			return new Result(words, 0, 0);
		}
		Types types = Types.read(words);

		// Bare globals in declaration order (the index every function knows them by, behind its own parameters), the
		// entry points, and the undefined values wherever they stand.
		List<Integer> entryPoints = new ArrayList<>();
		List<Integer> privateIds = new ArrayList<>();
		List<Integer> privateAt = new ArrayList<>();
		Map<Integer, Integer> undefAt = new LinkedHashMap<>(); // instruction position -> type
		int at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			if (opcode == OP_ENTRY_POINT && count >= 3) {
				entryPoints.add(words[at + 2]);
			} else if (opcode == OP_VARIABLE && count == 4 && words[at + 3] == STORAGE_PRIVATE && types.zeroablePointee(words[at + 1]) != null) {
				privateIds.add(words[at + 2]);
				privateAt.add(at);
			} else if (opcode == OP_UNDEF && count == 3 && types.zeroable(words[at + 1])) {
				undefAt.put(at, words[at + 1]);
			}
			at += count;
		}

		List<Function> functions = new ArrayList<>();
		at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			if ((words[at] & 0xFFFF) == OP_FUNCTION) {
				Function function = Function.read(words, at, types, privateIds);
				functions.add(function);
				at = function.end();
				continue;
			}
			at += count;
		}

		Map<Integer, Integer> zeroAt = new LinkedHashMap<>(); // instruction position -> pointee type
		readFirst(words, functions, entryPoints, privateAt, types, zeroAt);
		if (zeroAt.isEmpty() && undefAt.isEmpty()) {
			return new Result(words, 0, 0);
		}

		// One null constant per pointee type, in first-use order (deterministic output). An undefined value keeps its
		// id; its constant is declared right behind its type, which precedes every use of the value.
		Map<Integer, Integer> nulls = new LinkedHashMap<>();
		Map<Integer, List<Integer>> undefsByType = new LinkedHashMap<>();
		int bound = words[3];
		for (int pointee : zeroAt.values()) {
			if (!nulls.containsKey(pointee)) {
				nulls.put(pointee, bound++);
			}
		}
		for (Map.Entry<Integer, Integer> undef : undefAt.entrySet()) {
			undefsByType.computeIfAbsent(undef.getValue(), k -> new ArrayList<>()).add(words[undef.getKey() + 2]);
		}

		int[] out = new int[words.length + 3 * nulls.size() + zeroAt.size()];
		System.arraycopy(words, 0, out, 0, HEADER_WORDS);
		out[3] = bound;
		int to = HEADER_WORDS;
		at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			Integer pointee = zeroAt.get(at);
			if (pointee != null) {
				out[to] = VARIABLE_WITH_INITIALISER_HEAD;
				out[to + 1] = words[at + 1];
				out[to + 2] = words[at + 2];
				out[to + 3] = words[at + 3];
				out[to + 4] = nulls.get(pointee);
				to += 5;
				at += count;
				continue;
			}
			if (undefAt.containsKey(at)) {
				at += count; // declared behind its type instead, under the same id
				continue;
			}
			System.arraycopy(words, at, out, to, count);
			to += count;
			at += count;
			// Right behind the type it zeroes: after the type, before any pointer to it and so before any variable of it.
			// A forward pointer declaration names no type of its own.
			if (opcode >= OP_TYPE_FIRST && opcode <= OP_TYPE_LAST && opcode != OP_TYPE_FORWARD_POINTER && count >= 2) {
				int type = words[at - count + 1];
				Integer zero = nulls.get(type);
				if (zero != null) {
					out[to] = CONSTANT_NULL_HEAD;
					out[to + 1] = type;
					out[to + 2] = zero;
					to += 3;
				}
				for (int id : undefsByType.getOrDefault(type, List.of())) {
					out[to] = CONSTANT_NULL_HEAD;
					out[to + 1] = type;
					out[to + 2] = id;
					to += 3;
				}
			}
		}
		return new Result(out, zeroAt.size(), undefAt.size());
	}

	/** The magic number first, and every instruction's word count inside the module. */
	private static boolean readable(int[] words) {
		if (words.length < HEADER_WORDS || words[0] != MAGIC) {
			return false;
		}
		int at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			if (count == 0 || at + count > words.length) {
				return false;
			}
			at += count;
		}
		return true;
	}

	/** The module's type declarations: each type's opcode and the types it is made of. */
	private record Types(Map<Integer, Integer> opcodes, Map<Integer, int[]> members, Map<Integer, Integer> pointees) {
		static Types read(int[] words) {
			Map<Integer, Integer> opcodes = new HashMap<>();
			Map<Integer, int[]> members = new HashMap<>();
			Map<Integer, Integer> pointees = new HashMap<>();
			int at = HEADER_WORDS;
			while (at < words.length) {
				int count = words[at] >>> 16;
				int opcode = words[at] & 0xFFFF;
				// A forward pointer's first operand is the pointer type it announces, not a result id of its own.
				if (opcode >= OP_TYPE_FIRST && opcode <= OP_TYPE_LAST && opcode != OP_TYPE_FORWARD_POINTER && count >= 2) {
					int id = words[at + 1];
					opcodes.put(id, opcode);
					if (opcode == OP_TYPE_POINTER && count >= 4) {
						pointees.put(id, words[at + 3]);
					} else if (opcode == OP_TYPE_ARRAY && count >= 3) {
						members.put(id, new int[]{words[at + 2]});
					} else if (opcode == OP_TYPE_STRUCT) {
						int[] made = new int[count - 2];
						System.arraycopy(words, at + 2, made, 0, made.length);
						members.put(id, made);
					}
				}
				at += count;
			}
			return new Types(opcodes, members, pointees);
		}

		/** The pointee a null constant would name for a variable of this pointer type, or null when it may not. */
		Integer zeroablePointee(int pointerType) {
			Integer pointee = pointees.get(pointerType);
			return pointee != null && zeroable(pointee) ? pointee : null;
		}

		/** Whether {@code OpConstantNull} may name the type: no opaque handle, runtime array or pointer at any depth. */
		boolean zeroable(int type) {
			Integer opcode = opcodes.get(type);
			if (opcode == null || opcode == OP_TYPE_IMAGE || opcode == OP_TYPE_SAMPLER || opcode == OP_TYPE_SAMPLED_IMAGE
					|| opcode == OP_TYPE_RUNTIME_ARRAY || opcode == OP_TYPE_OPAQUE || opcode == OP_TYPE_POINTER) {
				return false;
			}
			for (int member : members.getOrDefault(type, new int[0])) {
				if (!zeroable(member)) {
					return false;
				}
			}
			return true;
		}
	}

	/** One basic block: where its instructions start and end, where it goes, whether it returns. */
	private record Block(int start, int end, List<Integer> successors, boolean returns) {
	}

	/**
	 * What a call site may assume of a function, over its parameters and then the module's bare globals: which it can
	 * read before writing them, and which it has written on every path that returns.
	 */
	private record Summary(BitSet readsFirst, BitSet defines) {
		/** A function nothing is known about: reads everything, defines nothing. */
		static Summary unknown(int shared) {
			BitSet reads = new BitSet(shared);
			reads.set(0, shared);
			return new Summary(reads, new BitSet(shared));
		}
	}

	/**
	 * One function: its blocks and the pointers the walk tracks, one index each (its parameters, then the module's bare
	 * globals, then its own bare locals). Aliases are the access chains and copies made of a tracked pointer.
	 *
	 * @param shared  indices taken by the parameters and the globals (what a summary speaks about)
	 * @param localAt the instruction position of each tracked local, by its index less {@code shared}
	 */
	private record Function(int id, int end, int parameters, int shared, List<Block> blocks, Map<Integer, Integer> labelToBlock,
			Map<Integer, Integer> index, List<Integer> localAt, Map<Integer, Integer> aliases) {
		static Function read(int[] words, int start, Types types, List<Integer> privateIds) {
			int id = words[start + 2];
			List<Block> blocks = new ArrayList<>();
			Map<Integer, Integer> labelToBlock = new HashMap<>();
			Map<Integer, Integer> index = new LinkedHashMap<>();
			List<Integer> localAt = new ArrayList<>();
			Map<Integer, Integer> aliases = new HashMap<>();
			int parameters = 0;
			int at = start + (words[start] >>> 16);
			while (at < words.length && (words[at] & 0xFFFF) == OP_FUNCTION_PARAMETER) {
				// Every parameter takes an index (opaque ones too), so call-site argument positions line up with summaries.
				if (types.zeroablePointee(words[at + 1]) != null) {
					index.put(words[at + 2], parameters);
				}
				parameters++;
				at += words[at] >>> 16;
			}
			for (int k = 0; k < privateIds.size(); k++) {
				index.put(privateIds.get(k), parameters + k);
			}
			int shared = parameters + privateIds.size();
			int blockStart = -1;
			while (at < words.length) {
				int count = words[at] >>> 16;
				int opcode = words[at] & 0xFFFF;
				if (opcode == OP_FUNCTION_END) {
					at += count;
					break;
				}
				if (opcode == OP_LABEL) {
					blockStart = at;
					labelToBlock.put(words[at + 1], blocks.size());
				} else if (opcode == OP_VARIABLE && count == 4 && words[at + 3] == STORAGE_FUNCTION) {
					if (types.zeroablePointee(words[at + 1]) != null) {
						index.put(words[at + 2], shared + localAt.size());
						localAt.add(at);
					}
				} else if (opcode == OP_ACCESS_CHAIN || opcode == OP_IN_BOUNDS_ACCESS_CHAIN || opcode == OP_PTR_ACCESS_CHAIN
						|| opcode == OP_COPY_OBJECT) {
					// Module order: a definition precedes its uses in the structured control flow shaderc emits.
					Integer base = base(words[at + 3], index, aliases);
					if (base != null) {
						aliases.put(words[at + 2], base);
					}
				} else if (terminator(opcode)) {
					blocks.add(new Block(blockStart, at + count, successors(words, at), opcode == OP_RETURN || opcode == OP_RETURN_VALUE));
					blockStart = -1;
				}
				at += count;
			}
			return new Function(id, at, parameters, shared, blocks, labelToBlock, index, localAt, aliases);
		}

		int tracked() {
			return shared + localAt.size();
		}
	}

	/**
	 * Puts into {@code zeroAt} every bare variable some path reads before a store reaches it: a local by the walk of its
	 * function, a global by the walk of each entry point, the summaries refined together until none moves.
	 */
	private static void readFirst(int[] words, List<Function> functions, List<Integer> entryPoints, List<Integer> privateAt, Types types,
			Map<Integer, Integer> zeroAt) {
		Map<Integer, Function> byId = new HashMap<>();
		Map<Integer, Summary> summaries = new HashMap<>();
		for (Function function : functions) {
			byId.put(function.id(), function);
			summaries.put(function.id(), Summary.unknown(function.shared()));
		}
		Map<Integer, BitSet> needs = new HashMap<>();
		for (int round = 0; round < MOST_ROUNDS; round++) {
			boolean moved = false;
			for (Function function : functions) {
				BitSet read = new BitSet(function.tracked());
				Summary summary = walk(words, function, byId, summaries, read);
				needs.put(function.id(), read);
				if (!summary.equals(summaries.get(function.id()))) {
					summaries.put(function.id(), summary);
					moved = true;
				}
			}
			if (!moved) {
				break;
			}
		}
		for (Function function : functions) {
			BitSet read = needs.get(function.id());
			boolean root = entryPoints.contains(function.id());
			for (int index = read.nextSetBit(0); index >= 0; index = read.nextSetBit(index + 1)) {
				int position;
				if (index >= function.shared()) {
					position = function.localAt().get(index - function.shared());
				} else if (root && index >= function.parameters()) {
					position = privateAt.get(index - function.parameters());
				} else {
					continue;
				}
				zeroAt.put(position, types.zeroablePointee(words[position + 1]));
			}
		}
	}

	/**
	 * The must-be-stored analysis over one function (entry block starts empty, every other block full, sets only
	 * shrink). Marks in {@code read} every tracked pointer some block reads while not yet stored, and returns what the
	 * parameters and globals look like from a call site.
	 */
	private static Summary walk(int[] words, Function function, Map<Integer, Function> byId, Map<Integer, Summary> summaries, BitSet read) {
		int tracked = function.tracked();
		List<Block> blocks = function.blocks();
		if (blocks.isEmpty()) {
			return Summary.unknown(function.shared());
		}
		List<List<Integer>> predecessors = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			predecessors.add(new ArrayList<>());
		}
		for (int i = 0; i < blocks.size(); i++) {
			for (int label : blocks.get(i).successors()) {
				Integer target = function.labelToBlock().get(label);
				if (target != null) {
					predecessors.get(target).add(i);
				}
			}
		}
		BitSet[] in = new BitSet[blocks.size()];
		BitSet[] out = new BitSet[blocks.size()];
		for (int i = 0; i < blocks.size(); i++) {
			in[i] = new BitSet(tracked);
			if (i > 0) {
				in[i].set(0, tracked);
			}
			out[i] = transfer(words, function, blocks.get(i), in[i], byId, summaries, null);
		}
		boolean moved = true;
		while (moved) {
			moved = false;
			for (int i = 1; i < blocks.size(); i++) {
				List<Integer> preds = predecessors.get(i);
				if (preds.isEmpty()) {
					continue;
				}
				BitSet meet = new BitSet(tracked);
				meet.set(0, tracked);
				for (int pred : preds) {
					meet.and(out[pred]);
				}
				if (!meet.equals(in[i])) {
					in[i] = meet;
					out[i] = transfer(words, function, blocks.get(i), meet, byId, summaries, null);
					moved = true;
				}
			}
		}
		// Defined on every path that returns (a path that kills hands nothing back). No returning block: defines all.
		BitSet defined = new BitSet(tracked);
		defined.set(0, tracked);
		for (int i = 0; i < blocks.size(); i++) {
			transfer(words, function, blocks.get(i), in[i], byId, summaries, read);
			if (blocks.get(i).returns()) {
				defined.and(out[i]);
			}
		}
		return new Summary(read.get(0, function.shared()), defined.get(0, function.shared()));
	}

	/**
	 * Runs one block from a set of definitely stored pointers and returns the set at its end; with {@code read}, also
	 * marks every pointer the block reads while not yet stored.
	 */
	private static BitSet transfer(int[] words, Function function, Block block, BitSet in, Map<Integer, Function> byId, Map<Integer, Summary> summaries,
			BitSet read) {
		BitSet defined = (BitSet) in.clone();
		Map<Integer, Integer> index = function.index();
		Map<Integer, Integer> aliases = function.aliases();
		int at = block.start();
		while (at < block.end()) {
			int count = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			switch (opcode) {
				case OP_STORE -> define(words[at + 1], defined, index);
				case OP_LOAD -> use(words[at + 3], defined, index, aliases, read);
				case OP_COPY_MEMORY -> {
					use(words[at + 2], defined, index, aliases, read);
					define(words[at + 1], defined, index);
				}
				case OP_FUNCTION_CALL -> call(words, at, function, byId, summaries, defined, read);
				default -> {
					// Nothing else shaderc emits for GLSL reads or writes a variable through its pointer.
				}
			}
			at += count;
		}
		return defined;
	}

	/**
	 * A call: each argument the callee may read first is read here, each it writes on every path is defined here, and
	 * the globals likewise under the callee's own indices.
	 */
	private static void call(int[] words, int at, Function caller, Map<Integer, Function> byId, Map<Integer, Summary> summaries, BitSet defined,
			BitSet read) {
		int count = words[at] >>> 16;
		Function callee = byId.get(words[at + 3]);
		Summary summary = summaries.get(words[at + 3]);
		Map<Integer, Integer> index = caller.index();
		Map<Integer, Integer> aliases = caller.aliases();
		for (int argument = 0; argument + 4 < count; argument++) {
			int pointer = words[at + 4 + argument];
			if (summary == null || summary.readsFirst().get(argument)) {
				use(pointer, defined, index, aliases, read);
			}
			if (summary != null && summary.defines().get(argument)) {
				define(pointer, defined, index);
			}
		}
		if (callee == null || summary == null) {
			// A callee the module does not define reads every global and defines none.
			for (int k = caller.parameters(); k < caller.shared(); k++) {
				if (!defined.get(k) && read != null) {
					read.set(k);
				}
			}
			return;
		}
		int globals = caller.shared() - caller.parameters();
		for (int k = 0; k < globals; k++) {
			int here = caller.parameters() + k;
			int there = callee.parameters() + k;
			if (summary.readsFirst().get(there) && !defined.get(here) && read != null) {
				read.set(here);
			}
			if (summary.defines().get(there)) {
				defined.set(here);
			}
		}
	}

	/** A store through the variable's own pointer defines it whole; one through a chain does not. */
	private static void define(int pointer, BitSet defined, Map<Integer, Integer> index) {
		Integer whole = index.get(pointer);
		if (whole != null) {
			defined.set(whole);
		}
	}

	/** A read: the tracked variable behind the pointer, if not yet stored, needs its zero. */
	private static void use(int pointer, BitSet defined, Map<Integer, Integer> index, Map<Integer, Integer> aliases, BitSet read) {
		Integer base = base(pointer, index, aliases);
		if (base != null && !defined.get(base) && read != null) {
			read.set(base);
		}
	}

	private static Integer base(int pointer, Map<Integer, Integer> index, Map<Integer, Integer> aliases) {
		Integer direct = index.get(pointer);
		return direct != null ? direct : aliases.get(pointer);
	}

	private static boolean terminator(int opcode) {
		return opcode == OP_BRANCH || opcode == OP_BRANCH_CONDITIONAL || opcode == OP_SWITCH || opcode == OP_KILL || opcode == OP_RETURN
				|| opcode == OP_RETURN_VALUE || opcode == OP_UNREACHABLE || opcode == OP_TERMINATE_INVOCATION;
	}

	/** The labels a terminator can go to (a switch's literals read as single words). */
	private static List<Integer> successors(int[] words, int at) {
		int count = words[at] >>> 16;
		int opcode = words[at] & 0xFFFF;
		List<Integer> targets = new ArrayList<>();
		if (opcode == OP_BRANCH) {
			targets.add(words[at + 1]);
		} else if (opcode == OP_BRANCH_CONDITIONAL) {
			targets.add(words[at + 2]);
			targets.add(words[at + 3]);
		} else if (opcode == OP_SWITCH) {
			targets.add(words[at + 2]);
			for (int label = at + 4; label < at + count; label += 2) {
				targets.add(words[label]);
			}
		}
		return targets;
	}
}
