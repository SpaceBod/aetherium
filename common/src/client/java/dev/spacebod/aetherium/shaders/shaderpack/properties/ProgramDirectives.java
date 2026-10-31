package dev.spacebod.aetherium.shaders.shaderpack.properties;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTest;
import dev.spacebod.aetherium.shaders.gl.blending.BlendModeOverride;
import dev.spacebod.aetherium.shaders.gl.blending.BufferBlendInformation;
import dev.spacebod.aetherium.shaders.gl.framebuffer.ViewportData;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.CommentDirective;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.CommentDirectiveParser;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.ConstDirectiveParser;
import dev.spacebod.aetherium.shaders.shaderpack.parsing.DispatchingDirectiveHolder;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProgramDirectives {
	private static final ImmutableList<String> LEGACY_RENDER_TARGETS = PackRenderTargetDirectives.LEGACY_RENDER_TARGETS;
	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
	private static final Pattern FRAGMENT_OUTPUT = Pattern.compile("\\b(?:gl_FragColor|gl_FragData|out)\\b");
	private static final Pattern SECOND_LEGACY_OUTPUT = Pattern.compile("\\bgl_FragData\\s*\\[\\s*1\\s*]");
	private static final Pattern OUTPUT_STORAGE = Pattern.compile("\\bout\\b");
	private static final Pattern OUTPUT_LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)\\s*(?:[,)]|$)");
	private static final Pattern OUTPUT_LAYOUT = Pattern.compile("\\blayout\\s*\\([^)]*\\)");
	private static final Pattern OUTPUT_NAME = Pattern.compile("\\b(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*])?\\s*$");

	private final int[] drawBuffers;
	private final ViewportData viewportScale;
	@Nullable
	private final AlphaTest alphaTestOverride;

	private final Optional<BlendModeOverride> blendModeOverride;
	private final List<BufferBlendInformation> bufferBlendInformations;
	private final ImmutableSet<Integer> mipmappedBuffers;
	private final ImmutableMap<Integer, Boolean> explicitFlips;
	private boolean unknownDrawBuffers;

	private ProgramDirectives(int[] drawBuffers, ViewportData viewportScale, @Nullable AlphaTest alphaTestOverride,
							  Optional<BlendModeOverride> blendModeOverride, List<BufferBlendInformation> bufferBlendInformations, ImmutableSet<Integer> mipmappedBuffers,
							  ImmutableMap<Integer, Boolean> explicitFlips) {
		this.drawBuffers = drawBuffers;
		this.viewportScale = viewportScale;
		this.alphaTestOverride = alphaTestOverride;
		this.blendModeOverride = blendModeOverride;
		this.bufferBlendInformations = bufferBlendInformations;
		this.mipmappedBuffers = mipmappedBuffers;
		this.explicitFlips = explicitFlips;
		this.unknownDrawBuffers = false;
	}

	public ProgramDirectives(ProgramSource source, ShaderProperties properties, Set<Integer> supportedRenderTargets,
							 @Nullable BlendModeOverride defaultBlendOverride) {
		// DRAWBUFFERS/RENDERTARGETS are only read from the fragment shader; whichever appears last wins.
		// Without either, colour programs assume 0; shadow programs include 1 only if a second output exists.
		// SEUS v08/v10 need 01234567 for world programs, but that breaks TAA in
		// Sildur's Vibrant Shaders (gbuffers_skybasic has no directive and would write garbage to colortex7).
		Optional<CommentDirective> optionalDrawbuffersDirective = findDrawbuffersDirective(source.getFragmentSource());
		Optional<CommentDirective> optionalRendertargetsDirective = findRendertargetsDirective(source.getFragmentSource());

		Optional<CommentDirective> optionalCommentDirective = getAppliedDirective(optionalDrawbuffersDirective, optionalRendertargetsDirective);
		drawBuffers = optionalCommentDirective.map(commentDirective -> {
			if (commentDirective.getType() == CommentDirective.Type.DRAWBUFFERS) {
				return parseDigits(commentDirective.getDirective().toCharArray());
			} else if (commentDirective.getType() == CommentDirective.Type.RENDERTARGETS) {
				return parseDigitList(commentDirective.getDirective());
			} else {
				throw new IllegalStateException("Unhandled comment directive type!");
			}
		}).orElseGet(() -> {
			unknownDrawBuffers = true;
			String fragment = source.getFragmentSource().orElse("");
			String name = source.getName();
			boolean shadow = name.equals("shadow") || name.startsWith("shadow_") || name.equals("dh_shadow");
			boolean world = shadow || name.startsWith("gbuffers_") || name.startsWith("dh_");
			// World placeholders must not reserve a phantom attachment and displace a real output.
			// Full-screen passes keep target 0: the frame loop uses their first target for sizing.
			if (world && !hasFragmentOutput(fragment)) {
				return new int[0];
			}
			return shadow && hasSecondFragmentOutput(fragment) ? new int[]{0, 1} : new int[]{0};
		});

		if (properties != null) {
			viewportScale = properties.getViewportScaleOverrides().getOrDefault(source.getName(), ViewportData.defaultValue());
			alphaTestOverride = properties.getAlphaTestOverrides().get(source.getName());

			BlendModeOverride blendModeOverride = properties.getBlendModeOverrides().get(source.getName());
			List<BufferBlendInformation> bufferBlendInformations = properties.getBufferBlendOverrides().get(source.getName());
			this.blendModeOverride = Optional.ofNullable(blendModeOverride != null ? blendModeOverride : defaultBlendOverride);
			this.bufferBlendInformations = bufferBlendInformations != null ? bufferBlendInformations : Collections.emptyList();

			explicitFlips = source.getParent().getPackDirectives().getExplicitFlips(source.getName());
		} else {
			viewportScale = ViewportData.defaultValue();
			alphaTestOverride = null;
			blendModeOverride = Optional.ofNullable(defaultBlendOverride);
			bufferBlendInformations = Collections.emptyList();
			explicitFlips = ImmutableMap.of();
		}

		HashSet<Integer> mipmappedBuffers = new HashSet<>();
		DispatchingDirectiveHolder directiveHolder = new DispatchingDirectiveHolder();

		supportedRenderTargets.forEach(index -> {
			BooleanConsumer mipmapHandler = shouldMipmap -> {
				if (shouldMipmap) {
					mipmappedBuffers.add(index);
				} else {
					mipmappedBuffers.remove(index);
				}
			};

			directiveHolder.acceptConstBooleanDirective("colortex" + index + "MipmapEnabled", mipmapHandler);

			if (index < LEGACY_RENDER_TARGETS.size()) {
				directiveHolder.acceptConstBooleanDirective(LEGACY_RENDER_TARGETS.get(index) + "MipmapEnabled", mipmapHandler);
			}
		});

		source.getFragmentSource().map(ConstDirectiveParser::findDirectives).ifPresent(directives -> {
			for (ConstDirectiveParser.ConstDirective directive : directives) {
				directiveHolder.processDirective(directive);
			}
		});

		this.mipmappedBuffers = ImmutableSet.copyOf(mipmappedBuffers);
	}

	public static boolean hasFragmentOutput(String source) {
		return FRAGMENT_OUTPUT.matcher(COMMENTS.matcher(source).replaceAll(" ")).find();
	}

	/** Recognize a second colour output without treating function out-parameters as render targets. */
	private static boolean hasSecondFragmentOutput(String source) {
		String code = COMMENTS.matcher(source).replaceAll(" ");
		if (SECOND_LEGACY_OUTPUT.matcher(code).find()) {
			return true;
		}
		boolean first = false;
		int implicit = 0;
		for (String statement : code.split("[;{}]")) {
			Matcher output = OUTPUT_STORAGE.matcher(statement);
			if (!output.find()) {
				continue;
			}
			String before = statement.substring(0, output.start());
			if (before.lastIndexOf('(') > before.lastIndexOf(')')) {
				continue;
			}
			Matcher location = OUTPUT_LOCATION.matcher(statement);
			String explicit = location.find() ? location.group(1) : null;
			String names = OUTPUT_LAYOUT.matcher(statement.substring(output.end())).replaceAll(" ");
			for (String declarator : names.split(",")) {
				Matcher name = OUTPUT_NAME.matcher(declarator);
				if (!name.find()) {
					continue;
				}
				boolean array = name.group(2) != null && !name.group(2).equals("1");
				if (explicit != null) {
					if (explicit.equals("1") || explicit.equals("0") && array) {
						return true;
					}
					first |= explicit.equals("0");
				} else if (name.group(1).equals("outColor1") || name.group(1).equals("outColor0") && array) {
					return true;
				} else if (name.group(1).equals("outColor0")) {
					first = true;
				} else if (!name.group(1).matches("outColor[2-7]")) {
					implicit += array ? 2 : 1;
				}
			}
		}
		return implicit >= (first ? 1 : 2);
	}

	private static Optional<CommentDirective> findDrawbuffersDirective(Optional<String> stageSource) {
		return stageSource.flatMap(fragment -> CommentDirectiveParser.findDirective(fragment, CommentDirective.Type.DRAWBUFFERS));
	}

	private static Optional<CommentDirective> findRendertargetsDirective(Optional<String> stageSource) {
		return stageSource.flatMap(fragment -> CommentDirectiveParser.findDirective(fragment, CommentDirective.Type.RENDERTARGETS));
	}

	private static int[] parseDigits(char[] directiveChars) {
		int[] buffers = new int[directiveChars.length];
		int index = 0;

		for (char buffer : directiveChars) {
			buffers[index++] = Character.digit(buffer, 10);
		}

		return buffers;
	}

	private static int[] parseDigitList(String digitListString) {
		return Arrays.stream(digitListString.split(","))
			.mapToInt(Integer::parseInt)
			.toArray();
	}

	private static Optional<CommentDirective> getAppliedDirective(Optional<CommentDirective> optionalDrawbuffersDirective, Optional<CommentDirective> optionalRendertargetsDirective) {
		if (optionalDrawbuffersDirective.isPresent() && optionalRendertargetsDirective.isPresent()) {
			if (optionalDrawbuffersDirective.get().getLocation() > optionalRendertargetsDirective.get().getLocation()) {
				return optionalDrawbuffersDirective;
			} else {
				return optionalRendertargetsDirective;
			}
		} else if (optionalDrawbuffersDirective.isPresent()) {
			return optionalDrawbuffersDirective;
		} else {
			return optionalRendertargetsDirective;
		}
	}

	/** These directives with {@code override} as the blend mode (used when no {@code blend.<program>} line set one). */
	public ProgramDirectives withBlendModeOverride(BlendModeOverride override) {
		ProgramDirectives directives = new ProgramDirectives(drawBuffers, viewportScale, alphaTestOverride, Optional.of(override),
			bufferBlendInformations, mipmappedBuffers, explicitFlips);
		directives.unknownDrawBuffers = unknownDrawBuffers;
		return directives;
	}

	public int[] getDrawBuffers() {
		return drawBuffers;
	}

	public boolean hasUnknownDrawBuffers() {
		return unknownDrawBuffers;
	}

	public ViewportData getViewportScale() {
		return viewportScale;
	}

	public Optional<AlphaTest> getAlphaTestOverride() {
		return Optional.ofNullable(alphaTestOverride);
	}

	public Optional<BlendModeOverride> getBlendModeOverride() {
		return blendModeOverride;
	}

	public List<BufferBlendInformation> getBufferBlendOverrides() {
		return bufferBlendInformations;
	}

	public ImmutableSet<Integer> getMipmappedBuffers() {
		return mipmappedBuffers;
	}

	public ImmutableMap<Integer, Boolean> getExplicitFlips() {
		return explicitFlips;
	}
}
