# Changelog

All notable changes to Aetherium are listed here. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

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

[Unreleased]: https://github.com/SpaceBod/aetherium/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/SpaceBod/aetherium/releases/tag/v0.1.0
