package dev.spacebod.aetherium.shaders.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.spacebod.aetherium.shaders.AetheriumShaders;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.state.ShaderAttributeInputs;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.TransformPatcher;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweredProgram;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.LoweringParameters;
import dev.spacebod.aetherium.shaders.pipeline.transform.lowering.VulkanLowering;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaInterface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Aetherium Shaders: turns one pack world program (gbuffers_* or shadow*) into GLSL that vanilla 26.3's pipeline builder
 * accepts for a given vanilla vertex format: the transformer, then the Vulkan lowering on its syntax tree
 * ({@link VulkanLowering}: attributes, depth convention,
 * alpha test, sampler names, locations, outputs, the uniform block). Shared by the engine and the game-free compile
 * harness ({@code gradlew :common:microbench -Ponly=shaders}). Vanilla binds attributes by element name, so the
 * pipeline's vertex format is a renamed copy ({@link #shaderAttributeName}).
 */
public final class WorldInterface {
	/**
	 * Vanilla element names (and the ids element of {@link EntityVertexFormats}); the pipeline format renames them
	 * {@code aeth_<name>}, what the transformer declares.
	 */
	private static final Set<String> VANILLA_ATTRIBUTES = Set.of("Position", "Color", "UV0", "UV1", "UV2", "UV3", "Normal", "LineWidth",
			"ChunkPosition", "ChunkVisibility", "Entity");

	/** A world program ready for vanilla's pipeline builder. */
	public record Prepared(Map<PatchShaderType, String> stages, UniformBlock.Layout layout, Map<String, UniformType> resources,
			Map<String, String> samplerTypes, List<String> notes) {
	}

	private WorldInterface() {
	}

	/** The name a vanilla vertex element has in pack programs (and in the renamed pipeline format). */
	public static String shaderAttributeName(String elementName) {
		return VANILLA_ATTRIBUTES.contains(elementName) ? "aeth_" + elementName : elementName;
	}

	/** A copy of {@code format} with elements renamed for pack programs (same offsets, same stride, same step rate). */
	public static VertexFormat renamed(VertexFormat format) {
		VertexFormat.Builder builder = VertexFormat.builder(format.getStepRate());
		String previous = null;
		for (VertexFormatElement element : format.getElements()) {
			if (element.name().equals(previous)) {
				continue; // matrix columns are one attribute with several elements; re-added below with their stride
			}
			previous = element.name();
			List<VertexFormatElement> columns = format.getElements().stream().filter(e -> e.name().equals(element.name())).toList();
			int stride = columns.size() > 1 ? columns.get(1).offset() - columns.get(0).offset() : element.format().blockSize();
			builder.addAttribute(shaderAttributeName(element.name()), element.offset(), stride, element.format(), columns.size());
		}
		VertexFormat copy = builder.build();
		if (copy.getVertexSize() != format.getVertexSize()) {
			throw new IllegalStateException("cannot rename " + format + ": trailing padding (" + copy.getVertexSize() + " vs " + format.getVertexSize() + ")");
		}
		return copy;
	}

	/**
	 * @param formats      the vanilla pipeline's vertex bindings (index = binding; nulls allowed)
	 * @param source       where the model-view matrix and model offset come from
	 * @param drawBuffers  the program's colortex targets (fragment output i -> drawBuffers[i])
	 * @param attachments  the render pass's colour attachments, as colortex indices
	 */
	public static Prepared prepare(String name, String vertex, @Nullable String geometry, String fragment, List<@Nullable VertexFormat> formats,
			VanillaInterface.TransformSource source, AlphaTest alpha, boolean fullbright, boolean lines, boolean clouds, boolean glint, boolean text,
			int[] drawBuffers, int[] attachments) {
		return prepare(name, vertex, geometry, fragment, formats, source, alpha, fullbright, lines, clouds, glint, text, drawBuffers, attachments, false);
	}

	/**
	 * @param glDepth write OpenGL window depth ({@code z' = (z + w) / 2}, 1 = far) instead of vanilla's reversed depth, and
	 *                leave {@code gl_FragCoord.z}/{@code gl_FragDepth} as they are: the shadow pass, whose depth map packs
	 *                sample directly
	 */
	public static Prepared prepare(String name, String vertex, @Nullable String geometry, String fragment, List<@Nullable VertexFormat> formats,
			VanillaInterface.TransformSource source, AlphaTest alpha, boolean fullbright, boolean lines, boolean clouds, boolean glint, boolean text,
			int[] drawBuffers, int[] attachments, boolean glDepth) {
		return prepare(name, vertex, geometry, fragment, formats, source, alpha, fullbright, lines, clouds, glint, text, drawBuffers, attachments, glDepth,
				LoweringParameters.Glint.NONE, false);
	}

	/**
	 * @param singlePassGlint single-pass glint handling (26.3 draws enchanted items with the glint in the same draw)
	 */
	public static Prepared prepare(String name, String vertex, @Nullable String geometry, String fragment, List<@Nullable VertexFormat> formats,
			VanillaInterface.TransformSource source, AlphaTest alpha, boolean fullbright, boolean lines, boolean clouds, boolean glint, boolean text,
			int[] drawBuffers, int[] attachments, boolean glDepth, LoweringParameters.Glint singlePassGlint,
			boolean grayscaleAlbedo) {
		VertexFormat main = formats.isEmpty() ? null : formats.getFirst();
		ShaderAttributeInputs inputs;
		if (main != null) {
			inputs = new ShaderAttributeInputs(main, fullbright, lines, glint, text, false);
		} else if (clouds) {
			// Clouds have no vertex buffer: position and colour are pulled from the CloudFaces texel buffer.
			inputs = new ShaderAttributeInputs(true, false, false, false, false);
		} else {
			throw new IllegalArgumentException(name + ": pipeline has no vertex format at binding 0");
		}
		return lowered(TransformPatcher.patchVanillaLowered(name, vertex, geometry, null, null, fragment, alpha, lines, clouds, inputs, source,
				TexturePatching.current(), Lowering.world(attributes(formats), alpha, drawBuffers, attachments, glDepth, singlePassGlint, grayscaleAlbedo)));
	}

