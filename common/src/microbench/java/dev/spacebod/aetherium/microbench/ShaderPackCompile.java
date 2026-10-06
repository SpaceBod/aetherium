package dev.spacebod.aetherium.microbench;

import com.google.common.collect.ImmutableList;
import dev.spacebod.aetherium.shaders.engine.CompositePass;
import dev.spacebod.aetherium.shaders.engine.LoadTimings;
import dev.spacebod.aetherium.shaders.engine.ComputePass;
import dev.spacebod.aetherium.shaders.engine.PackStorage;
import dev.spacebod.aetherium.shaders.engine.StorageBindings;
import dev.spacebod.aetherium.shaders.engine.WorldInterface;
import dev.spacebod.aetherium.shaders.gl.blending.AlphaTests;
import dev.spacebod.aetherium.shaders.gl.shader.PackFacingNames;
import dev.spacebod.aetherium.shaders.helpers.StringPair;
import dev.spacebod.aetherium.shaders.pipeline.transform.PatchShaderType;
import dev.spacebod.aetherium.shaders.pipeline.transform.TransformDiskCache;
import dev.spacebod.aetherium.shaders.pipeline.transform.TransformPatcher;
import dev.spacebod.aetherium.shaders.pipeline.transform.transformer.VanillaInterface;
import dev.spacebod.aetherium.shaders.shaderpack.ShaderPack;
import dev.spacebod.aetherium.shaders.shaderpack.include.AbsolutePackPath;
import dev.spacebod.aetherium.shaders.shaderpack.include.IncludeGraph;
import dev.spacebod.aetherium.shaders.shaderpack.include.IncludeProcessor;
import dev.spacebod.aetherium.shaders.shaderpack.include.ShaderPackSourceNames;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramArrayId;
import dev.spacebod.aetherium.shaders.shaderpack.loading.ProgramId;
import dev.spacebod.aetherium.shaders.shaderpack.materialmap.NamespacedId;
import dev.spacebod.aetherium.shaders.shaderpack.preprocessor.JcppProcessor;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ComputeSource;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSet;
import dev.spacebod.aetherium.shaders.shaderpack.programs.ProgramSource;
import dev.spacebod.aetherium.shaders.shaderpack.texture.TextureStage;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * Compiles every program of a shader pack the way the game does, without the game: the pack is loaded through the
 * engine's own {@link ShaderPack} (options, includes, preprocessor, shaders.properties; custom images and storage buffers
 * configured as in game), each program goes through the engine's own lowering ({@link WorldInterface} for world programs
 * against the vanilla 26.3 vertex format they replace, {@link CompositePass#lower}, {@link ComputePass#lower}), and every
 * stage is compiled to Vulkan 1.2 SPIR-V with vanilla GlslCompiler's options.
 *
 * <p>{@code AETHERIUM_PACK_OPTIONS=file}: use the pack's options file next to the zip ({@code <zip>.txt}), as in game.
 * Failures dump to {@code build/shader-harness/<pack>/}.
 *
 * <p>{@code AETHERIUM_TRANSFORM_CACHE=1}: each pack is built twice through the disk transform cache in a fresh temporary
 * folder (cold, then warm with the in-memory cache dropped), reporting transform and SPIR-V compile time for both and
 * whether every stage's GLSL and SPIR-V came out byte-identical. Otherwise the disk cache is off.
 *
 * <p>Run: {@code gradlew :common:microbench -Ponly=shaders} with {@code AETHERIUM_PACKS="<zip>;<zip>"} (absolute paths).
 */
final class ShaderPackCompile {
	/** Strict mode (default): exactly vanilla GlslCompiler's options. {@code -Daetherium.shaders.relaxed=true}: glslang's relaxed rules. */
	private static final boolean STRICT = !Boolean.getBoolean("aetherium.shaders.relaxed");
	private static final Path DUMP = Path.of("build", "shader-harness");
	/** {@code AETHERIUM_SPIRV_SIZES=1}: also compile every stage with debug information, to report the size difference. */
	private static final boolean SIZES = "1".equals(System.getenv("AETHERIUM_SPIRV_SIZES"));
	/** {@code AETHERIUM_METAL=1} (default on macOS hosts, {@code 0} turns it off): also translate every module to Metal as MoltenVK does. */
	private static final boolean METAL = System.getenv("AETHERIUM_METAL") != null ? "1".equals(System.getenv("AETHERIUM_METAL"))
			: System.getProperty("os.name", "").startsWith("Mac");
	/** Metal's sampler slots per stage for pushed descriptors (MoltenVK's {@code maxPerStageDescriptorSamplers}). */
	private static final int METAL_SAMPLER_SLOTS = 16;
	/** {@code AETHERIUM_TRANSFORM_CACHE=1}: cold and warm builds through the disk transform cache, compared. */
	private static final boolean CACHE = "1".equals(System.getenv("AETHERIUM_TRANSFORM_CACHE"));
	/** Time spent in {@link #compile} (shaderc), summed. */
	private static long compileNanos;
	/** Cold / warm totals over every pack ({@code AETHERIUM_TRANSFORM_CACHE=1}). */
	private static final long[] cacheTotals = new long[6];
	private static int cacheStages, cacheIdentical, cachePrograms, cacheHits;

	private ShaderPackCompile() {
	}

	static void run(String packs) {
		// Shadow comparisons compiled as filtered (OpenGL hardware filtering), the longer path; nearest is plain step().
		dev.spacebod.aetherium.shaders.engine.ShadowSampling.filterAllForCompileCheck();
		// As on a device with storage images and buffers (the game requests them): packs then take their storage paths.
		dev.spacebod.aetherium.client.gpu.StorageFeatures.assumeForCompileCheck();
		dev.spacebod.aetherium.client.gpu.FloatControls.assumeForCompileCheck();
		// The device of the platform stood in for: on macOS, MoltenVK (Metal lowering, no geometry stages).
		boolean mac = harnessOs().equals("mac");
		dev.spacebod.aetherium.client.gpu.DeviceTraits.assumeForCompileCheck(mac);
		dev.spacebod.aetherium.client.gpu.GeometryStages.assumeForCompileCheck(!mac);
		// The pack loader reads the game version (its version macros); registries for the ID maps (block.properties ...).
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		if (packs == null || packs.isBlank()) {
			System.out.println("shaders: no packs given (AETHERIUM_PACKS=\"a.zip;b.zip\")");
			return;
		}
		TransformDiskCache.useDirectory(null);
		for (String pack : packs.split(";")) {
			try {
				runPack(Path.of(pack.trim()));
			} catch (IOException e) {
				System.out.println("shaders: " + pack + ": " + e);
			} finally {
				StorageBindings.reset();
				dev.spacebod.aetherium.shaders.engine.TexturePatching.reset();
			}
		}
		if (CACHE) {
			System.out.printf(Locale.ROOT, "%ntransform cache, all packs: cold transform %.2f s, shaderc %.2f s, wall %.2f s; warm transform %.2f s (%d programs, %d from disk), shaderc %.2f s, wall %.2f s; %d/%d stages byte-identical (GLSL and SPIR-V)%n",
					cacheTotals[0] / 1e9, cacheTotals[1] / 1e9, cacheTotals[2] / 1e9, cacheTotals[3] / 1e9, cachePrograms, cacheHits,
					cacheTotals[4] / 1e9, cacheTotals[5] / 1e9, cacheIdentical, cacheStages);
		}
	}

	private record Program(String name, Map<String, String> sources) {
	}

	/** One lowering of one program: its stages, their SPIR-V (null = failed) and errors. */
	private record Outcome(Map<PatchShaderType, String> stages, Map<PatchShaderType, ByteBuffer> spirv, Map<PatchShaderType, String> errors,
			String transformError) {
		boolean ok() {
			return transformError == null && errors.isEmpty();
		}
	}

	/** Counts for one lowering over one pack. */
	private static final class Tally {
		int stages, compiled, transformFailures;
		/** SPIR-V bytes as compiled for the game (no debug information), and with it ({@code AETHERIUM_SPIRV_SIZES=1}). */
		long spirvBytes, debugBytes;
		/** Zero-fill: modules changed, variables and undefined values zeroed, modules whose patched words fail to parse. */
		int zeroedModules, zeroedVariables, zeroedUndefs, zeroBroken;
		/** Stages with comparisons on hardware samplers / made in the shader. */
		int hardwareCompare, emulatedCompare;
		/** {@link #METAL}: stages translated to Metal, and stages that failed (geometry stages are not tried: MoltenVK has none). */
		int metalOk, geometryStages;
		/** {@link #METAL}: the MSL of every translated stage, by program.stage, for Metal's own compiler. */
		final Map<String, String> msl = new TreeMap<>();
		final Map<String, Integer> metalErrors = new TreeMap<>();
		final List<String> metalFailed = new ArrayList<>();
		/**
		 * {@link #METAL}: graphics programs whose set 0 holds more samplers than Metal's 16 slots per stage (vanilla shows
		 * every set-0 binding to every stage): drawn through an allocated set, which needs Apple Silicon (argument buffers).
		 */
		final List<String> wideSamplers = new ArrayList<>();
		/** Set-0 samplers over all stages (descriptors written per draw). */
		int samplers;
		final List<String> emulatedIn = new ArrayList<>();
		final Map<String, Integer> errors = new TreeMap<>();
		final List<String> failed = new ArrayList<>();

		void add(String program, Outcome o) {
			if (o.transformError() != null) {
				transformFailures++;
				failed.add(program + " (transform)");
				count(errors, "transform: " + firstLine(o.transformError()));
				return;
			}
			stages += o.stages().size();
			compiled += o.spirv().size();
			o.stages().forEach((type, glsl) -> {
				hardwareCompare += glsl.contains(dev.spacebod.aetherium.shaders.engine.ShadowSampling.COMPARE_PREFIX) ? 1 : 0;
				if (glsl.contains("aeth_shadowFiltered(") || glsl.contains("aeth_shadowNearest(") || glsl.contains("aeth_shadowGather(")) {
					emulatedCompare++;
					emulatedIn.add(program + "." + type.name().toLowerCase(Locale.ROOT));
				}
			});
			Set<String> programSamplers = new HashSet<>();
			for (Map.Entry<PatchShaderType, ByteBuffer> e : o.spirv().entrySet()) {
				ByteBuffer code = e.getValue();
				spirvBytes += code.remaining();
				if (e.getKey() != PatchShaderType.COMPUTE) {
					SpirvInterface.reflect(code, false).stream().filter(l -> l.startsWith("sampler set=0")).forEach(l -> programSamplers.add(l.split(" ")[2]));
				}
				samplers += (int) SpirvInterface.reflect(code, e.getKey() == PatchShaderType.COMPUTE).stream().filter(l -> l.startsWith("sampler set=0")).count();
				int[] words = new int[code.remaining() / 4];
				code.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(words);
				var zeroed = dev.spacebod.aetherium.client.gpu.LocalZeroes.apply(words);
				if (zeroed.changed()) {
					zeroedModules++;
					zeroedVariables += zeroed.variables();
					zeroedUndefs += zeroed.undefs();
					ByteBuffer patched = ByteBuffer.allocateDirect(zeroed.words().length * 4).order(java.nio.ByteOrder.nativeOrder());
					patched.asIntBuffer().put(zeroed.words());
					try {
						SpirvInterface.reflect(patched, e.getKey() == PatchShaderType.COMPUTE);
					} catch (RuntimeException ex) {
						zeroBroken++;
					}
				}
				if (METAL) {
					if (e.getKey() == PatchShaderType.GEOMETRY) {
						geometryStages++;
					} else {
						String stage = program + "." + e.getKey().name().toLowerCase(Locale.ROOT);
						// As the game hands it to MoltenVK: private names dropped (DebugNames).
						MetalTranslation.Result metal = MetalTranslation.translate(dev.spacebod.aetherium.client.gpu.DeviceTraits.moltenVk()
								? metalNamed(code) : code);
						if (metal.error() == null) {
							metalOk++;
							msl.put(stage, metal.msl());
						} else {
							count(metalErrors, "SPIRV-Cross: " + firstLine(metal.error()));
							metalFailed.add(stage);
						}
					}
				}
				if (SIZES) {
					ByteBuffer debug = compile(program + "." + e.getKey(), o.stages().get(e.getKey()), e.getKey(), true);
					debugBytes += debug == null ? 0 : debug.remaining();
				}
			}
			if (METAL && programSamplers.size() > METAL_SAMPLER_SLOTS) {
				wideSamplers.add(program + " (" + programSamplers.size() + ")");
			}
			o.errors().values().forEach(e -> count(errors, e));
			if (!o.errors().isEmpty()) {
				failed.add(program);
			}
		}

		/**
		 * Compiles {@link #msl} with Metal's runtime compiler (benchmarks/tools/metal-compile.swift), adding failures to
		 * the Metal tally; the stages that compiled, or -1 when the tool could not run.
		 */
		private int compileMetal(String name) {
			if (msl.isEmpty()) {
				return 0;
			}
			try {
				Path dir = DUMP.resolve("metal").resolve(name.replaceAll("[^A-Za-z0-9_.@-]", "_"));
				Files.createDirectories(dir);
				try (var old = Files.list(dir)) {
					for (Path f : (Iterable<Path>) old::iterator) {
						Files.delete(f);
					}
				}
				for (Map.Entry<String, String> e : msl.entrySet()) {
					Files.writeString(dir.resolve(e.getKey() + ".metal"), e.getValue());
				}
				Path tool = Path.of(System.getProperty("aetherium.root", "..")).resolve("benchmarks/tools/metal-compile.swift").toAbsolutePath().normalize();
				Process process = new ProcessBuilder("xcrun", "swift", tool.toString(), dir.toAbsolutePath().toString()).redirectErrorStream(true).start();
				int ok = -1;
				for (String line : new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
					if (line.startsWith("FAIL ")) {
						int colon = line.indexOf(": ");
						metalFailed.add(line.substring(5, colon));
						count(metalErrors, "Metal: " + line.substring(colon + 2));
					} else if (line.startsWith("OK ")) {
						ok = Integer.parseInt(line.split(" ")[1]);
					}
				}
				process.waitFor();
				return ok;
			} catch (IOException | InterruptedException | RuntimeException e) {
				count(metalErrors, "Metal compiler not run: " + e);
				return -1;
			}
		}

		void print(String pack, String label, int programs) {
			System.out.printf(Locale.ROOT, "%n%s [%s]: %d programs, %d stages compiled to Vulkan SPIR-V out of %d (%.0f%%), %d transform failures%n",
					pack, label, programs, compiled, stages, stages == 0 ? 0 : 100.0 * compiled / stages, transformFailures);
			System.out.printf(Locale.ROOT, "  SPIR-V %d KiB%s; zero-fill: %d modules, %d variables, %d undefined values%s; shadow comparisons: %d stages on hardware samplers, %d in the shader; %d set-0 samplers%n",
					spirvBytes >> 10, SIZES ? " (" + (debugBytes >> 10) + " KiB with debug information)" : "", zeroedModules, zeroedVariables, zeroedUndefs,
					zeroBroken == 0 ? "" : ", " + zeroBroken + " UNREADABLE after the pass", hardwareCompare, emulatedCompare, samplers);
			if (!emulatedIn.isEmpty()) {
				System.out.println("  shadow comparisons made in the shader: " + emulatedIn);
			}
			if (!failed.isEmpty()) {
				System.out.println("  failing programs: " + failed);
			}
			errors.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
					.forEach(x -> System.out.printf(Locale.ROOT, "  %4dx %s%n", x.getValue(), x.getKey()));
			if (METAL) {
				int metalCompiled = compileMetal(pack + "@" + label);
				System.out.printf(Locale.ROOT, "  Metal (MoltenVK's SPIRV-Cross, then Metal's compiler): %d of %d stages translate, %s compile%s%n", metalOk,
						compiled - geometryStages, metalCompiled < 0 ? "not checked:" : metalCompiled,
						geometryStages == 0 ? "" : "; " + geometryStages + " geometry stages skipped (MoltenVK has none: vanilla shader used)");
				if (!wideSamplers.isEmpty()) {
					System.out.println("  Metal: " + wideSamplers.size() + " programs past " + METAL_SAMPLER_SLOTS
							+ " samplers per stage, drawn through an allocated set (Apple Silicon): " + wideSamplers);
				}
				if (!metalFailed.isEmpty()) {
					System.out.println("  Metal failures: " + metalFailed);
					metalErrors.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
							.forEach(x -> System.out.printf(Locale.ROOT, "  %4dx %s%n", x.getValue(), x.getKey()));
				}
			}
		}
	}

	private static void runPack(Path zip) throws IOException {
		String packName = zip.getFileName().toString();
		boolean folder = Files.isDirectory(zip);
		try (FileSystem fs = folder ? null : FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of())) {
			Path root = folder ? zip.resolve("shaders") : fs.getPath("shaders");
			List<Program> programs = loadThroughEngine(zip, root);
			if (programs == null) {
				programs = loadRaw(root);
			}
			addVariants(programs);
			Map<String, Outcome> cold = CACHE ? new LinkedHashMap<>() : null;
			Path cacheDir = null;
			LoadTimings.Snapshot start = LoadTimings.snapshot();
			long compileStart = compileNanos;
			long wallStart = System.nanoTime();
			if (CACHE) {
				cacheDir = Files.createTempDirectory("aetherium-transform-cache");
				TransformDiskCache.useDirectory(cacheDir);
				TransformPatcher.clearCache();
			}
			Tally tally = new Tally();
			for (Program program : programs) {
				Outcome o = build(program);
				if (cold != null) {
					cold.put(program.name(), o);
				}
				tally.add(program.name(), o);
				dumpFailures(packName, program.name(), o);
				if (program.name().equals(System.getenv("AETHERIUM_DUMP"))) {
					o.stages().forEach((k, v) -> System.out.println("===== " + program.name() + " " + k + System.lineSeparator() + v));
					o.errors().forEach((k, v) -> System.out.println("----- " + program.name() + " " + k + " error: " + v));
				}
			}
			tally.print(packName, STRICT ? "vanilla compiler options" : "relaxed rules", programs.size());
			if (CACHE) {
				long coldWall = System.nanoTime() - wallStart;
				LoadTimings.Snapshot afterCold = LoadTimings.snapshot();
				long coldCompile = compileNanos - compileStart;
				// Warm: the in-memory results dropped, so every program comes from the disk entries the cold build wrote.
				TransformPatcher.clearCache();
				long warmCompileStart = compileNanos;
				long warmWallStart = System.nanoTime();
				int stages = 0, identical = 0;
				List<String> differing = new ArrayList<>();
				for (Program program : programs) {
					Outcome warm = build(program);
					int[] counts = compare(cold.get(program.name()), warm);
					stages += counts[0];
					identical += counts[1];
					if (counts[0] != counts[1]) {
						differing.add(program.name());
					}
				}
				long warmWall = System.nanoTime() - warmWallStart;
				LoadTimings.Snapshot afterWarm = LoadTimings.snapshot();
				long warmCompile = compileNanos - warmCompileStart;
				long coldTransform = afterCold.nanos(LoadTimings.Kind.TRANSFORM) - start.nanos(LoadTimings.Kind.TRANSFORM);
				long warmTransform = afterWarm.nanos(LoadTimings.Kind.TRANSFORM) - afterCold.nanos(LoadTimings.Kind.TRANSFORM);
				long warmCount = afterWarm.count(LoadTimings.Kind.TRANSFORM) - afterCold.count(LoadTimings.Kind.TRANSFORM);
				long warmHits = afterWarm.cached(LoadTimings.Kind.TRANSFORM) - afterCold.cached(LoadTimings.Kind.TRANSFORM);
				long bytes;
				try (var files = Files.list(cacheDir)) {
					bytes = files.mapToLong(f -> f.toFile().length()).sum();
				}
				System.out.printf(Locale.ROOT, "  transform cache: cold transform %.2f s (%d), shaderc %.2f s, wall %.2f s; warm transform %.2f s (%d, %d cached), shaderc %.2f s, wall %.2f s; %d KiB on disk; %d/%d stages byte-identical%s%n",
						coldTransform / 1e9, afterCold.count(LoadTimings.Kind.TRANSFORM) - start.count(LoadTimings.Kind.TRANSFORM), coldCompile / 1e9, coldWall / 1e9,
						warmTransform / 1e9, warmCount, warmHits, warmCompile / 1e9, warmWall / 1e9, bytes >> 10, identical, stages,
						differing.isEmpty() ? "" : " DIFFERENT: " + differing);
				cacheTotals[0] += coldTransform;
				cacheTotals[1] += coldCompile;
				cacheTotals[2] += coldWall;
				cacheTotals[3] += warmTransform;
				cacheTotals[4] += warmCompile;
				cacheTotals[5] += warmWall;
				cacheStages += stages;
				cacheIdentical += identical;
				cachePrograms += (int) warmCount;
				cacheHits += (int) warmHits;
				if (!damageChecked) {
					damageChecked = true;
					checkDamagedEntries(cacheDir, programs);
				}
				TransformDiskCache.useDirectory(null);
				TransformPatcher.clearCache();
				try (var files = Files.walk(cacheDir)) {
					files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
				}
			}
		}
	}

	private static boolean damageChecked;

	/**
	 * Damages three entries (a flipped payload byte, a truncated file, foreign bytes), then lowers every program again from
	 * the disk cache: the damaged entries must be transformed again, rewritten whole, and every stage come out as before.
	 */
	private static void checkDamagedEntries(Path dir, List<Program> programs) throws IOException {
		Map<String, Map<PatchShaderType, String>> before = new LinkedHashMap<>();
		for (Program program : programs) {
			before.put(program.name(), lowerQuietly(program));
		}
		List<Path> entries;
		try (var files = Files.list(dir)) {
			entries = files.filter(f -> f.toString().endsWith(".bin")).sorted().limit(3).toList();
		}
		long[] sizes = new long[entries.size()];
		for (int i = 0; i < entries.size(); i++) {
			byte[] data = Files.readAllBytes(entries.get(i));
			sizes[i] = data.length;
			switch (i) {
				case 0 -> {
					data[data.length - 5] ^= 0x5A;
					Files.write(entries.get(i), data);
				}
				case 1 -> Files.write(entries.get(i), java.util.Arrays.copyOf(data, data.length / 2));
				default -> Files.write(entries.get(i), "not an entry".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		TransformPatcher.clearCache();
		LoadTimings.Snapshot start = LoadTimings.snapshot();
		int same = 0;
		for (Program program : programs) {
			same += java.util.Objects.equals(before.get(program.name()), lowerQuietly(program)) ? 1 : 0;
		}
		LoadTimings.Snapshot end = LoadTimings.snapshot();
		int rewritten = 0;
		for (int i = 0; i < entries.size(); i++) {
			rewritten += Files.size(entries.get(i)) == sizes[i] ? 1 : 0;
		}
		long count = end.count(LoadTimings.Kind.TRANSFORM) - start.count(LoadTimings.Kind.TRANSFORM);
		long cached = end.cached(LoadTimings.Kind.TRANSFORM) - start.cached(LoadTimings.Kind.TRANSFORM);
		System.out.printf(Locale.ROOT, "  damaged entries: %d damaged, %d of %d programs from disk, %d rewritten whole, %d/%d programs identical%n",
				entries.size(), cached, count, rewritten, same, programs.size());
	}

	private static Map<PatchShaderType, String> lowerQuietly(Program program) {
		try {
			return lower(program.name(), program.sources());
		} catch (RuntimeException e) {
			return Map.of();
		}
	}

	/** Stages compared, and how many are byte-identical (GLSL text, SPIR-V words, errors) between two builds of a program. */
	private static int[] compare(Outcome a, Outcome b) {
		if (!java.util.Objects.equals(a.transformError(), b.transformError())) {
			return new int[]{Math.max(1, a.stages().size()), 0};
		}
		int stages = 0, same = 0;
		java.util.Set<PatchShaderType> types = java.util.EnumSet.noneOf(PatchShaderType.class);
		types.addAll(a.stages().keySet());
		types.addAll(b.stages().keySet());
		for (PatchShaderType type : types) {
			stages++;
			if (java.util.Objects.equals(a.stages().get(type), b.stages().get(type)) && java.util.Objects.equals(a.spirv().get(type), b.spirv().get(type))
					&& java.util.Objects.equals(a.errors().get(type), b.errors().get(type))) {
				same++;
			}
		}
		return new int[]{stages, same};
	}

	// ------------------------------------------------------------------ loading

	/** The overworld program set through the engine's ShaderPack (null when that fails here: the raw loader is used). */
	private static List<Program> loadThroughEngine(Path zip, Path root) {
		Map<String, String> options = Map.of();
		if ("file".equalsIgnoreCase(System.getenv("AETHERIUM_PACK_OPTIONS"))) {
			options = readOptions(zip.resolveSibling(zip.getFileName() + ".txt"));
			System.out.println("  pack options from " + zip.getFileName() + ".txt: " + options);
		}
		ShaderPack pack;
		try {
			// The engine adds its standard macros itself (StandardMacros); LOD macros through the property it reads.
			if (System.getenv("AETHERIUM_SHADERS_LOD") != null) {
				System.setProperty("aetherium.shaders.lod", "true");
			}
			pack = new ShaderPack(root, options, ImmutableList.of(), !Files.isDirectory(zip));
		} catch (Throwable e) {
			System.out.println("  (pack not loadable through the engine here: " + e + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] + " / " + (e.getStackTrace().length > 1 ? e.getStackTrace()[1] : "") : "") + "; raw sources used, storage not configured)");
			return null;
		}
		ProgramSet set = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
		// As the engine sets it up at pack load: raw textures renamed per stage, and what set 1 holds.
		dev.spacebod.aetherium.shaders.engine.TexturePatching.configure(set.getPackDirectives().getTextureMap());
		var raws = dev.spacebod.aetherium.shaders.engine.RawTextures.collect(pack);
		boolean targetImages = dev.spacebod.aetherium.shaders.engine.TargetStorage.usedBy(set);
		if (!pack.getCustomImages().isEmpty() || !pack.getBufferObjects().isEmpty() || !raws.isEmpty() || targetImages) {
			StorageBindings.configure(PackStorage.bindings(pack.getCustomImages()), !pack.getBufferObjects().isEmpty(),
					dev.spacebod.aetherium.shaders.engine.RawTextures.bindings(raws), targetImages);
			System.out.printf(Locale.ROOT, "  storage: %d custom images, %d storage buffers, %d raw textures (set 1)%n", pack.getCustomImages().size(),
					pack.getBufferObjects().size(), raws.size());
		}
		List<Program> out = new ArrayList<>();
		for (ProgramId id : ProgramId.values()) {
			set.get(id).ifPresent(source -> out.add(program(source)));
		}
		for (ProgramArrayId stage : ProgramArrayId.values()) {
			for (ProgramSource source : set.getComposite(stage)) {
				if (source != null && source.isValid()) {
					out.add(program(source));
				}
			}
			ComputeSource[][] computes = set.getCompute(stage);
			if (computes != null) {
				for (ComputeSource[] list : computes) {
					addComputes(out, list);
				}
			}
		}
		addComputes(out, set.getSetup());
		addComputes(out, set.getShadowCompute());
		addComputes(out, set.getFinalCompute());
		// Program names are unique per kind; keep the first of duplicates (fallback chains resolve to the same source).
		Map<String, Program> unique = new LinkedHashMap<>();
		out.forEach(p -> unique.putIfAbsent(p.name(), p));
		return new ArrayList<>(unique.values());
	}

	private static Program program(ProgramSource source) {
		Map<String, String> sources = new HashMap<>();
		source.getVertexSource().ifPresent(s -> sources.put("vsh", s));
		source.getGeometrySource().ifPresent(s -> sources.put("gsh", s));
		source.getFragmentSource().ifPresent(s -> sources.put("fsh", s));
		return new Program(source.getName(), sources);
	}

	private static void addComputes(List<Program> out, ComputeSource[] list) {
		if (list == null) {
			return;
		}
		for (ComputeSource source : list) {
			if (source != null && source.getSource().isPresent()) {
				out.add(new Program(source.getName(), Map.of("csh", source.getSource().get())));
			}
		}
	}

	private static Map<String, String> readOptions(Path file) {
		Map<String, String> out = new LinkedHashMap<>();
		try {
			for (String line : Files.readAllLines(file)) {
				int eq = line.indexOf('=');
				if (eq > 0 && !line.startsWith("#")) {
					out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
				}
			}
		} catch (IOException e) {
			System.out.println("  (no options file " + file + ")");
		}
		return out;
	}

	/** Fallback: every program file through the include graph and the preprocessor with default options. */
	private static List<Program> loadRaw(Path root) throws IOException {
		ImmutableList.Builder<AbsolutePackPath> starts = ImmutableList.builder();
		ImmutableList<String> candidates = ShaderPackSourceNames.POTENTIAL_STARTS;
		ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/"), candidates);
		if (Files.isDirectory(root.resolve("world0"))) {
			ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/world0"), candidates);
		}
		IncludeGraph graph = new IncludeGraph(root, starts.build(), true);
		IncludeProcessor includes = new IncludeProcessor(graph);
		if (!graph.getFailures().isEmpty()) {
			System.out.println("  include failures: " + graph.getFailures().keySet());
		}
		Map<String, Map<String, String>> programs = new TreeMap<>();
		// The overworld's set: root programs, overridden by world0/ ones.
		List<AbsolutePackPath> ordered = new ArrayList<>(graph.getNodes().keySet());
		ordered.sort(java.util.Comparator.comparing((AbsolutePackPath p) -> p.getPathString().startsWith("/world0/")));
		for (AbsolutePackPath path : ordered) {
			String file = path.getPathString().substring(1);
			if (file.startsWith("world0/") && file.indexOf('/', "world0/".length()) < 0) {
				file = file.substring("world0/".length());
			} else if (file.contains("/")) {
				continue;
			}
			int dot = file.lastIndexOf('.');
			if (dot < 0) {
				continue;
			}
			String ext = file.substring(dot + 1);
			if (!List.of("vsh", "fsh", "gsh", "tcs", "tes", "csh").contains(ext)) {
				continue;
			}
			String source = String.join("\n", includes.getIncludedFile(path));
			programs.computeIfAbsent(file.substring(0, dot), k -> new TreeMap<>()).put(ext, JcppProcessor.glslPreprocessSource(source, environmentDefines()));
		}
		List<Program> out = new ArrayList<>();
		programs.forEach((name, sources) -> out.add(new Program(name, sources)));
		return out;
	}

	/** Programs the engine also builds in other forms: lines with the basic fallback, LOD fallbacks, entity shadows. */
	private static void addVariants(List<Program> programs) {
		Map<String, Program> byName = new LinkedHashMap<>();
		programs.forEach(p -> byName.put(p.name(), p));
		if (!byName.containsKey("gbuffers_line") && byName.containsKey("gbuffers_basic")) {
			programs.add(new Program("gbuffers_basic@line", byName.get("gbuffers_basic").sources()));
		}
		Program terrain = byName.get("dh_terrain");
		if (terrain != null) {
			if (!byName.containsKey("dh_generic")) {
				programs.add(new Program("dh_generic", terrain.sources()));
			}
			if (!byName.containsKey("dh_shadow")) {
				programs.add(new Program("dh_shadow", terrain.sources()));
			}
		}
		if (byName.containsKey("shadow")) {
			programs.add(new Program("shadow@entity", byName.get("shadow").sources()));
		}
		// Block-entity geometry (chests, signs, banners) draws with the block programs in the entity format.
		for (String block : List.of("gbuffers_block", "gbuffers_block_translucent")) {
			if (byName.containsKey(block)) {
				programs.add(new Program(block + "@entity", byName.get(block).sources()));
			}
		}
	}

	// ------------------------------------------------------------------ lowering and compiling

	private static Outcome build(Program program) {
		Map<PatchShaderType, String> stages;
		try {
			stages = lower(program.name(), program.sources());
		} catch (RuntimeException e) {
			String message = e.getMessage() == null ? e.toString() : e.getMessage();
			Throwable cause = e.getCause();
			while (cause != null) {
				message += " / " + cause.getMessage();
				cause = cause.getCause();
			}
			return new Outcome(Map.of(), Map.of(), Map.of(), message);
		}
		Map<PatchShaderType, String> present = new EnumMap<>(PatchShaderType.class);
		Map<PatchShaderType, ByteBuffer> spirv = new EnumMap<>(PatchShaderType.class);
		Map<PatchShaderType, String> errors = new EnumMap<>(PatchShaderType.class);
		if (stages != null) {
			stages.forEach((type, source) -> {
				if (source == null) {
					return;
				}
				present.put(type, source);
				ByteBuffer code = compile(program.name() + "." + type.name().toLowerCase(Locale.ROOT), source, type, false);
				// Vanilla's pipeline builder also rejects struct-typed stage inputs/outputs (SPIR-V base type 15), which the
				// compiler accepts: checked here so the harness fails where the game would.
				String struct = code == null || type == PatchShaderType.COMPUTE ? null : SpirvInterface.reflect(code, false).stream()
						.filter(l -> (l.startsWith("in ") || l.startsWith("out ")) && l.contains(" t15x")).findFirst().orElse(null);
				if (struct != null) {
					errors.put(type, "error: '…' : struct interface variable (vanilla's pipeline builder rejects it)");
					rawErrors.put(program.name() + type, "struct interface variable: " + struct);
				} else if (code != null) {
					spirv.put(type, code);
				} else {
					errors.put(type, normalise(lastRawError));
					rawErrors.put(program.name() + type, lastRawError);
				}
			});
		}
		return new Outcome(present, spirv, errors, null);
	}

	private static final Map<String, String> rawErrors = new HashMap<>();

	/** The stages exactly as the engine hands them to vanilla's compiler, for the current lowering mode. */
	private static Map<PatchShaderType, String> lower(String name, Map<String, String> src) {
		if (src.containsKey("csh")) {
			ComputePass.Lowered compute = ComputePass.lower(name, src.get("csh"), stageOf(name));
			return compute == null ? null : Map.of(PatchShaderType.COMPUTE, compute.glsl());
		}
		if (!src.containsKey("vsh") || !src.containsKey("fsh")) {
			return null;
		}
		int[] buffers = {0, 1, 2, 3, 4, 5, 6, 7};
		if (name.startsWith("dh_")) {
			if (name.startsWith("dh_generic")) {
				return WorldInterface.prepareLodGeneric(name, src.get("vsh"), src.get("fsh"), buffers, buffers).stages();
			}
			return WorldInterface.prepareLod(name, src.get("vsh"), src.get("fsh"), buffers, buffers, name.startsWith("dh_shadow")).stages();
		}
		if (isWorld(name)) {
			// Against the vanilla 26.3 vertex format of the pipelines the program replaces.
			boolean entityShadow = name.equals("shadow@entity");
			// Chunk terrain is drawn by the chunk renderer from its region meshes (ChunkMeshFormat); moving blocks and the
			// block-breaking overlay still come from vanilla's block pipelines.
			boolean chunks = !entityShadow && name.matches("gbuffers_(terrain.*|water)|shadow.*");
			boolean terrain = !entityShadow && name.matches("gbuffers_(block|damagedblock)");
			List<com.mojang.renderpearl.api.vertex.VertexFormat> formats = new ArrayList<>();
			if (chunks) {
				formats.add(chunkFormat());
			} else {
				// Entity and world-text draws are built in the extended formats while a pack runs.
				formats.add(dev.spacebod.aetherium.shaders.engine.EntityVertexFormats.forPipeline(
						entityShadow ? com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY : formatFor(name), true));
			}
			if (terrain) {
				formats.add(com.mojang.blaze3d.vertex.DefaultVertexFormat.CHUNK_DATA_INSTANCED);
			}
			WorldInterface.Prepared prepared = WorldInterface.prepare(name, src.get("vsh"), src.get("gsh"), src.get("fsh"), formats,
					chunks ? VanillaInterface.TransformSource.TERRAIN_REGION
							: terrain ? VanillaInterface.TransformSource.TERRAIN_MULTIDRAW
							: entityShadow ? VanillaInterface.TransformSource.DYNAMIC_SHADOW : VanillaInterface.TransformSource.DYNAMIC,
					AlphaTests.ONE_TENTH_ALPHA, false, name.endsWith("line"), name.endsWith("clouds"), name.contains("glint"), false, buffers, buffers,
					name.startsWith("shadow"));
			if (name.equals(System.getenv("AETHERIUM_DUMP"))) {
				System.out.println("===== " + name + " resources " + prepared.resources());
			}
			return prepared.stages();
		}
		CompositePass.Lowered composite = CompositePass.lower(name, src.get("vsh"), src.get("fsh"), stageOf(name));
		Map<PatchShaderType, String> out = new EnumMap<>(PatchShaderType.class);
		out.put(PatchShaderType.VERTEX, composite.vertex());
		out.put(PatchShaderType.FRAGMENT, composite.fragment());
		return out;
	}

	private static boolean isWorld(String name) {
		return name.startsWith("gbuffers_") || name.startsWith("dh_") || name.startsWith("shadow") && !name.startsWith("shadowcomp");
	}

	/** Null when both compile to the same interface (or one does not compile: counted separately), else the difference. */
	private static void dumpFailures(String pack, String name, Outcome o) {
		if (o.ok()) {
			return;
		}
		try {
			Path dir = DUMP.resolve(pack);
			Files.createDirectories(dir);
			String file = name.replaceAll("[^A-Za-z0-9_.@-]", "_");
			for (Map.Entry<PatchShaderType, String> e : o.stages().entrySet()) {
				Files.writeString(dir.resolve(file + "." + e.getKey().name().toLowerCase(Locale.ROOT) + ".glsl"), e.getValue());
			}
			StringBuilder errors = new StringBuilder();
			if (o.transformError() != null) {
				errors.append(o.transformError()).append('\n');
			}
			o.errors().forEach((k, v) -> errors.append(k).append(": ").append(rawErrors.getOrDefault(name + k, v)).append('\n'));
			Files.writeString(dir.resolve(file + ".errors.txt"), errors.toString());
		} catch (IOException e) {
			System.out.println("  (dump failed: " + e + ")");
		}
	}

	private static com.mojang.renderpearl.api.vertex.VertexFormat chunkFormat;

	/** The chunk mesh format the chunk renderer builds while a pack is configured. */
	private static com.mojang.renderpearl.api.vertex.VertexFormat chunkFormat() {
		if (chunkFormat == null) {
			chunkFormat = new dev.spacebod.aetherium.shaders.chunks.ChunkMeshFormat().getVertexFormat();
		}
		return chunkFormat;
	}

	private static com.mojang.renderpearl.api.vertex.VertexFormat formatFor(String name) {
		String p = name.replace("gbuffers_", "").replaceFirst("^.*@", "");
		if (p.matches("block|damagedblock")) {
			return dev.spacebod.aetherium.shaders.engine.TerrainVertexFormat.EXTENDED;
		}
		if (p.matches("particles.*|weather")) {
			return com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE;
		}
		if (p.equals("skybasic")) {
			return com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION;
		}
		if (p.matches("skytextured|textured|armor_glint")) {
			return com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX;
		}
		if (p.equals("line")) {
			return com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH;
		}
		if (p.equals("clouds")) {
			return null; // vanilla 26.3 pulls cloud vertices from the CloudFaces texel buffer
		}
		if (p.matches("basic")) {
			return com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
		}
		if (p.matches("beaconbeam")) {
			return com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR;
		}
		return com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY;
	}

	private static TextureStage stageOf(String name) {
		if (name.startsWith("shadowcomp")) {
			return TextureStage.SHADOWCOMP;
		}
		if (name.startsWith("prepare")) {
			return TextureStage.PREPARE;
		}
		if (name.startsWith("deferred")) {
			return TextureStage.DEFERRED;
		}
		if (name.startsWith("begin")) {
			return TextureStage.BEGIN;
		}
		if (name.startsWith("setup")) {
			return TextureStage.SETUP;
		}
		return TextureStage.COMPOSITE_AND_FINAL;
	}

	/** The last compile error as shaderc reported it (line numbers, identifiers). */
	private static String lastRawError;

	/** Compiles one stage with vanilla GlslCompiler's options; the SPIR-V (a heap copy), or null with {@link #lastRawError} set. */
	private static ByteBuffer compile(String name, String source, PatchShaderType type, boolean debugInfo) {
		int kind = switch (type) {
			case VERTEX -> Shaderc.shaderc_vertex_shader;
			case FRAGMENT -> Shaderc.shaderc_fragment_shader;
			case GEOMETRY -> Shaderc.shaderc_geometry_shader;
			case TESS_CONTROL -> Shaderc.shaderc_tess_control_shader;
			case TESS_EVAL -> Shaderc.shaderc_tess_evaluation_shader;
			case COMPUTE -> Shaderc.shaderc_compute_shader;
		};
		long started = System.nanoTime();
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			// Vanilla's compiler binds graphics uniforms itself; the engine's compute compiler does not (every binding explicit).
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, type != PatchShaderType.COMPUTE);
			if (type == PatchShaderType.COMPUTE) {
				// As the engine compiles pack computes (ShaderCompiler): optimised, which also validates the module.
				Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			}
			if (debugInfo) {
				Shaderc.shaderc_compile_options_set_generate_debug_info(options);
			}
			if (!STRICT) {
				Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
				Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
			}
			// Sources go through native heap memory: large packs' sources overflow LWJGL's small thread stack.
			ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
			ByteBuffer nameBytes = org.lwjgl.system.MemoryUtil.memUTF8(name);
			ByteBuffer entryBytes = org.lwjgl.system.MemoryUtil.memUTF8("main");
			long result;
			try {
				result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, kind, nameBytes, entryBytes, options);
			} finally {
				org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
				org.lwjgl.system.MemoryUtil.memFree(nameBytes);
				org.lwjgl.system.MemoryUtil.memFree(entryBytes);
			}
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success) {
					ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
					ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(java.nio.ByteOrder.nativeOrder());
					copy.put(bytes).flip();
					return copy;
				}
				lastRawError = Shaderc.shaderc_result_get_error_message(result);
				return null;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
			if (!debugInfo) {
				compileNanos += System.nanoTime() - started;
			}
		}
	}

	/** First error line without file/line numbers, so identical causes group together. */
	private static String normalise(String message) {
		if (message == null) {
			return "null";
		}
		for (String line : message.split("\n")) {
			if (line.contains("error")) {
				return line.replaceAll("^[^:]*:\\d+: ", "").replaceAll("'[^']*'", "'…'").trim();
			}
		}
		return firstLine(message);
	}

	private static String firstLine(String s) {
		return s == null ? "null" : s.split("\n")[0];
	}

	private static void count(Map<String, Integer> m, String k) {
		m.merge(k, 1, Integer::sum);
	}

	/** {@code code} with the names of private code dropped, as the game does on MoltenVK. */
	private static ByteBuffer metalNamed(ByteBuffer code) {
		int[] words = new int[code.remaining() / 4];
		code.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(words);
		int[] stripped = dev.spacebod.aetherium.client.gpu.DebugNames.apply(words);
		ByteBuffer out = ByteBuffer.allocateDirect(stripped.length * 4).order(java.nio.ByteOrder.nativeOrder());
		out.asIntBuffer().put(stripped);
		return out;
	}

	/** The platform the harness stands in for: AETHERIUM_HARNESS_OS=windows|mac|linux, else the host. */
	static String harnessOs() {
		String os = System.getenv("AETHERIUM_HARNESS_OS");
		if (os == null) {
			String host = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			os = host.startsWith("mac") ? "mac" : host.startsWith("linux") ? "linux" : "windows";
		}
		return os;
	}

	/**
	 * The pipeline's environment defines (StandardMacros), enough for packs' #if chains: an NVIDIA device on Windows and
	 * Linux, Apple silicon through MoltenVK on macOS, where no OS macro is defined (as in game).
	 */
	private static List<StringPair> environmentDefines() {
		List<StringPair> d = new ArrayList<>();
		String os = harnessOs();
		boolean mac = os.equals("mac");
		if (!mac) {
			d.add(new StringPair("MC_OS_" + os.toUpperCase(Locale.ROOT), ""));
		}
		String[][] pairs = {
				{"MC_VERSION", "260300"}, {"MC_GL_VERSION", "460"}, {"MC_GLSL_VERSION", "460"},
				{mac ? "MC_GL_VENDOR_OTHER" : "MC_GL_VENDOR_NVIDIA", ""}, {mac ? "MC_GL_RENDERER_APPLE" : "MC_GL_RENDERER_GEFORCE", ""}, {"MC_NORMAL_MAP", ""}, {"MC_SPECULAR_MAP", ""},
				{"MC_RENDER_QUALITY", "1.0"}, {"MC_SHADOW_QUALITY", "1.0"}, {"MC_HAND_DEPTH", "0.125"}, {PackFacingNames.ENGINE_MACRO, ""},
				{PackFacingNames.VERSION_MACRO, "11104"}, {PackFacingNames.TAG_SUPPORT_MACRO, "2"}, {"MC_GL_ARB_shader_texture_lod", ""}, {"MC_GL_EXT_gpu_shader4", ""},
				{"MC_GL_ARB_texture_gather", ""}, {"MC_GL_ARB_gpu_shader5", ""}, {"MC_GL_ARB_shader_image_load_store", ""},
				{"MC_GL_ARB_compute_shader", ""}, {"MC_GL_ARB_shader_storage_buffer_object", ""}};
		for (String[] p : pairs) {
			d.add(new StringPair(p[0], p[1]));
		}
		// As in game: MC_RENDER_STAGE_* for every world rendering phase.
		dev.spacebod.aetherium.shaders.gl.shader.StandardMacros.getRenderStages().forEach((k, v) -> d.add(new StringPair(k, v)));
		for (int i = 0; i < dev.spacebod.aetherium.shaders.gl.shader.StandardMacros.LOD_MATERIALS.length; i++) {
			d.add(new StringPair("DH_BLOCK_" + dev.spacebod.aetherium.shaders.gl.shader.StandardMacros.LOD_MATERIALS[i], String.valueOf(i)));
		}
		// AETHERIUM_SHADERS_LOD=1: as with level-of-detail terrain rendering (the packs' dhDepthTex/dh_* code paths).
		if (System.getenv("AETHERIUM_SHADERS_LOD") != null) {
			d.add(new StringPair("DISTANT_HORIZONS", ""));
			d.add(new StringPair("DISTANT_HORIZONS_TEXTURES", ""));
		}
		return d;
	}
}
