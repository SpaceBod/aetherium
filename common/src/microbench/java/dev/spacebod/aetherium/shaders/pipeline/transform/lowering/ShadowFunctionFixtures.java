package dev.spacebod.aetherium.shaders.pipeline.transform.lowering;

import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Naming regressions before sampler types are lowered, including sampler members in structs. */
public final class ShadowFunctionFixtures {
	private ShadowFunctionFixtures() {
	}

	public static void run() {
		record Case(String declarations, String calls, Set<String> renamedCalls) { }
		List<Case> cases = List.of(
				new Case("struct Textures { sampler2D depth; }; uniform Textures textures;"
						+ "float fetchDepth(sampler2DShadow s, sampler2D d, vec3 p) { return texture(s,p)+texture(d,p.xy).r; }",
						"fetchDepth(shadowtex0,textures.depth,vec3(0.5));", Set.of()),
				new Case("struct Comparisons { sampler2DShadow shadow; }; uniform Comparisons comparisons;"
						+ "float fetchDepth(sampler2DShadow s,vec3 p) { return texture(s,p); }",
						"fetchDepth(comparisons.shadow,vec3(0.5));", Set.of()),
				new Case("float fetchDepth(sampler2DShadow s,vec3 p) { return texture(s,p); }",
						"fetchDepth((shadowtex0),vec3(0.5));", Set.of()),
				new Case("float fetchDepth(sampler2DShadow s,vec3 p) { return texture(s,p); }"
						+ "float fetchDepth(sampler2D s,vec2 p) { return texture(s,p).r; }",
						"fetchDepth(shadowtex0,vec3(0.5)); fetchDepth(depthtex0,vec2(0.5));", Set.of()),
				new Case("float fetchDepth(sampler2DShadow s,vec3 p);"
						+ "float fetchDepth(sampler2DShadow source,vec3 position) { return texture(source,position); }",
						"fetchDepth(shadowtex0,vec3(0.5));", Set.of()),
				new Case("float fetchDepth(sampler2DShadow s,vec3 p[1]) { return texture(s,p[0]); }"
						+ "float fetchDepth(sampler2D s,vec3 p[2]) { return texture(s,p[1].xy).r; }",
						"vec3 a[1]; vec3 b[2]; fetchDepth(shadowtex0,a); fetchDepth(depthtex0,b);", Set.of()),
				new Case("float fetchDepth(sampler2DShadow s,float p) { return texture(s,vec3(p)); }"
						+ "float fetchDepth(sampler2D s,int p) { return texture(s,vec2(p)).r; }",
						"fetchDepth((shadowtex0),1); fetchDepth(depthtex0,1);", Set.of("aeth_s_fetchDepth_0")),
				new Case("float fetchDepth(sampler2DShadow s,float p) { return texture(s,vec3(p)); }"
						+ "float fetchDepth(sampler2DShadow s,int p) { return texture(s,vec3(p)); }",
						"fetchDepth(shadowtex0,1); fetchDepth(shadowtex0,0.5);", Set.of()),
				new Case("float fetchDepth(sampler2DShadow s,vec3 p) { return texture(s,p); }"
						+ "float fetchDepth(sampler2D s,vec3 p) { return texture(s,p.xy).r; }",
						"fetchDepth(shadowtex0,vec3(0.5)); fetchDepth(depthtex0,vec3(0.5));", Set.of("aeth_s_fetchDepth_0")),
				new Case("float fetchDepth(sampler2DShadow s,vec3[2] p) { return texture(s,p[0]); }"
						+ "float fetchDepth(sampler2D s,vec3 p[2]) { return texture(s,p[1].xy).r; }",
						"vec3 p[2]; fetchDepth(shadowtex0,p); fetchDepth(depthtex0,p);", Set.of("aeth_s_fetchDepth_0")),
				new Case("float fetchDepth(sampler2DShadow a,sampler2D b) { return texture(a,vec3(0.5))+texture(b,vec2(0.5)).r; }"
						+ "float fetchDepth(sampler2D a,sampler2DShadow b) { return texture(a,vec2(0.5)).r+texture(b,vec3(0.5)); }",
						"fetchDepth(shadowtex0,depthtex0); fetchDepth(depthtex0,shadowtex0);", Set.of("aeth_s_fetchDepth_0","aeth_s_fetchDepth_1")));
		ASTParser parser = new ASTParser();
		for (Case fixture : cases) {
			var tree = parser.parseTranslationUnit(RootSupplier.DEFAULT, "#version 430 core\n"
					+ "uniform sampler2DShadow shadowtex0; uniform sampler2D depthtex0;\n"
					+ fixture.declarations() + "\nvoid main() {" + fixture.calls() + "}\n");
			String before = ASTPrinter.printSimple(tree);
			boolean[] changed = {false};
			tree.getRoot().indexBuildSession(() -> {
				changed[0] = VulkanLowering.shadowFunctionNames(tree, tree.getRoot());
			});
			if (fixture.renamedCalls().isEmpty()) {
				if (changed[0] || !before.equals(ASTPrinter.printSimple(tree))) {
					throw new AssertionError("a non-colliding sampler function or call was renamed: " + before);
				}
			} else {
				Set<String> calls = new HashSet<>();
				for (FunctionCallExpression call : tree.getRoot().nodeIndex.get(FunctionCallExpression.class)) {
					if (call.getFunctionName() != null) {
						calls.add(call.getFunctionName().getName());
					}
				}
				Set<String> declarations = new HashSet<>();
				for (FunctionPrototype function : tree.getRoot().nodeIndex.get(FunctionPrototype.class)) {
					declarations.add(function.getName().getName());
				}
				if (!changed[0] || !calls.containsAll(fixture.renamedCalls()) || !declarations.containsAll(fixture.renamedCalls())) {
					throw new AssertionError("colliding sampler overload declarations and calls no longer match: " + ASTPrinter.printSimple(tree));
				}
			}
		}
		System.out.println("shadow overload naming: " + cases.size() + " AST cases pass; struct-member/grouped arguments, non-collisions, prototypes and array types preserved");
	}
}
