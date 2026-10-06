# Performance

Every optimisation in Aetherium is a switch with its own id. Each one is checked with the game-free harness, then
measured in game against the **baseline** profile (every switch off), before it is turned on by default. All of them
can be turned off under **Options › Aetherium... › Performance** or **Shaders**, or in `config/aetherium.json`.

- [Shader engine](#shader-engine)
- [Memory](#memory)
- [Experimental](#experimental)
- [Measured results](#measured-results)
- [How it is measured](#how-it-is-measured)

## Shader engine

Unless noted, the picture is identical with the switch on or off.

| Id | Name | What it does |
|---|---|---|
| `shaders.lean_frame` | Lean frame | Skips the depth copies, conversions and clears that the loaded pack never reads. |
| `shaders.shadow_cache` | Shadow cache | Keeps the terrain part of the shadow map between frames and redraws it only when the sun moves a fiftieth of a degree, you move to another spot or a chunk changes. Terrain shadows can trail the sun by that much; mobs and players cast shadows every frame. |
| `shaders.shadow_cache_bind` | Direct shadow cache | When the cached shadow map is reused and nothing is drawn on top, packs read it directly instead of a copy. |
| `shaders.hw_shadow_compare` | Hardware shadow filtering | Shadow-map comparisons use the GPU's built-in depth-comparison filtering (one lookup) instead of four lookups and a blend. |
| `shaders.reach_snapshots` | Targeted snapshots | Each world pass copies only the pack buffers its shaders can actually read. |
| `shaders.settled_bindings` | Settled texture bindings | Works out each shader's textures once per render pass and rebinds only what changed. Helps most with many mobs on screen. |
| `shaders.frozen_fullscreen` | Prepared screen passes | Full-screen passes keep their texture plan from load and upload their settings in one go per stage. |
| `shaders.cpu_matrices` | Precomputed terrain matrices | Terrain shaders read the frame's normal and inverse view matrices instead of computing them per vertex and pixel. |
| `shaders.blit_mips` | Blitted mipmaps | Fills packs' mip chains (bloom, exposure, shadow mips) with image copies instead of a draw pass per level, and only when the buffer changed. |
| `shaders.loadop_clears` | Clears on load | Per-frame buffer clears happen as the first pass that draws into each buffer starts, instead of one clear (and GPU stall) per buffer. |
| `shaders.narrow_barrier` | Narrow pass barriers | After each of the engine's own passes the GPU waits only for what that pass wrote. |
| `shaders.inline_compute` | Inline compute | Records packs' compute programs into the frame's own command list instead of splitting it. |
| `shaders.flip_only_doubles` | Single buffers where possible | Gives a pack buffer its second (ping-pong) copy only when a pass needs one. Saves video memory. |
| `shaders.transform_cache` | Shader transform cache | Keeps each program's converted code on disk (up to 256 MiB), so loading the same pack again skips most of the work. |
| `shaders.early_hand` | Lit first-person hand | Draws the hand with the world, so packs that light the scene afterwards light it too. A visual choice, not a speed-up. |
| `shaders.nan_preserve` | Exact NaN handling | Keeps packs' checks for invalid values working on Vulkan as on OpenGL. A correctness switch; some packs turn black without it. |
| `shaders.zero_locals` | Zeroed shader variables | Variables read before they are written start at zero, as OpenGL drivers give them. A correctness switch; prevents black faces and flickering lines. |

## Memory

| Id | Name | What it does |
|---|---|---|
| `mem.state_cache_dedup` | Shared block shapes | Block states share identical collision shapes and face data (about 6,000 shape objects become about 330). |
| `mem.component_patch` | Shared empty item data | Item data that has been emptied again shares one empty table. |
| `mem.palette_lock` | Lightweight chunk data checks | Chunk storage checks for cross-thread misuse with one number instead of a lock object per section (about 13 MB at 16 chunks). |

## Experimental

Off by default.

| Id | Name | What it does |
|---|---|---|
| `gpu.pipeline_cache` | Pipeline cache | Keeps compiled GPU pipelines on disk so later launches and pack loads start faster. Ignored after a driver or GPU change. No gain measured yet. |

## Measured results

Measured on 2026-10-06 with Complementary Unbound in one static scene (the [test setup](#test-setup) is below). Each
result compares variants measured in the same session, interleaved, under the same conditions, so the numbers are
given as percentage differences rather than absolute frame rates.

### All optimisations together

The default profile against the baseline (every optimisation off):

- Frame rate **+4.1%**, 1% lows **+6.0%**, 99th-percentile frame time **−4.5%**.
- GPU frame time **−4.0%**, CPU frame time **−10.5%**.
- Retained heap after the world settled **−3.7%** (about 20 MB, with the shader cache empty in both).

The biggest change is on the CPU side, yet only part of it reaches the frame rate. In these runs the GPU was busy for
about 95% of each frame, so the GPU set the pace most of the time. The CPUs are also a likely limit, though: the test
machine has two server Xeons with modest single-core speed (2.2 GHz base), and Minecraft does most of its frame work
on one thread, so many cores do not help it. Expect different results on a desktop CPU with fast single cores, and
more of the CPU saving to show on machines where the CPU sets the pace. The baseline also varied more between its runs
than the default profile did (one of its three runs was 18% faster than the other two), so the frame-rate figure is
approximate; the GPU, CPU and 99th-percentile figures are steadier.

### Hardware shadow filtering (`shaders.hw_shadow_compare`)

Switching the shadow-map comparisons to the GPU's built-in depth-comparison filtering gave frame rate **+4.9%**, GPU
frame time **−4.8%**, CPU frame time **−6.1%** and 99th-percentile frame time **−2.7%**. Each shadow lookup becomes one
filtered lookup instead of four lookups and a blend in the shader, so packs that sample their shadow maps a lot gain
the most.

### Single buffers where possible (`shaders.flip_only_doubles`)

Complementary Unbound's colour targets use **33.6 MiB less video memory**, because only the targets that a pass
actually writes or flips get a second texture. The saving is fixed per pack and resolution, so it is the same in every
run.

### Shader transform cache (`shaders.transform_cache`)

Loading the pack a second time made it ready **44% sooner** (8.8 s cold, 4.9 s warm). A warm load took 88 of the
pack's 125 programs from the cache; the other 37 were converted again, so the cache is not hitting as often as it
should and the gain should grow once that is fixed (see the [Roadmap](roadmap.md#engineering)).

### Pipeline cache (`gpu.pipeline_cache`)

No measurable gain. With the shader cache already warm, the pack was ready in 5.1 s with the pipeline cache against
4.9 s without, which is within run-to-run noise: the driver already builds these pipelines quickly. It stays off.

### Test setup

| Setting | Value |
|---|---|
| Shader pack | Complementary Unbound r5.9.3, pack profile HIGH, no options changed |
| Scene | One static view: the benchmark world (seed 20260927, default overworld), standing at spawn looking out over the terrain (yaw 45°, pitch 10°), noon, clear weather, spectator mode. Measured for 15 s after the terrain finished building and a 3 s warm-up. |
| Game settings | 1920×1080 window, render distance 16, simulation distance 8, FOV 70, vsync off, frame rate unlimited, render scale 100% |
| Runs | One game launch per run, 3 runs per variant (2 for the pipeline cache), variants interleaved so drift spreads over all of them |
| CPU | 2 × Intel Xeon Gold 5220R: 48 cores, 96 threads, 2.2 GHz base, 4.0 GHz turbo. Java sees 48 threads (one Windows processor group). |
| Memory | 383 GB RAM; Java heap fixed at 16 GB |
| GPU | NVIDIA GeForce RTX 3080, driver 616.56 (Vulkan 1.4.351) |
| Software | Windows 11 Pro for Workstations (build 26200), Java 25.0.4.1, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0, Sodium mc26.3-0.9.3-alpha.1 |

## How it is measured

The in-game benchmark opens a fixed world, waits until the terrain has finished building, then follows a scripted
camera path (here a static view) and records every frame's interval, CPU time and GPU time, with GPU time per render
pass. Each run is its own game launch; variants are interleaved. See
[Development](development.md#benchmarks) and [`benchmarks/README.md`](../benchmarks/README.md).
