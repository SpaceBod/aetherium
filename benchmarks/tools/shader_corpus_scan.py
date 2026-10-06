#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-only
"""
Shader pack corpus scan: for every pack in a shaderpacks folder, lists the uniforms, samplers and shaders.properties
keys its sources use that the engine does not provide. The provided names are read from the engine's own sources,
so the list follows the code.

    python benchmarks/tools/shader_corpus_scan.py [shaderpacks folder] [--out report.md] [--all]

Default folder: fabric/run/shaderpacks. Packs are zips or folders holding a shaders/ directory. The scan is textual:
names inside disabled #ifdef branches count as used (other mods' uniforms such as an LOD mod's show up), so
"missing" means "worth a look", not "broken". --all lists the provided names too.
"""
import argparse
import io
import os
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ENGINE = REPO / "common" / "src" / "client" / "java" / "dev" / "spacebod" / "aetherium" / "shaders"
SOURCE_EXT = (".vsh", ".fsh", ".gsh", ".csh", ".tcs", ".tes", ".glsl")

UNIFORM_DECL = re.compile(r"^\s*(?:layout\s*\([^)]*\)\s*)?uniform\s+(?:(?:highp|mediump|lowp|flat|coherent|readonly|writeonly|restrict|volatile)\s+)*(\w+)\s+(\w+)\s*(?:\[[^\]]*\])?\s*;", re.M)
PROPERTY_LINE = re.compile(r"^\s*([A-Za-z0-9_.\[\]:-]+)\s*=\s*(.*)$", re.M)
STRING_LITERAL = re.compile(r'"([A-Za-z_]\w*)"')

# Samplers the engine binds by name (besides colortexN, shadowcolorN and the pack's own textures and images).
BUILTIN_SAMPLERS = {
    "texture", "tex", "gtexture", "lightmap", "normals", "specular", "noisetex", "shadow", "watershadow",
    "shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW", "shadowcolor", "depthtex0", "depthtex1", "depthtex2",
    "gdepthtex", "gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4",
    "dhDepthTex", "dhDepthTex0", "dhDepthTex1",
}
SAMPLER_PATTERNS = [re.compile(p) for p in (r"colortex\d+$", r"shadowcolor\d$", r"colorimg\d$", r"shadowcolorimg\d$")]


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def provided_uniforms():
    """
    Identifier-like string literals in the uniform providers, plus names built as "prefix" + name + "suffix" from the
    capitalised literals of the same file, plus names the shader conversion rewrites itself (a superset).
    """
    names = set()
    template = re.compile(r'"(\w*)"\s*\+\s*\w+(?:\s*\+\s*"(\w*)")?')
    files = [j for folder in ("uniforms", "compat") for j in (ENGINE / folder).rglob("*.java")]
    files += list((ENGINE / "pipeline" / "transform" / "transformer").glob("*.java"))
    for java in files:
        text = read(java)
        literals = set(STRING_LITERAL.findall(text))
        names.update(literals)
        for prefix, suffix in template.findall(text):
            names.update(prefix + lit + (suffix or "") for lit in literals if lit[:1].isupper())
    for java in (ENGINE / "engine").glob("*.java"):
        text = read(java)
        for call in re.finditer(r"\.uniform\w*\(([^;]*)", text):
            names.update(STRING_LITERAL.findall(call.group(1)))
    return names


def handled_property_keys():
    text = read(ENGINE / "shaderpack" / "properties" / "ShaderProperties.java")
    exact = set(re.findall(r'(?:key|name)\.equals\("([^"]+)"\)', text))
    exact.update(re.findall(r'"([^"]+)"\.equals\(key\)', text))
    exact.update(re.findall(r'Directive\(\s*key\s*,\s*value\s*,\s*"([^"]+)"', text))
    prefixes = set(re.findall(r'(?:key|name)\.startsWith\("([^"]+)"\)', text))
    prefixes.update(re.findall(r'Directive\(\s*"([^"]+\.)"', text))
    # Built from the pack-facing tag at run time.
    exact.update({"iris.features.required", "iris.features.optional"})
    return exact, prefixes


