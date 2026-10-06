package dev.spacebod.aetherium.shaders.chunks;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.WorldRenderingSettings;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.lwjgl.system.MemoryUtil;

/**
 * The chunk mesh vertex while a shader pack is configured: the terrain renderer's own compact vertex, unchanged (so its
 * own shader still draws the mesh), followed by what pack programs read and the compact vertex lacks:
 * <ul>
 *   <li>{@code Normal}: the quad's face normal from its corners;</li>
 *   <li>{@code mc_Entity}: block ID from block.properties (-1 unmapped) and 1 in y for fluids;</li>
 *   <li>{@code mc_midTexCoord}: the centre of the quad's texture;</li>
 *   <li>{@code at_tangent}: the texture's u direction in the quad's plane, w the handedness;</li>
 *   <li>{@code at_midBlock}: offset from the vertex to its block's centre in 1/64 blocks, light emission in w.</li>
 * </ul>
 * Everything is worked out once per quad in the encoder, from the exact vertex values before they are quantised. The
 * block a quad belongs to travels on its vertices ({@link ChunkVertexData}), since translucent quads reach the encoder
 * later, through the sorter's copies.
 *
 * <p>With {@code separateAo} the compact colour holds the unshaded tint and the vertex brightness in alpha, as packs
 * that treat occlusion themselves expect.
 */
public final class ChunkMeshFormat implements ChunkVertexType {
	/** The compact vertex's element names, read by the shader lowering. */
	public static final String POSITION = "a_Position";
	public static final String COLOR = "a_Color";
	public static final String TEXTURE = "a_TexCoord";
	public static final String LIGHT_AND_DATA = "a_LightAndData";
	/** The samplers the chunk renderer binds for every draw: block atlas and lightmap. */
	public static final String ALBEDO = "u_BlockTex";
	public static final String LIGHTMAP = "u_LightTex";

	private static final int NORMAL = 0;
	private static final int ENTITY = 4;
	private static final int MID_TEX = 8;
	private static final int TANGENT = 12;
	private static final int MID_BLOCK = 16;
	private static final int EXTRA = 20;
	/** Byte offset of the compact colour. */
	private static final int COMPACT_COLOR = 8;

	private final ChunkVertexType compact = ChunkMeshFormats.COMPACT;
	private final ChunkVertexEncoder compactEncoder = compact.getEncoder();
	private final int compactStride = compact.getVertexFormat().getVertexSize();
	private final VertexFormat format;
	private final int stride;
	private final ChunkVertexEncoder encoder = this::encode;

	public ChunkMeshFormat() {
		VertexFormat base = compact.getVertexFormat();
		VertexFormat.Builder builder = VertexFormat.builder(base.getStepRate());
		for (VertexFormatElement element : base.getElements()) {
			builder.addAttribute(element.name(), element.offset(), element.format().blockSize(), element.format(), 1);
		}
		int at = compactStride;
		at = add(builder, "Normal", GpuFormat.RGBA8_SNORM, at);
		at = add(builder, "mc_Entity", GpuFormat.RG16_SINT, at);
		at = add(builder, "mc_midTexCoord", GpuFormat.RG16_UNORM, at);
		at = add(builder, "at_tangent", GpuFormat.RGBA8_SNORM, at);
		add(builder, "at_midBlock", GpuFormat.RGBA8_SINT, at);
		format = builder.build();
		stride = format.getVertexSize();
		if (stride != compactStride + EXTRA) {
			throw new IllegalStateException("chunk vertex is " + stride + " bytes, expected " + (compactStride + EXTRA));
		}
	}

	private static int add(VertexFormat.Builder builder, String name, GpuFormat element, int at) {
		builder.addAttribute(name, at, element.blockSize(), element, 1);
		return at + element.blockSize();
	}

	@Override
	public VertexFormat getVertexFormat() {
		return format;
	}

	@Override
	public ChunkVertexEncoder getEncoder() {
		return encoder;
	}

	/**
	 * The compact encoder writes the four vertices at its own stride; they are spread to ours from the last one back (the
	 * first already sits in place), then each gets the quad's extra data.
	 */
	private long encode(long pointer, int materialBits, ChunkVertexEncoder.Vertex[] vertices, int sectionIndex) {
		compactEncoder.write(pointer, materialBits, vertices, sectionIndex);
		for (int i = vertices.length - 1; i > 0; i--) {
			MemoryUtil.memCopy(pointer + (long) i * compactStride, pointer + (long) i * stride, compactStride);
		}
		QuadFrame frame = QuadFrame.of(vertices);
		boolean ao = WorldRenderingSettings.INSTANCE.shouldUseSeparateAo();
		for (int i = 0; i < vertices.length; i++) {
			ChunkVertexEncoder.Vertex vertex = vertices[i];
			long p = pointer + (long) i * stride;
			if (ao) {
				MemoryUtil.memPutInt(p + COMPACT_COLOR, ColorABGR.withAlpha(vertex.color, vertex.ao));
			}
			long extra = p + compactStride;
			MemoryUtil.memPutInt(extra + NORMAL, frame.normal);
			ChunkVertexData data = (ChunkVertexData) vertex;
			MemoryUtil.memPutInt(extra + ENTITY, data.aetherium$entity());
			MemoryUtil.memPutInt(extra + MID_TEX, frame.midTexture);
			MemoryUtil.memPutInt(extra + TANGENT, frame.tangent);
			MemoryUtil.memPutInt(extra + MID_BLOCK, midBlock(data.aetherium$block(), vertex));
		}
		return pointer + (long) vertices.length * stride;
	}

	/** {@code at_midBlock}: the block's centre minus the vertex, in 1/64 blocks, and the block's light emission. */
	private static int midBlock(int block, ChunkVertexEncoder.Vertex vertex) {
		if (!ChunkVertexData.measured(block)) {
			return ChunkVertexData.emission(block) << 24;
		}
		return offset(ChunkVertexData.blockX(block), vertex.x)
				| offset(ChunkVertexData.blockY(block), vertex.y) << 8
				| offset(ChunkVertexData.blockZ(block), vertex.z) << 16
				| ChunkVertexData.emission(block) << 24;
	}

	private static int offset(int block, float vertex) {
		return Math.max(-128, Math.min(127, Math.round((block + 0.5F - vertex) * 64.0F))) & 0xFF;
	}
}
