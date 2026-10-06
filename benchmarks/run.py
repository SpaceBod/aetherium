#!/usr/bin/env python3
"""Runs Aetherium benchmark scenarios and renders a comparison report: the macOS (and Linux) port of run.ps1.

Every measured run is its own game launch (fresh JVM, fresh copy of the golden world), driven entirely by the mod's
benchmark driver: it opens the world, waits until the scene is fully built, flies the scenario's camera path while
recording every frame, writes a result JSON and quits. Runs are interleaved (repeat 1 of every scenario x profile, then
repeat 2, ...) so slow drift on the machine (thermals, background work) spreads over all variants.

Golden worlds live in benchmarks/worlds/<id> (gitignored). A missing world is prepared first: created from the seed in
worlds.json, every pregenerate path flown once, saved.

The flags are run.ps1's, in either spelling (-ShaderPack or --shader-pack); lists take commas as in PowerShell:

  benchmarks/run.py -Scenario ground-vista-quick -Play -ShaderPack BSL_v10.1.8.zip
  benchmarks/run.py -Scenario ground-vista-quick -Profile baseline,default -Label my-change
  benchmarks/run.py -Scenario idle-vista,flyover -Profile baseline,default -Repeat 3 -Label light-map-clone

macOS only:
  -Heap 8G        fixed game heap (AETHERIUM_HEAP; default 8G here, 16G in run.ps1: the heap shares unified memory
                  with the GPU)
  -VkDebug        Vulkan validation layers and MoltenVK warnings, through Homebrew's Vulkan loader and MoltenVK instead
                  of the MoltenVK the game bundles (brew install vulkan-loader vulkan-validationlayers molten-vk)
  -MetalHud       Metal performance HUD over the game window (FPS, GPU time, memory)
"""
import argparse
import datetime
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent
ROOT = BENCH.parent
RUN_DIR = ROOT / "fabric" / "run"
GRADLE = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
MACOS = sys.platform == "darwin"
BREW = Path("/opt/homebrew") if Path("/opt/homebrew").is_dir() else Path("/usr/local")


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)

    def opt(ps, gnu, **kw):
        ap.add_argument("-" + ps, "--" + gnu, **kw)

    def csv(text):
        return [x for x in text.split(",") if x]

    opt("Scenario", "scenario", type=csv, default=None, help="scenario ids (scenarios/*.json); default: all")
    opt("Profile", "profile", type=csv, default=None, help="baseline and/or default, each with optional +overrides")
    opt("Set", "set", type=csv, default=[], help="optimisation overrides on every profile, e.g. light.no_map_clone=true")
    opt("Backend", "backend", choices=["vulkan", "opengl"], default="vulkan")
    opt("Repeat", "repeat", type=int, default=3)
    opt("RenderDistance", "render-distance", type=int, default=0)
    # Window size in points: on a Retina display the framebuffer is twice that in each direction.
    opt("Width", "width", type=int, default=1280 if MACOS else 1920)
    opt("Height", "height", type=int, default=720 if MACOS else 1080)
    opt("Label", "label", default="run")
    opt("TimeoutMinutes", "timeout-minutes", type=int, default=15)
    opt("Prepare", "prepare", action="store_true")
    opt("VerifyLight", "verify-light", action="store_true")
    opt("ShaderPack", "shader-pack", default="", help="pack file name in fabric/run/shaderpacks")
    opt("RenderScale", "render-scale", type=int, default=100, choices=range(50, 101), metavar="50-100")
    opt("Play", "play", action="store_true")
    opt("DistantHorizons", "distant-horizons", action="store_true")
    opt("NoReport", "no-report", action="store_true")
    opt("Heap", "heap", default=os.environ.get("AETHERIUM_HEAP", "8G" if MACOS else "16G"))
    opt("VkDebug", "vk-debug", action="store_true")
    opt("MetalHud", "metal-hud", action="store_true")
    a = ap.parse_args()
    a.profile_given = a.profile is not None
    a.profile = a.profile or ["baseline"]
    return a


