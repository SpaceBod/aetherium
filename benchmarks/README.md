# Benchmarks

Every Aetherium optimisation is measured here before it is kept. The in-game harness lives in the
development-only source set (`common/src/dev/`, loaded as `aetherium_dev` in Fabric dev runs, never in
the release jar); this folder holds the scenarios, the runners and the tools. Results and reports are written
locally and are not committed.

```
benchmarks/
  run.ps1            runner: prepares golden worlds, launches one game per run, renders the report
  worlds.json        world definitions (seed, notes)
  scenarios/*.json   camera paths and settings, one file per scenario
  tools/report.py    result JSONs -> HTML report (+ Markdown tables), standard library only
  run.py             the same runner for macOS
  results/<date>_<label>/<scenario>_<profile>_<backend>_r<n>.json   raw results (gitignored)
  reports/<date>_<label>.html (+ .md)                               rendered reports (gitignored)
  worlds/<world>-rd<N>/   golden world saves (gitignored, re-created on demand)
```

## Running

```powershell
# Baseline of the whole suite on Vulkan, 3 repeats
.\benchmarks\run.ps1 -Label baseline

# An optimisation against the baseline, interleaved A/B/A/B
.\benchmarks\run.ps1 -Scenario flyover,worldgen-flight -Profile baseline,default -Label shadow-cache

# Only one optimisation switched on, everything else off
.\benchmarks\run.ps1 -Profile baseline -Set shaders.shadow_cache=true -Label only-shadow-cache

# Backend and render-distance sweeps
.\benchmarks\run.ps1 -Backend opengl -Label gl
.\benchmarks\run.ps1 -Scenario idle-vista,flyover -RenderDistance 32 -Label rd32
```

Re-render a report from existing results (for example several folders compared at once):

```powershell
python benchmarks\tools\report.py benchmarks\results\2026-09-27_1500_baseline benchmarks\results\2026-09-28_1000_light -o benchmarks\reports\light-vs-baseline.html
```

**Leave the machine alone while it runs.** Mouse input, other windows, a browser tab playing video or
Windows Update show up directly in the numbers. Keep the power plan fixed (it is recorded) and the game
window unobstructed.

## What one run does

1. `run.ps1` copies the golden world into `fabric/run/saves/`, writes `fabric/run/config/aetherium.json`
   (the profile under test) and `fabric/run/aetherium/bench/request.json` (the scenario), then launches
   `gradlew :fabric:runClient` with `--graphicsBackend <backend> --width <w> --height <h>` and a fixed
   16 GB heap. The game window is moved to the second (non-primary) monitor (`tools/move-window.ps1`).
2. The mod's `BenchDriver` picks up the request at start-up (and renames it, so a relaunch does not
   repeat it), waits for the title screen, sets render/simulation distance and FOV, unlimited FPS,
   vsync off and no pause on focus loss, then opens the world.
3. In the world it forces spectator mode, noon, clear weather, and turns off time, weather and mob
   spawning, so every run sees the same scene.
4. **Settle** (scenarios with `"start": "settled"`): the camera holds the path's first pose until the
   compile queue is empty, the loaded-chunk count is unchanged and almost nothing is being rebuilt for
   3 consecutive seconds. That moment is `load.settledSeconds`. Then `System.gc()` and a short warm-up.
   Scenarios with `"start": "joined"` skip this and measure from the moment the world opens.
5. **Measure**: the camera flies the path, placed at the start of every frame, so the path is timed
   by wall clock and is identical at any frame rate. Every frame is recorded (interval, CPU time,
   GPU time), every frame-graph pass is timed on CPU and GPU, and the timeline and JVM/OS are sampled
   once a second.
6. After a few extra frames (late GPU results), the result JSON is written and the game quits.

Every measured run is its own JVM with its own fresh copy of the world. Runs are interleaved (repeat 1
of every scenario and profile, then repeat 2, ...) so drift on the machine does not favour one variant.

## Golden worlds

Created on first use (or with `-Prepare`) from `worlds.json`: a new world from the seed, spectator,
peaceful. The driver records the **anchor** (spawn column, surface height) in the save as
`aetherium-anchor.json`; scenario paths are offsets from it. It then flies every scenario path that has
`"pregenerate": true` at a third of its speed, settles at each end, returns to the anchor and saves.

A golden world is generated at the largest render distance of the selected scenarios that use it and
named `<world>-rd<N>`. That keeps `worldgen-flight` honest: its direction (west) is never pregenerated,
and nothing beyond the benchmark's own render distance exists around spawn. **Delete `worlds/` after
changing a scenario path, a seed or the render distance of a scenario**, so the golden worlds are rebuilt.

## Scenarios