	/**
	 * A pack LOD program ({@code dh_terrain}, {@code dh_water}, {@code dh_shadow}) for the LOD mod's terrain pipelines,
	 * drawn from {@link #LOD_FORMAT}. The mod binds its own per-buffer block {@code vertUniqueUniformBlock} (the buffer's
	 * world-space origin), its lightmap ({@code uLightMap}) and block atlas ({@code uBlockAtlas}); the program declares
	 * those names so the mod's own {@code setUniform} calls feed it. {@code modelOffset} (camera-relative origin, what
	 * packs read) is that origin minus {@code aeth_lodCamera}.
	 */
	public static Prepared prepareLod(String name, String vertex, String fragment, int[] drawBuffers, int[] attachments, boolean glDepth) {
		return lowered(TransformPatcher.patchLodLowered(name, vertex, fragment, false, TexturePatching.current(),
				Lowering.lod(attributes(List.of(LOD_FORMAT)), drawBuffers, attachments, glDepth, false)));
	}

	/**
	 * A pack's {@code dh_generic} program (or the {@code dh_terrain} it falls back to) for the LOD mod's generic objects
	 * (LOD clouds, beacon beams): boxes pre-expanded into {@link #LOD_GENERIC_FORMAT} vertices, offset by the mod's own
	 * per-group block {@code vertUniformBlock} (declared with the mod's members, so its {@code setUniform} feeds it). The
	 * transformer's per-instance scale/translation are 1 and 0 here, and the face normal comes from the vertex index
	 * within its box (24 vertices, 4 per face), as the mod's own shader does.
	 */
	public static Prepared prepareLodGeneric(String name, String vertex, String fragment, int[] drawBuffers, int[] attachments) {
		return lowered(TransformPatcher.patchLodLowered(name, vertex, fragment, true, TexturePatching.current(),
				Lowering.lod(attributes(List.of(LOD_GENERIC_FORMAT)), drawBuffers, attachments, false, true)));
	}

	/** The LOD mod's 20-byte generic-object vertex: position, colour, material, three padding bytes. */
	public static final VertexFormat LOD_GENERIC_FORMAT = VertexFormat.builder(0)
			.addAttribute("vPosition", 0, GpuFormat.RGB32_FLOAT.blockSize(), GpuFormat.RGB32_FLOAT, 1)
			.addAttribute("aeth_color", 12, GpuFormat.RGBA8_UNORM.blockSize(), GpuFormat.RGBA8_UNORM, 1)
			.addAttribute("aMaterial", 16, GpuFormat.R8_UINT.blockSize(), GpuFormat.R8_UINT, 1)
			.addAttribute("aeth_lodPadding", 19, GpuFormat.R8_UINT.blockSize(), GpuFormat.R8_UINT, 1)
			.build();

	/**
	 * The LOD mod's 16-byte terrain vertex as the LOD programs read it: position xyz + meta (light, micro offset) as
	 * {@code uvec4 vPosition}, colour as {@code vec4 aeth_color}, material/normal/texture tile as {@code uvec4
	 * aethExtra}. Same bytes as the mod's own six-element format.
	 */
	public static final VertexFormat LOD_FORMAT = VertexFormat.builder(0)
			.addAttribute("vPosition", 0, GpuFormat.RGBA16_UINT.blockSize(), GpuFormat.RGBA16_UINT, 1)
			.addAttribute("aeth_color", 8, GpuFormat.RGBA8_UNORM.blockSize(), GpuFormat.RGBA8_UNORM, 1)
			.addAttribute("aethExtra", 12, GpuFormat.RGBA8_UINT.blockSize(), GpuFormat.RGBA8_UINT, 1)
			.build();

	private static Map<String, GpuFormat> attributes(List<@Nullable VertexFormat> formats) {
		Map<String, GpuFormat> attributes = new HashMap<>();
		for (VertexFormat format : formats) {
			if (format != null) {
				format.getElements().forEach(e -> attributes.putIfAbsent(shaderAttributeName(e.name()), e.format()));
			}
		}
		return attributes;
	}

	/** A program the tree lowering produced, as the engine consumes it. */
	private static Prepared lowered(LoweredProgram program) {
		return new Prepared(new EnumMap<>(program.stages()), program.layout(), program.resources(), program.samplerTypes(), program.notes());
	}

	/** {@link DevFlags#DUMP}: writes a converted program's stages to {@code shader-dump/} when its name matches. */
	static void dump(String name, Map<PatchShaderType, String> stages) {
		if (!DevFlags.dumps(name)) {
			return;
		}
		try {
			Path dir = Path.of("shader-dump");
			Files.createDirectories(dir);
			for (Map.Entry<PatchShaderType, String> e : stages.entrySet()) {
				if (e.getValue() != null) {
					String file = name.replaceAll("[^A-Za-z0-9_.-]", "_") + "." + e.getKey().name().toLowerCase(Locale.ROOT) + ".glsl";
					Files.writeString(dir.resolve(file), e.getValue());
				}
			}
		} catch (IOException e) {
			AetheriumShaders.logger.warn("dump of {} failed", name, e);
		}
	}
}