def write_utf8(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def sh(*cmd) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def machine():
    if MACOS:
        gpus = []
        try:
            for d in json.loads(sh("system_profiler", "-json", "SPDisplaysDataType")).get("SPDisplaysDataType", []):
                gpus.append(f"{d.get('sppci_model', '?')} ({d.get('sppci_cores', '?')} cores, {d.get('spdisplays_mtlgpufamilysupport', 'Metal')})")
        except ValueError:
            pass
        power = sh("pmset", "-g", "batt").splitlines()
        lowpower = re.search(r"lowpowermode\s+(\d)", sh("pmset", "-g"))
        return {
            "host": platform.node(),
            "cpu": sh("sysctl", "-n", "machdep.cpu.brand_string"),
            "cores": int(sh("sysctl", "-n", "hw.perflevel0.physicalcpu") or 0) + int(sh("sysctl", "-n", "hw.perflevel1.physicalcpu") or 0)
                     or int(sh("sysctl", "-n", "hw.physicalcpu") or 0),
            "threads": int(sh("sysctl", "-n", "hw.logicalcpu") or 0),
            "ramGB": round(int(sh("sysctl", "-n", "hw.memsize") or 0) / 2**30, 1),
            "gpus": gpus,
            "os": f"macOS {sh('sw_vers', '-productVersion')} ({sh('sw_vers', '-buildVersion')})",
            "power": (power[0].replace("Now drawing from ", "").strip("'") if power else "?")
                     + (" + low power mode" if lowpower and lowpower.group(1) == "1" else ""),
        }
    return {"host": platform.node(), "cpu": platform.processor() or platform.machine(), "cores": os.cpu_count(),
            "threads": os.cpu_count(), "ramGB": 0, "gpus": [], "os": platform.platform(), "power": "?"}


def gradle_property(name):
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    return None


class Runner:
    def __init__(self, a):
        self.a = a
        self.log = BENCH / "results" / ".last-game.log"

    # ------------------------------------------------------------ game launches

    def env(self):
        env = dict(os.environ)
        env["AETHERIUM_HEAP"] = self.a.heap
        jvm = env.get("AETHERIUM_JVM_ARGS", "").split()
        if self.a.vk_debug:
            loader = BREW / "lib" / "libvulkan.1.dylib"
            icd = BREW / "etc" / "vulkan" / "icd.d" / "MoltenVK_icd.json"
            layers = BREW / "share" / "vulkan" / "explicit_layer.d"
            missing = [str(p) for p in (loader, icd, layers) if not p.exists()]
            if missing:
                sys.exit(f"-VkDebug needs Homebrew's Vulkan loader, MoltenVK and validation layers ({', '.join(missing)} missing):\n"
                         "  brew install vulkan-loader molten-vk vulkan-validationlayers")
            # LWJGL loads this loader instead of the MoltenVK in the game's natives; the loader finds Homebrew's MoltenVK.
            jvm.append(f"-Dorg.lwjgl.vulkan.libname={loader}")
            env["VK_DRIVER_FILES"] = str(icd)
            env["VK_ICD_FILENAMES"] = str(icd)
            env["VK_LAYER_PATH"] = str(layers)
            # The layer manifest names its library without a path (libVkLayer_khronos_validation.dylib): let dlopen find it.
            env["DYLD_FALLBACK_LIBRARY_PATH"] = os.pathsep.join(filter(None, [str(BREW / "lib"), env.get("DYLD_FALLBACK_LIBRARY_PATH")]))
            env["MVK_CONFIG_LOG_LEVEL"] = "2"  # warnings: unsupported features and shader conversion errors
        if self.a.metal_hud:
            env["MTL_HUD_ENABLED"] = "1"
        if jvm:
            env["AETHERIUM_JVM_ARGS"] = " ".join(jvm)
        return env

    def client_args(self):
        args = f"--graphicsBackend {self.a.backend} --width {self.a.width} --height {self.a.height}"
        return args + (" --vulkanValidation --renderDebugLabels" if self.a.vk_debug else "")

    def launch(self):
        self.log.parent.mkdir(parents=True, exist_ok=True)
        cmd = [str(GRADLE), ":fabric:runClient", f"-PclientArgs={self.client_args()}", "--console=plain", "-q"]
        out = open(self.log, "w", encoding="utf-8")
        err = open(str(self.log) + ".err", "w", encoding="utf-8")
        return subprocess.Popen(cmd, cwd=ROOT, env=self.env(), stdout=out, stderr=err, start_new_session=True)

    @staticmethod
    def kill(proc):
        """Stops the Gradle client and the game JVM it started (a child of the Gradle daemon, not of the client)."""
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            pass
        subprocess.run(["pkill", "-f", "net.fabricmc.devlaunchinjector.Main"], capture_output=True)

    def invoke_game(self, request: str, result_path: Path, config_json: str):
        """Launches the dev client once and waits for it. The mod reads aetherium/bench/request.json on start-up."""
        write_utf8(RUN_DIR / "aetherium" / "bench" / "request.json", request)
        write_utf8(RUN_DIR / "config" / "aetherium.json", config_json)
        result_path.unlink(missing_ok=True)
        proc = self.launch()
        try:
            proc.wait(timeout=self.a.timeout_minutes * 60)
        except subprocess.TimeoutExpired:
            print(f"WARNING: run exceeded {self.a.timeout_minutes} min, killing the game", file=sys.stderr)
            self.kill(proc)
        except KeyboardInterrupt:
            self.kill(proc)
            raise
        if not result_path.exists():
            sys.exit(f"No result written ({result_path}). See {self.log}(.err) and fabric/run/logs/latest.log")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if result.get("error"):
            sys.exit(f"Benchmark failed: {result['error']}")
        return result

    def new_config(self, profile_spec: str) -> str:
        """A profile may carry its own overrides after '+', e.g. "default+gpu.cull" or "default+gpu.cull=false"."""
        parts = profile_spec.split("+")
        opts = {}
        for s in self.a.set + parts[1:]:
            k, _, v = s.partition("=")
            opts[k] = v.lower() == "true" if v else True
        config = {"profile": parts[0], "optimisations": opts, "bench": {"gpuTimers": True, "passTimers": True},
                  "debug": {"verifyLightSnapshots": self.a.verify_light}}
        if self.a.shader_pack:
            config["shaders"] = {"enabled": True, "pack": self.a.shader_pack, "renderScale": self.a.render_scale}
        return json.dumps(config, indent=2)

    # ------------------------------------------------------------ main flow

    def run(self):
        a = self.a
        mods = RUN_DIR / "mods"
        for jar in mods.glob("DistantHorizons*.jar"):
            jar.unlink()
        if a.distant_horizons:
            lod = sorted((ROOT / "libs").glob("DistantHorizons-fabric-*.jar"))
            if not lod:
                sys.exit("libs/DistantHorizons-fabric-*.jar missing: run benchmarks/tools/build-lod.sh first")
            mods.mkdir(parents=True, exist_ok=True)
            shutil.copy2(lod[0], mods)
        if a.shader_pack and not (RUN_DIR / "shaderpacks" / a.shader_pack).exists():
            have = ", ".join(p.name for p in (RUN_DIR / "shaderpacks").glob("*")) or "none: run benchmarks/tools/fetch-packs.sh"
            sys.exit(f"Shader pack '{a.shader_pack}' is not in fabric/run/shaderpacks (have: {have})")

        # Scenarios and worlds.
        files = sorted((BENCH / "scenarios").glob("*.json"))
        scenarios = a.scenario or [f.stem for f in files]
        worlds = json.loads((BENCH / "worlds.json").read_text(encoding="utf-8"))
        text, world, rd = {}, {}, {}
        for f in files:
            t = f.read_text(encoding="utf-8")
            if a.render_distance > 0:
                t = re.sub(r'"renderDistance":\s*\d+', f'"renderDistance": {a.render_distance}', t)
                t = re.sub(r'"id":\s*"([^"]+)"', rf'"id": "\1@rd{a.render_distance}"', t)
            parsed = json.loads(t)
            text[f.stem], world[f.stem], rd[f.stem] = parsed, parsed["world"], int(parsed["renderDistance"])
        for s in scenarios:
            if s not in text:
                sys.exit(f"Unknown scenario '{s}' (see benchmarks/scenarios/)")

        meta = {
            "runner": "benchmarks/run.py",
            "modVersion": gradle_property("mod_version"),
            "loader": "fabric",
            "loaderVersion": gradle_property("loader_version"),
            "backendRequested": a.backend,
            "machine": machine(),
            "extraMods": sorted(p.name for p in mods.glob("*.jar")),
            "heap": a.heap,
        }
        if a.vk_debug:
            meta["vkDebug"] = True

        print("Building...", flush=True)
        if subprocess.run([str(GRADLE), ":fabric:classes", "--console=plain", "-q"], cwd=ROOT).returncode != 0:
            sys.exit("Build failed")

        # A golden world is generated at exactly the render distance of the scenarios that use it: generating further
        # out would pre-build terrain a worldgen scenario is meant to generate live. Keyed "<world>-rd<N>".
        def golden_name(sid):
            w = world[sid]
            return f"{w}-rd{max(rd[s] for s in scenarios if world[s] == w)}"

        for golden in sorted({golden_name(s) for s in scenarios}):
            w, prep_rd = golden.rsplit("-rd", 1)
            dest = BENCH / "worlds" / golden
            if (dest / "level.dat").exists() and not a.prepare:
                continue
            d = worlds.get(w)
            if not d:
                sys.exit(f"World '{w}' is not defined in worlds.json")
            print(f"Preparing golden world '{golden}' (seed {d['seed']}, render distance {prep_rd})...", flush=True)
            save_name = f"aeth-prep-{golden}"
            save = RUN_DIR / "saves" / save_name
            shutil.rmtree(save, ignore_errors=True)
            out = BENCH / "worlds" / f".prepare-{golden}.json"
            req = {"mode": "prepare", "saveName": save_name, "seed": int(d["seed"]), "prepareRenderDistance": int(prep_rd),
                   "prepareTimeScale": 3.0, "output": str(out), "exitWhenDone": True,
                   "scenarios": [text[f.stem] for f in files if world[f.stem] == w], "meta": meta}
            r = self.invoke_game(json.dumps(req, indent=2), out, self.new_config("baseline"))
            shutil.rmtree(dest, ignore_errors=True)
            shutil.copytree(save, dest)
            print(f"  prepared in {r.get('seconds', 0):.0f} s")

        if a.play:
            self.play(scenarios[0], golden_name(scenarios[0]), rd, meta)
            return

        self.measure(scenarios, golden_name, text, meta)

    def play(self, sid, golden, rd, meta):
        a = self.a
        save_name = f"aeth-play-{golden}"
        save = RUN_DIR / "saves" / save_name
        shutil.rmtree(save, ignore_errors=True)
        shutil.copytree(BENCH / "worlds" / golden, save)
        req = {"mode": "play", "saveName": save_name, "prepareRenderDistance": rd[sid], "exitWhenDone": False, "meta": meta}
        write_utf8(RUN_DIR / "aetherium" / "bench" / "request.json", json.dumps(req, indent=2))
        # Play mode shows the build as shipped: the default profile unless -Profile is given.
        write_utf8(RUN_DIR / "config" / "aetherium.json", self.new_config(a.profile[0] if a.profile_given else "default"))
        (RUN_DIR / "screenshots" / "aetherium-play.png").unlink(missing_ok=True)
        self.launch()
        print(f"Game launching into '{save_name}' (creative, on the ground). Close it when you are done.")
        print(f"  log: {self.log} · game log: fabric/run/logs/latest.log")

    def measure(self, scenarios, golden_name, text, meta):
        a = self.a
        stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H%M")
        results = BENCH / "results" / f"{stamp}_{a.label}"
        results.mkdir(parents=True, exist_ok=True)
        total = a.repeat * len(scenarios) * len(a.profile)
        n = 0
        print(f"Running {total} benchmark(s) into {results}")
        print("Leave the machine alone while this runs: input, other windows and background load all show up in the numbers."
              + (" Keep it on power and out of low power mode." if MACOS else ""))
        for rep in range(1, a.repeat + 1):
            for s in scenarios:
                for p in a.profile:
                    n += 1
                    golden = golden_name(s)
                    save_name = f"aeth-bench-{golden}"
                    save = RUN_DIR / "saves" / save_name
                    shutil.rmtree(save, ignore_errors=True)
                    shutil.copytree(BENCH / "worlds" / golden, save)
                    sid = text[s]["id"]
                    out = results / f"{sid}_{p}_{a.backend}_r{rep}.json"
                    run_meta = dict(meta, label=a.label, repeat=rep, repeats=a.repeat, profileRequested=p, overrides=a.set)
                    req = {"mode": "measure", "saveName": save_name, "output": str(out), "exitWhenDone": True,
                           "scenario": text[s], "meta": run_meta}
                    print(f"[{n}/{total}] {sid} / {p} / {a.backend} / run {rep}", flush=True)
                    r = self.invoke_game(json.dumps(req, indent=2), out, self.new_config(p))
                    q, load = r.get("quick", {}), r.get("load", {})
                    print(f"        {q.get('avgFps', 0):.1f} fps avg, p99 {q.get('p99Ms', 0):.2f} ms, settled after {load.get('settledSeconds', 0):.1f} s")
        if not a.no_report:
            report = BENCH / "reports" / f"{stamp}_{a.label}.html"
            if subprocess.run([sys.executable, str(BENCH / "tools" / "report.py"), str(results), "-o", str(report)]).returncode == 0:
                print(f"Report: {report}")


if __name__ == "__main__":
    Runner(parse_args()).run()