| Id | Measures | Start |
|----|----------|-------|
| `idle-vista` | Pure render throughput: 40 blocks above spawn, nothing moving, 30 s | settled |
| `panorama-spin` | Visibility churn: two full turns in 24 s, the frustum sweeps every loaded section | settled |
| `flyover` | Chunk streaming from disk: east at 32 blocks/s (elytra) for 40 s | settled |
| `worldgen-flight` | New terrain: west at 24 blocks/s into never-generated chunks (worldgen, light, meshing together) | settled |
| `world-join` | Load: world open to fully built, 30 s from the join | joined |

Scenario file format (`scenarios/<id>.json`):

```json
{
  "id": "flyover",
  "description": "shown in the report",
  "world": "overworld-a",
  "renderDistance": 16, "simulationDistance": 8, "fov": 70, "timeOfDay": 6000,
  "start": "settled",
  "settleTimeoutSeconds": 180,
  "warmupSeconds": 3,
  "pregenerate": true,
  "path": [
    { "t": 0,  "dx": 0,    "dy": 60, "dz": 0, "yaw": -90, "pitch": 20 },
    { "t": 40, "dx": 1280, "dy": 60, "dz": 0, "yaw": -90, "pitch": 20 }
  ]
}
```

`t` is seconds, `dx/dy/dz` are blocks from the anchor, yaw/pitch in degrees (yaw -90 faces east, 90 west,
0 south, 180 north). Keyframes are linearly interpolated; yaw is not wrapped, so 0 -> 720 is two turns.
The measured window is the last keyframe's `t`.

## What is measured

Per frame: **interval** (end of one frame to the end of the next, what an FPS counter inverts), **CPU**
(vanilla's own frame-time figure: render-thread time from frame start to the swapchain blit, without
present and the limiter) and **GPU** (timestamp span of the frame via vanilla's `GpuQueryPool`, read back
several frames later without waiting).

Per frame-graph pass (`main`, `sky`, `clouds`, `weather`, post chains, ...): CPU recording time and GPU
time, through the `FrameGraphBuilder.Inspector` vanilla already passes to `execute`.

Once a second: FPS, loaded chunks, compile queue, visible and rendered sections, sections built and
cancelled, mesh worker time, translucency resorts, integrated server MSPT, camera position; and, on a
separate thread (OS CPU-load reads take ~170 ms on Windows), heap, allocation rate, GC count and time,
process and system CPU load, thread count.

The report derives: average FPS, 1% and 0.1% lows (1000 / mean of the slowest 1% / 0.1% of frames),
frame-time p50/p95/p99/p99.9, stutters per minute (frames longer than max(2.5 x median, median + 10 ms)),
CPU and GPU frame p50/p95 and whether the scene is GPU-bound (GPU p50 at least 85% of frame p50),
sections built per second, mesh worker time, time to settled, world open time, server MSPT, heap peak,
allocation rate, GC pause per minute, process CPU. With 2+ repeats every number gets a standard
deviation, and a delta against the baseline is shown in colour only when it is larger than twice the
combined run-to-run noise.

## Result format (format 1)

```
{ format, mode: "measure", finishedAt,
  meta:     runner facts (machine, versions, label, repeat, profile requested, overrides)
            + game facts (minecraft, profile, optimisations, device{backend,name,vendor,driver,type,
              terrainMultiDrawIndirect}, options{renderDistance,...,framebufferWidth/Height}, jvm{...}, os),
  scenario: the scenario file as run,
  load:     { joinSeconds, settledSeconds, settleTimedOut },
  work:     section compiles/cancels/compile ms/resorts/resort ms inside the measured window,
  frames:   { count, unit: "us", interval[], cpu[], gpu[] (-1 = not timed), untimedGpuFrames },
  passes:   { <pass name>: { cpu[], gpu[] } },
  timeline: [ 1 Hz game samples from world open, with "phase" ],
  system:   [ 1 Hz JVM/OS samples ],
  quick:    { avgFps, p50Ms, p99Ms } }
```

## Caveats

- The baseline is **Aetherium loaded with every optimisation off**, not a jar-free vanilla game. The
  instrumentation (a few null checks per frame, timestamp queries, one small allocation per frame for
  query results) is identical in both, so deltas are fair; absolute numbers are a hair below vanilla.
- Runs use the Fabric dev client (`runClient`). Same JIT and game code as a launcher install, but a
  different classpath layout; compare runs with runs, not with numbers from a launcher.
- `-Profile baseline,baseline` is an A/A test: it measures the noise floor of the machine and the harness.
- For runtime switches, in-run alternation (`AETHERIUM_BENCH_ALTERNATE`) compares off and on inside one
  launch, which is faster and less noisy than separate launches.