def pack_files(pack):
    """(path inside shaders/, text) for every source and properties file of the pack."""
    if pack.is_dir():
        root = pack / "shaders"
        for f in root.rglob("*"):
            if f.is_file() and (f.suffix in SOURCE_EXT or f.suffix == ".properties"):
                yield str(f.relative_to(root)).replace(os.sep, "/"), read(f)
        return
    with zipfile.ZipFile(pack) as z:
        for info in z.infolist():
            name = info.filename
            if "shaders/" not in name or info.is_dir():
                continue
            rel = name[name.index("shaders/") + len("shaders/"):]
            if rel.endswith(SOURCE_EXT) or rel.endswith(".properties"):
                yield rel, io.TextIOWrapper(z.open(info), encoding="utf-8", errors="replace").read()


def scan(pack, uniforms, keys, prefixes):
    used_uniforms = defaultdict(set)
    used_samplers = defaultdict(set)
    used_keys = defaultdict(set)
    custom_uniforms = set()
    custom_samplers = set()
    for rel, text in pack_files(pack):
        if rel.endswith(".properties"):
            if os.path.basename(rel) != "shaders.properties":
                continue
            # Continuation lines (ending in a backslash) belong to the key above them.
            text = re.sub(r"\\[ \t]*\r?\n", " ", text)
            for key, value in PROPERTY_LINE.findall(text):
                used_keys[key].add(rel)
                parts = key.split(".")
                if parts[0] in ("uniform", "variable") and len(parts) >= 3:
                    custom_uniforms.add(parts[-1])
                elif parts[0] in ("texture", "customTexture") and len(parts) >= 2:
                    custom_samplers.add(parts[-1])
                elif parts[0] == "image" and value.split():
                    # image.<name> = <sampler name> <format> ...
                    custom_samplers.add(parts[-1])
                    custom_samplers.add(value.split()[0])
            continue
        for kind, name in UNIFORM_DECL.findall(text):
            target = used_samplers if "sampler" in kind or "image" in kind else used_uniforms
            target[name].add(rel)

    def sampler_known(name):
        return name in BUILTIN_SAMPLERS or name in custom_samplers or any(p.match(name) for p in SAMPLER_PATTERNS)

    def key_known(key):
        return key in keys or any(key.startswith(p) for p in prefixes)

    rows = []
    for name in sorted(used_uniforms):
        rows.append(("uniform", name, name in uniforms or name in custom_uniforms, len(used_uniforms[name])))
    for name in sorted(used_samplers):
        rows.append(("sampler", name, sampler_known(name), len(used_samplers[name])))
    for key in sorted(used_keys):
        rows.append(("property", key, key_known(key), len(used_keys[key])))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", nargs="?", default=str(REPO / "fabric" / "run" / "shaderpacks"))
    parser.add_argument("--out", help="write the Markdown report here (default: stdout)")
    parser.add_argument("--all", action="store_true", help="list provided names too")
    args = parser.parse_args()
    folder = Path(args.folder)
    packs = sorted(p for p in folder.iterdir() if p.suffix.lower() == ".zip" or (p.is_dir() and (p / "shaders").is_dir()))
    if not packs:
        sys.exit(f"no packs in {folder}")
    uniforms = provided_uniforms()
    keys, prefixes = handled_property_keys()
    out = [f"# Shader pack corpus scan\n\nEngine: {len(uniforms)} uniform-name candidates, {len(keys)} property keys, "
           f"{len(prefixes)} property prefixes.\n"]
    for pack in packs:
        rows = scan(pack, uniforms, keys, prefixes)
        missing = [r for r in rows if not r[2]]
        shown = rows if args.all else missing
        out.append(f"\n## {pack.name}\n\n{len(rows)} names used, {len(missing)} not provided.\n")
        if shown:
            out.append("\n| kind | name | provided | files |\n|---|---|---|---|\n")
            out.extend(f"| {kind} | `{name}` | {'yes' if ok else 'no'} | {count} |\n" for kind, name, ok, count in shown)
    report = "".join(out)
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(report)


if __name__ == "__main__":
    main()
