# Changelog

All notable changes to Aetherium are listed here. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Fixed

- Alternating anisotropic terrain and ordinary entity draws reuse their albedo samplers instead of accumulating
  retired Vulkan samplers until a pack unloads, preventing sampler exhaustion with anisotropic texture filtering.
- Copies of vanilla render pipelines made by cosmetic mods retain their shader-pack entity and shadow programs,
  preventing Essential's translucent cosmetics from falling back to unencoded vanilla output. Unmatched pipeline
  lookups are cached instead of scanning the registered pipelines on every draw.
- Polytone uses vanilla leads while a shader pack is active, matching its Iris fallback and preventing rainbow leads
  with packs that encode their material buffers. Its textured leads still work without a shader pack.
- Shader interface blocks are split into individual varyings before Minecraft builds Vulkan pipelines, preventing
  compositing and world programs in packs such as Emanrux and Eclipse from failing with a struct interface error.
- Unsupported optional shader-pack features no longer activate shader stages, including tessellation.
- Custom uniform expressions accept the GLSL `fract()` function.
- Different blend functions per colour attachment are preserved on Vulkan devices with independent blending,
  allowing Iteration's water program to build.
- Texture samplers are renamed according to local scope, including reads in a local variable's initializer;
  Iteration's water, shadow and weather shaders no longer fail when their sampled colour is also named `tex`.
- Geometry stage inputs and outputs use separate location ranges, avoiding excessive geometry output locations
  and device loss with SEUS PTGI HRR 3. Integer varyings carry matching flat interpolation in every stage.
- Empty world fragment placeholders no longer reserve a colour target, preserving all eight real world targets in
  Alpha Piscium and Bliss. Full-screen placeholders keep their primary target for safe render-pass sizing.
  Inactive Distant Horizons programs do not reserve world targets.
- Custom image declarations accept repeated whitespace and up to 32 images; Derivative's original release now
  retains all of its image bindings. Mixed-case block IDs and non-English property comments no longer abort or
  truncate pack material maps.
- Compute uniform copies respect the device's offset alignment; pack buffers support indirect dispatch and
  1D lookup textures keep their Vulkan dimension. Supported NVIDIA subgroup partition operations are enabled.
- Compatible target image formats use views of the same storage, including Alpha Piscium's RGBA8 clears of
  R32UI targets. Mutable formats are enabled only for targets a compute program accesses through a different
  compatible format. Invalid vector constants emitted by the shader optimizer trigger an unoptimized recompile.
- Full-screen shaders accept explicit position and UV inputs alongside legacy built-ins, restoring IterationRP's
  post-processing instead of leaving those inputs unbound.
- Store-only compute programs with compatible normalized image declarations use the floating-point target's
  format, preserving numeric values and preventing IterationRP's white screen.
- Integer and boolean uniform providers convert to float when a pack declares a floating-point flag.
- Custom uniform expressions support vector/scalar multiplication in either order, restoring MakeUp's TAA correction.
- Iteration's End shaders accept matrix transpose and inverse expressions in their global initializers.
- Compute shaders are included when checking which depth textures a pack reads. Opaque shadow and no-hand depth
  copies are no longer skipped for compute-only readers, restoring sunlight and light shafts in Alpha Piscium.
- Vulkan pass barriers include geometry, tessellation and early depth-test accesses, keeping shader storage and
  depth writes visible to later passes.
- Default shadow draw buffers include shadowcolor1 only when the fragment shader exposes a second output,
  retaining SEUS's voxel lighting without reserving an unused target.
- Shadow colour textures used only as compute images are allocated even without render attachments, restoring
  Rethinking Voxels' water waves.

## [0.1.1] - 2026-10-18

A patch release for Photon: mining a block shows its cracks again, and enchanted items show a proper glint.

### Fixed

- Mining a block showed no crack with Photon. The crack is now drawn after the pack's deferred passes, wherever it
  lies, and it is no longer drawn into the shadow map.
- Enchanted items and armour showed a garbled, rainbow-striped glint with packs that store packed data in their
  first output (Photon). Packs with their own glint program now draw the glint with it, over the item.

### Also fixed

- NeoForge: the game crashed at startup ("Texture does not exist") when a texture atlas ticked before it was
  first uploaded, which NeoForge does while resources are still loading.
- The shader pack screen's "Open packs folder" did nothing when the `shaderpacks` folder did not exist yet; it now
  creates the folder and opens it.
- The shader toggle key treated a pack that failed to load as on, so the next press only "turned it off"; it now
  tries the pack again. "Shaders on" now shows once the pack is drawing, not before it has loaded.
- On Macs, a pack's geometry stage that only passes each corner on is now folded away in more of the shapes packs
  write it (varyings in interface blocks, a loop counter declared ahead of the loop, the position held in a local),
  instead of the program falling back to the game's shader.

## [0.1.0] - 2026-10-06

The first release.

**Requires** Minecraft 26.3 on Fabric (with Fabric API) or NeoForge, Sodium 0.9.2 or newer, and the Vulkan graphics
API.

### Shader packs

- Standard-format shader packs run natively on Minecraft's Vulkan renderer: gbuffer, shadow, full-screen, compute
  and geometry programs, custom images and buffers, raw textures and LabPBR material maps.
- Terrain draws through Sodium's chunk renderer with the vertex data packs read, and honours pack render layers,
  separate ambient occlusion, old lighting and light-block geometry. Switching packs rebuilds the terrain without a
  restart.
- Distant Horizons support: far terrain is drawn through the pack's `dh_*` programs, with far-terrain shadows.
- Packs load in the background, with a small progress panel in the HUD corner, and a disk cache for converted
  shaders makes loading the same pack again about 44% faster.
- Render scale (50–100%) with FSR 1.0 upscaling and adjustable sharpening.
- Shadow distance and shadow map scale overrides.

### Shader pack screen

- Pack header with name, version and option counts, a sections sidebar, profile chips, search and a "changed only"
  filter.
- Options with inline Reset buttons, arrows that step either way, and details showing the current and default values.
- Apply, Discard and Done, with what is waiting to be applied shown under the sections.
- Live preview (hold Tab), import and export of pack settings, drag-and-drop install and keyboard navigation.

### Performance

- About twenty shader engine optimisations, each with its own switch, among them the shadow cache, hardware shadow
  filtering, targeted snapshots, settled bindings, load-op clears and the transform cache. Measured on Complementary
  Unbound: frame rate +4%, CPU frame time −10.5% against everything off.
- Memory: shared block shapes, shared empty item data and lightweight chunk data checks.

### HUD and quality of life

- Hold-to-zoom (Z) with scroll, adjustable least, most and starting zoom, zoomed mouse sensitivity and an optional
  cinematic camera.
- Zoom readout: block coordinates, distance and facing direction, plus a 3D preview and the name of the block or mob
  under the crosshair. It can sit in any corner, and either panel can be turned off.
- Pixel-exact centred crosshair.
- Aetherium settings screen with search, tags and presets, reachable from **Options › Aetherium...** next to
  **Shader Packs...**.
- A prompt to switch the game to Vulkan when it starts on OpenGL.

[Unreleased]: https://github.com/SpaceBod/aetherium/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/SpaceBod/aetherium/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/SpaceBod/aetherium/releases/tag/v0.1.0
