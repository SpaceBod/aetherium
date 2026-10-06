# Development

- [Building](#building)
- [Repository layout](#repository-layout)
- [Dev runs](#dev-runs)
- [Game-free test harness](#game-free-test-harness)
- [Benchmarks](#benchmarks)
- [How a change is made](#how-a-change-is-made)
- [Code conventions](#code-conventions)
- [Releasing](#releasing)

## Building

You need JDK 25 (Gradle provisions it through the toolchain), Git and Bash. On Windows, Git Bash works.

The Distant Horizons compatibility code compiles against that mod's jar, which is not committed. Build it once after
cloning:

```bash
bash benchmarks/tools/build-lod.sh
```

This fetches the mod's source at a pinned commit into `build/lod-src`, builds it and copies the jars into `libs/`
(gitignored). Bump the pin in the script to move to a newer version. Then build both loader jars:

```bash
./gradlew :fabric:build :neoforge:build
```

The jars are written to `fabric/build/libs/` and `neoforge/build/libs/`.

## Repository layout

```
common/            loader-independent code, compiled into both jars
  src/main/          game-side code: config, the optimisation registry, mixin plugin, memory optimisations
  src/client/        client code: GPU passes, settings screen, zoom and HUD, and the shader engine (…/aetherium/shaders)
  src/dev/           development-only tooling (benchmark driver, instrumentation), loaded as `aetherium_dev` in
                     Fabric dev runs and never part of the release jar
  src/microbench/    game-free harness: parity tests, microbenchmarks, shader compile checks
fabric/            Fabric entry point and metadata
neoforge/          NeoForge entry point and metadata
build-logic/       shared Gradle conventions (spacebod.base / common / fabric / neoforge)
benchmarks/        in-game benchmark runners, scenarios and tools
docs/              this documentation
```

Inside `common/src/client/java/dev/spacebod/aetherium/`:

| Package | What it holds |
|---|---|
| `client/` | Client entry point, HUD corner panels, crosshair, Vulkan prompt |
| `client/gui/` | The Aetherium settings screen and its setting catalogue |
| `client/gpu/` | Vulkan access, device traits, shader compiler, pipeline cache, compute helpers |
| `client/mixin/` | Mixins, grouped by area (`chunks`, `shaders`, `hud`, `zoom`, ...) |
| `client/zoom/` | Zoom and its readout |
| `shaders/` | The shader engine: pack loading and options (`shaderpack`), GLSL transformation and lowering (`pipeline`), the frame (`engine`), uniforms, terrain (`chunks`), the pack screen (`gui`), compatibility (`compat`) |

## Dev runs

```bash
./gradlew :fabric:runClient
```

Dev runs use a fixed 16 GB heap and fetch Sodium from Modrinth. Put packs into
`fabric/run/shaderpacks`.

## Game-free test harness

Each check takes seconds and needs no game launch.

```bash
./gradlew :common:parity                       # differential tests of the memory optimisations against vanilla
./gradlew :common:microbench                   # block-state memory audit and microbenchmarks
./gradlew :common:microbench -Ponly=lowering   # GLSL lowering fixtures
```

**Shader pack compile check.** This transforms and compiles every program of the given packs to SPIR-V, against
the same terrain vertex format the game uses:

```bash
AETHERIUM_PACKS="path/to/a.zip;path/to/b.zip" ./gradlew :common:microbench -Ponly=shaders
```

Set `AETHERIUM_SHADERS_LOD=1` to also build the level-of-detail programs.
[`benchmarks/tools/shader_corpus_scan.py`](../benchmarks/tools/shader_corpus_scan.py) lists the names a pack uses
that the engine does not provide.

## Benchmarks

[`benchmarks/run.ps1`](../benchmarks/run.ps1) prepares fixed test worlds, launches one game per run, flies a scripted
camera path and renders a report. To compare the default profile with the baseline:

```powershell
.\benchmarks\run.ps1 -Scenario ground-vista-quick -Profile baseline,default -Label my-change
```

Use `-Play` for a hands-on session in the benchmark world, and `-ShaderPack <zip>` to load a pack.
[`benchmarks/README.md`](../benchmarks/README.md) covers the scenarios, metrics and result format. On macOS, use
`benchmarks/run.py` (see [macOS](macos.md#developing-on-a-mac)).

## How a change is made

1. **Check the game first.** Make sure vanilla 26.3 (or Sodium) does not already do it.
2. **Make it a switch.** An optimisation is a mixin package `mixin/opt/<id>` or a runtime check, plus an entry in
   `Optimisations` and a name and description in `SettingsCatalog`.
3. **Prove it without the game.** Add a parity test, a microbenchmark or a compile fixture.
4. **Measure it in game** against the baseline profile, with several repeats.
5. **Turn it on by default** only when it measures as a gain and the picture is unchanged (or the difference is
   documented, as with the shadow cache).

Visual changes are signed off in game by eye.

## Code conventions

- Tabs for indentation; wrap lines at about 160 columns.
- Comments describe this code: what it does and why. No dated notes, plan references or names of other mods.
- Imports of other mods' classes and their mixin targets live only in classes that load when that mod is present.
- Settings shown to players have a name and a description in plain language, and say when they need a restart.
- Every player-facing string is in `assets/aetherium/lang/en_us.json`.

<!-- TODO: formatter/IDE settings (an .editorconfig), if wanted. -->

## Releasing

1. Set `mod_version` in `gradle.properties` to the new version.
2. In `CHANGELOG.md`, move what is under **Unreleased** into a new `## [<version>] - <date>` section, and update the
   compare links at the bottom.
3. Run the shader pack compile check over the tested packs, and start the game once on each loader.
4. Commit and push to `main`, then publish:

```bash
bash scripts/release.sh --dry-run   # builds and shows the jars and release notes
bash scripts/release.sh             # creates the GitHub release v<version> with both jars attached
```

The script refuses to run with uncommitted or unpushed changes, or when the version has already been released. It
takes the release notes from the version's changelog section. `--draft` publishes a draft to check on GitHub first.

<!-- TODO before publishing on Modrinth or CurseForge: a mod icon (assets/aetherium/icon.png, then "icon" in
fabric.mod.json and logoFile in neoforge.mods.toml), and contact links in both metadata files. -->
