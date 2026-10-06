# macOS (Apple silicon)

Minecraft 26.3 runs its Vulkan renderer on macOS through MoltenVK, which ships with the game's own native libraries.
No Vulkan SDK is needed to build or play.

- [Playing](#playing)
- [What is different on MoltenVK](#what-is-different-on-moltenvk)
- [Developing on a Mac](#developing-on-a-mac)

## Playing

Install Aetherium as on any other platform (see the [README](../README.md#installation)). The game must use the Vulkan
graphics API; Aetherium offers to switch it on first start.

<!-- TODO: tested Macs, macOS versions and packs. -->

## What is different on MoltenVK

Metal, which MoltenVK runs on, differs from desktop Vulkan in a few places. Aetherium adapts packs as follows:

- **No OS macro is defined.** Packs read `MC_OS_MAC` as Apple's old OpenGL driver and would switch off compute, image
  stores, coloured lighting and per-buffer blending, all of which Metal has.
- **Hardware shadow comparison** uses MoltenVK's mutable comparison samplers. Without them the comparison runs in the
  shader.
- **No geometry stage.** A geometry stage that only passes each triangle corner on is folded into the fragment
  stage; any other falls back to the game's own shader for that program (logged).
- **Many samplers.** A pass that reads more than 16 samplers in one stage gets its own descriptor set, which
  MoltenVK binds through a Metal argument buffer.
- **Name clashes.** Pack shaders lose the names of their private code before translation, so no name collides with
  Metal's shading language. Every vertex position is `invariant`, and the float-packing built-ins become integer
  arithmetic.
- **32-bit float textures** are sampled nearest, because Metal cannot filter them.

## Developing on a Mac

One command checks the JDK, builds the level-of-detail jars and the mod, downloads BSL, Complementary and Photon into
`fabric/run/shaderpacks`, and runs the compile check:

```bash
bash benchmarks/tools/setup-macos.sh             # add --vk-debug for Homebrew's Vulkan loader and validation layers
```

`benchmarks/run.py` is the macOS version of `run.ps1` and takes the same flags:

```bash
benchmarks/run.py -Scenario ground-vista-quick -Play -ShaderPack BSL_v10.1.8.zip
benchmarks/run.py -Scenario ground-vista-quick -Profile baseline,default -Label my-change
```

Differences from Windows:

- The heap defaults to 8 GB (`-Heap` or `AETHERIUM_HEAP`), because the Java heap shares unified memory with the GPU.
  The window defaults to 1280×720 points, which is 2560×1440 pixels on a Retina display.
- `-VkDebug` runs with validation layers and MoltenVK warnings. It loads Homebrew's Vulkan loader and MoltenVK in place
  of the game's bundled copy, so their versions can differ. `-MetalHud` shows Apple's Metal performance HUD.
- There is no second-monitor window mover and no measurement of other processes' GPU load. Close other apps, and keep
  the Mac on power and out of Low Power Mode while measuring.
- On a Mac, the shader compile check stands in for Apple silicon through MoltenVK and also translates every stage to
  Metal, as MoltenVK does (`AETHERIUM_METAL=0|1`, `AETHERIUM_HARNESS_OS=windows|mac|linux`). The translation also runs
  on Windows; only the final Metal compile needs a Mac.
