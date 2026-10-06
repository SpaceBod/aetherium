# Features

Everything Aetherium does, grouped by area. Settings for each feature are described in [Settings](settings.md).

- [Shader packs](#shader-packs)
- [Shader pack screen](#shader-pack-screen)
- [Shader engine optimisations](#shader-engine-optimisations)
- [Render scale](#render-scale)
- [Shadows](#shadows)
- [Distant Horizons](#distant-horizons)
- [Memory optimisations](#memory-optimisations)
- [Zoom and HUD](#zoom-and-hud)
- [Settings and quality of life](#settings-and-quality-of-life)

---

## Shader packs

Aetherium runs standard-format shader packs, the ones with a `shaders.properties` file, on the game's Vulkan
renderer. Each program is read, preprocessed, converted to modern GLSL on a syntax tree, and compiled to SPIR-V with
the game's own shader compiler.

**Programs**

- All the gbuffer programs, including beacon beams, lightning, the End portal, leashes, the world border and the
  enchantment glint.
- Shadow programs, with the shadow map drawn every frame or reused from the [shadow cache](#shadows).
- Every full-screen stage (`prepare`, `deferred`, `composite`, `final` and the shadow composites), including the
  `shadowcomp` fragment stage.
- Compute programs, including `setup`, `shadow` and indirect dispatches.
- Geometry stages.
- Distant Horizons terrain programs (`dh_terrain`, `dh_water`, `dh_generic`, `dh_shadow`). See
  [below](#distant-horizons).

**Resources**

- Custom colour targets with their formats, sizes, clear colours and mipmaps.
- Custom images and storage buffers, including storage images written from gbuffer and shadow programs.
- Raw textures (1D, 2D, 3D and rectangle, all pixel types) and the pack's own texture files.
- LabPBR material maps (`_n` and `_s`), bound alongside every block, item and entity texture.
- Hardware shadow comparison for `shadow2D` lookups, with the shader fallback where it is unavailable.

**Pack options**

- Boolean, choice and slider options, screens and sub-screens, profiles and option comments, all from the pack's
  own language files.
- Custom uniforms and variables (`uniform.*`, `variable.*`), evaluated every frame.
- Per-pack settings saved next to the pack as `<pack>.txt`, in the same format other loaders use, so settings files
  can be shared.

**Terrain**

- Terrain draws through Sodium's chunk renderer with an extended vertex format. It carries the normal, block
  id (`mc_Entity`), mid-texture coordinate, tangent and block-relative position that packs read.
- Pack directives that change terrain are applied: render layers (`layer.*`), separate ambient occlusion, old
  lighting, light-block geometry and face culling.
- Switching to or from a pack rebuilds the chunk meshes in the right format without a restart.

**Loading**

- Packs load in the background, so switching does not freeze the game. The world keeps its normal look until the
  pack is ready.
- A small panel in the top-right corner shows the pack's name, a spinner and the percentage while it loads:

  <img src="images/loading-indicator.png" alt="The loading indicator" width="400">

- A disk cache keeps each program's converted code, so loading the same pack again takes about half the time.

## Shader pack screen

Open it with <kbd>O</kbd>, or **Options › Shader Packs...**. More in [Shader packs](shader-packs.md#the-shader-pack-screen).

- **Pack library.** Every pack in `shaderpacks`, with a live folder watch. Drop zips or folders onto the window to
  install them.
- **Pack header.** The pack's name, version and kind (zip or folder), how many options it has and how many you have
  changed.
- **Sections.** The pack's own option screens as a sidebar, with the current one highlighted.
- **Profiles.** The pack's presets as chips at the top of the details column. Changing any option shows **Custom**.
- **Options.** Toggles, choices with arrows on both ends, and sliders you can drag. Changed options show a marker
  and a **Reset** button.
- **Search and filters.** Search every option by name, or show only the options you have changed.
- **Details.** The pack's own description of the option under the mouse, its current and default value, and its id.
- **Apply, Discard, Done.** Nothing changes until you apply. The panel on the left shows what is waiting.
- **Preview.** Hold <kbd>Tab</kbd> to look at the world through the screen.
- **Import and export** of a pack's settings, **Reset all**, **Reload from disk**, and a shortcut to the packs folder.
- **Keyboard navigation.** <kbd>Tab</kbd> or the arrow keys move, <kbd>←</kbd> <kbd>→</kbd> change, and <kbd>Enter</kbd> toggles or
  opens.

## Shader engine optimisations

About twenty optimisations of the shader engine. Each one has an id and can be turned off under
**Aetherium › Performance**. Each was measured against an all-off baseline before it was turned on by default. They
range from skipping copies and clears a pack never reads to caching the shadow map and the converted shader code.
The full list, with what each one does and what it measured, is in [Performance](performance.md).

## Render scale

**Aetherium › Shaders › Render scale** draws the world at 50–100% of the window while a pack runs, then upscales it
with FSR 1.0 (edge-adaptive upscaling, then contrast-adaptive sharpening). The interface is drawn afterwards at full
resolution, so text stays sharp. **Upscale sharpness** sets the strength of the sharpening.

## Shadows

- **Shadow distance** and **Shadow map scale** override the pack's own values. They change the pack's settings
  before its shaders are built, so its filtering stays matched.
- **Shadow cache.** The terrain part of the shadow map is kept between frames. It is redrawn only when the sun
  moves, you move to another spot or a chunk changes. Mobs and players still cast shadows every frame.
- Shadow casters are culled against the light's view, not the camera's, so off-screen terrain and mobs still cast
  shadows into view.

## Distant Horizons

Aetherium works with [Distant Horizons](https://modrinth.com/mod/distanthorizons), so the view can reach far past the
render distance:

- **With a shader pack** that has Distant Horizons programs (`dh_terrain`, `dh_water`, ...), the far terrain is drawn
  through them, so it gets the pack's lighting, water and fog. The pack is given the depth textures, matrices and
  uniforms those programs read.
- **Shadows** from far terrain are drawn through the pack's `dh_shadow` program when it has one.
- **A pack without them** hides the far terrain rather than showing it unshaded.
- **Without a shader pack**, Distant Horizons draws as it normally does, on Vulkan.

Tested with Distant Horizons 3.3.4 for Minecraft 26.3.

<p>
<img src="images/showcase-lod.jpg" alt="Far terrain from Distant Horizons under a night sky" width="49%">
<img src="images/showcase-lod-2.jpg" alt="Far terrain from Distant Horizons at sunset, reaching to distant mountains" width="49%">
</p>

## Memory optimisations

| Optimisation | What it saves |
|---|---|
| Shared block shapes | Block states share identical collision shapes and face data; about 6,000 shape objects become about 330. |
| Shared empty item data | Item data that has been emptied again shares one empty table instead of keeping its own copy. |
| Lightweight chunk data checks | Chunk storage checks for cross-thread misuse with one number instead of a lock object per section, about 13 MB at 16 chunks render distance. |

## Zoom and HUD

Details in [Zoom and HUD](hud.md).

<img src="images/zoom-showcase.jpg" alt="Zooming in on a poppy 370 blocks away" width="100%">

- **Zoom.** Hold <kbd>Z</kbd> to zoom and scroll to change how far. Every zoom starts at the same starting
  magnification. The least zoom, most zoom and starting zoom can all be set.
- **Zoomed sensitivity.** The mouse turns slower in step with the zoom, so aiming feels the same at any
  magnification. The cinematic camera can be turned on or off for zooming.
- **Readout.** While zoomed, two small glass panels show the block coordinates, the distance, the direction you face,
  and a 3D preview and name of the block or mob under the crosshair. Mobs take priority over blocks. The panels can be
  placed in any corner, and each can be turned off.
- **Centred crosshair.** The crosshair sits on the exact centre of the window, where the camera points, instead of
  up to a few pixels left of and above it.

## Settings and quality of life

- **Aetherium settings** under **Options › Aetherium...**: every optimisation, shader engine setting and HUD option,
  with search, tags (what each one saves, whether it needs a restart) and presets.
- **Vulkan check.** If the game starts on OpenGL, Aetherium offers to switch the Graphics API to Vulkan and quit. If
  Vulkan is already chosen but could not start, it says so.
- **Debug screen (F3).** Under the system information: the active pack, its programs and its shadow map.
- **Optimisation profiles.** *Recommended* turns on everything measured as a gain; *Baseline* turns everything off
  for comparison.

