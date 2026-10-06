package dev.spacebod.aetherium.microbench;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * The interface of one SPIR-V module as sorted text lines: stage inputs and outputs (location, name, type), uniform
 * blocks (set, members with offsets and types), sampled and storage images, storage buffers. Two lowerings of the same
 * program must produce the same lines (formatting differences in the GLSL do not show here; interface differences do).
 * Graphics programs bind set-0 resources by name (vanilla assigns the bindings), so set-0 binding numbers are left out
 * for them; set 1 and compute programs keep theirs.
 */
final class SpirvInterface {
	private static final int DECORATION_LOCATION = 30;
	private static final int DECORATION_BINDING = 33;
	private static final int DECORATION_SET = 34;

	private SpirvInterface() {
	}

	static List<String> reflect(ByteBuffer spirv, boolean compute) {
		TreeSet<String> lines = new TreeSet<>();
		IntBuffer words = spirv.asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer ptr = stack.callocPointer(1);
			check(Spvc.spvc_context_create(ptr), "context");
			long context = ptr.get(0);
			try {
				check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), ptr), "parse");
				long ir = ptr.get(0);
				check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), "compiler");
				long compiler = ptr.get(0);
				check(Spvc.spvc_compiler_create_shader_resources(compiler, ptr), "resources");
				long resources = ptr.get(0);
				list(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, "in", lines, compute, stack);
				list(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, "out", lines, compute, stack);
				list(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, "ubo", lines, compute, stack);
				list(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, "ssbo", lines, compute, stack);
				list(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, "sampler", lines, compute, stack);
				list(compiler, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, "image", lines, compute, stack);
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
		return new ArrayList<>(lines);
	}

	private static void list(long compiler, long resources, int type, String kind, TreeSet<String> lines, boolean compute, MemoryStack outer) {
		try (MemoryStack stack = outer.push()) {
			PointerBuffer list = stack.callocPointer(1);
			PointerBuffer count = stack.callocPointer(1);
			check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, count), "list");
			SpvcReflectedResource.Buffer items = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
			for (int i = 0; i < count.get(0); i++) {
				SpvcReflectedResource r = items.get(i);
				String name = r.nameString();
				StringBuilder line = new StringBuilder(kind).append(' ');
				if (kind.equals("in") || kind.equals("out")) {
					line.append("location=").append(Spvc.spvc_compiler_get_decoration(compiler, r.id(), DECORATION_LOCATION)).append(' ');
				} else {
					int set = Spvc.spvc_compiler_get_decoration(compiler, r.id(), DECORATION_SET);
					line.append("set=").append(set).append(' ');
					if (compute || set != 0) {
						line.append("binding=").append(Spvc.spvc_compiler_get_decoration(compiler, r.id(), DECORATION_BINDING)).append(' ');
					}
				}
				line.append(name).append(' ').append(type(compiler, r.type_id()));
				if (kind.equals("sampler") && Spvc.spvc_type_get_image_is_depth(Spvc.spvc_compiler_get_type_handle(compiler, r.base_type_id()))) {
					line.append(" depth"); // a depth-comparison image (sampler2DShadow)
				}
				if (kind.equals("ubo") || kind.equals("ssbo")) {
					line.append(members(compiler, r.base_type_id(), stack));
				}
				lines.add(line.toString());
			}
		}
	}

	private static String members(long compiler, int baseTypeId, MemoryStack stack) {
		long type = Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId);
		int n = Spvc.spvc_type_get_num_member_types(type);
		StringBuilder b = new StringBuilder(" {");
		IntBuffer offset = stack.callocInt(1);
		for (int i = 0; i < n; i++) {
			Spvc.spvc_compiler_type_struct_member_offset(compiler, type, i, offset);
			b.append(' ').append(Spvc.spvc_compiler_get_member_name(compiler, baseTypeId, i)).append('@').append(offset.get(0)).append(':')
					.append(type(compiler, Spvc.spvc_type_get_member_type(type, i)));
		}
		return b.append(" }").toString();
	}

	private static String type(long compiler, int typeId) {
		long type = Spvc.spvc_compiler_get_type_handle(compiler, typeId);
		StringBuilder b = new StringBuilder();
		b.append("t").append(Spvc.spvc_type_get_basetype(type)).append('x').append(Spvc.spvc_type_get_vector_size(type))
				.append('x').append(Spvc.spvc_type_get_columns(type));
		int dims = Spvc.spvc_type_get_num_array_dimensions(type);
		for (int d = 0; d < dims; d++) {
			b.append('[').append(Spvc.spvc_type_get_array_dimension(type, d)).append(']');
		}
		return b.toString().toLowerCase(Locale.ROOT);
	}

	private static void check(int result, String what) {
		if (result != Spvc.SPVC_SUCCESS) {
			throw new IllegalStateException("spvc " + what + " failed: " + result);
		}
	}
}
