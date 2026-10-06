"""Launches plain vanilla Minecraft (no loader, no Aetherium) from the files Gradle already cached, for side-by-side
visual checks against the dev client.

Game dir: fabric/run-vanilla. On every launch it copies fabric/run/options.txt (same video settings as the dev client)
and a fresh copy of the golden world, then opens that world directly (quick play). Offline account, so singleplayer only.

Usage: python benchmarks/tools/run-vanilla.py [--world overworld-a-rd16] [--backend vulkan|opengl] [--version 26.3]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = Path.home() / ".gradle" / "caches"
LOOM = GRADLE / "fabric-loom"
MODULES = GRADLE / "modules-2" / "files-2.1"
# Same fixed heap as the dev client (fabric/build.gradle).
HEAP = os.environ.get("AETHERIUM_HEAP", "8G" if sys.platform == "darwin" else "16G")
# The launcher's OS name for this machine (library and argument rules).
OS_NAME = {"win32": "windows", "darwin": "osx"}.get(sys.platform, "linux")


def find_java():
    home = os.environ.get("JAVA_HOME")
    if not home and OS_NAME == "osx":
        home = subprocess.run(["/usr/libexec/java_home", "-v", "25"], capture_output=True, text=True).stdout.strip()
    if not home and OS_NAME == "windows":
        home = r"C:\Program Files\Java\jdk-25.0.4.1"
    return Path(home) / "bin" / ("java.exe" if OS_NAME == "windows" else "java") if home else Path("java")


def allowed(lib):
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        os_name = r.get("os", {}).get("name")
        if os_name is None or os_name == OS_NAME:
            ok = r["action"] == "allow"
    return ok


def jvm_args(info, natives):
    out = []
    for arg in info["arguments"]["jvm"]:
        if isinstance(arg, dict):
            if not allowed(arg) or any("arch" in r.get("os", {}) for r in arg["rules"]):
                continue
            arg = arg["value"]
        for a in ([arg] if isinstance(arg, str) else arg):
            if a in ("-cp", "${classpath}") or "${launcher_" in a:
                continue
            out.append(a.replace("${natives_directory}", str(natives)))
    return out


def find_library(lib):
    artifact = lib.get("downloads", {}).get("artifact")
    if not artifact:
        return None
    file_name = artifact["path"].rsplit("/", 1)[1]
    group, name, version = lib["name"].split(":")[:3]
    base = MODULES / group / name / version
    hits = list(base.glob(f"*/{file_name}")) if base.is_dir() else []
    if hits:
        return hits[0]
    hits = list((LOOM / "minecraftMaven").rglob(file_name))
    return hits[0] if hits else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="26.3")
    ap.add_argument("--world", default="overworld-a-rd16")
    ap.add_argument("--backend", default="vulkan", choices=["vulkan", "opengl"])
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    args = ap.parse_args()

    version_dir = LOOM / args.version
    info = json.loads((version_dir / "mojang_minecraft_info.json").read_text(encoding="utf-8"))

    classpath, missing = [], []
    for lib in info["libraries"]:
        # Windows: the x64 natives only. macOS lists both architectures under one rule; LWJGL picks its own.
        if not allowed(lib) or OS_NAME == "windows" and lib["name"].endswith("-arm64"):
            continue
        path = find_library(lib)
        if path:
            classpath.append(str(path))
        else:
            missing.append(lib["name"])
    if missing:
        sys.exit("Libraries not in the Gradle cache (run a dev client once first):\n  " + "\n  ".join(missing))
    classpath.append(str(version_dir / "minecraft-client.jar"))

    game_dir = ROOT / "fabric" / "run-vanilla"
    game_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "fabric" / "run" / "options.txt", game_dir / "options.txt")
    save_name = f"vanilla-{args.world}"
    save = game_dir / "saves" / save_name
    if save.exists():
        shutil.rmtree(save)
    shutil.copytree(ROOT / "benchmarks" / "worlds" / args.world, save)

    asset_index = f"{args.version}-{info['assetIndex']['id']}"
    cmd = [
        str(find_java()), f"-Xms{HEAP}", f"-Xmx{HEAP}",
        # The launcher's JVM arguments for this version (natives extract into the game dir)
        *jvm_args(info, game_dir / "natives"),
        "-cp", os.pathsep.join(classpath),
        info["mainClass"],
        "--username", "Vanilla", "--version", args.version, "--versionType", "release",
        "--accessToken", "0", "--uuid", "00000000-0000-0000-0000-000000000000",
        "--gameDir", str(game_dir),
        "--assetsDir", str(LOOM / "assets"), "--assetIndex", asset_index,
        "--width", str(args.width), "--height", str(args.height),
        "--graphicsBackend", args.backend,
        "--quickPlaySingleplayer", save_name,
    ]
    log = open(game_dir / "launch.log", "w", encoding="utf-8")
    # Detached (on Windows also out of the caller's job object), so the game outlives this script and the shell that ran it.
    if OS_NAME != "windows":
        subprocess.Popen(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    else:
        detached = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        try:
            subprocess.Popen(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT,
                             creationflags=detached | subprocess.CREATE_BREAKAWAY_FROM_JOB)
        except OSError:
            subprocess.Popen(cmd, cwd=game_dir, stdout=log, stderr=subprocess.STDOUT, creationflags=detached)
    print(f"Vanilla {args.version} launching ({args.backend}), world '{save_name}', log {game_dir / 'launch.log'}")


if __name__ == "__main__":
    main()
