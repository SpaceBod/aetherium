# Shader packs

- [Installing a pack](#installing-a-pack)
- [The shader pack screen](#the-shader-pack-screen)
- [Pack settings files](#pack-settings-files)
- [What packs can use](#what-packs-can-use)
- [Known limits](#known-limits)

## Installing a pack

1. Open the shader pack screen with <kbd>O</kbd>, or **Options › Shader Packs...**.
2. Drop the pack's `.zip` (or its folder) onto the window. You can also copy it into `.minecraft/shaderpacks`, and
   the screen picks it up on its own.
3. Pick the pack in the library (click the pack name at the top left) and press **Apply**.

The pack loads in the background. While it loads, the world keeps its normal look and a small panel in the top-right
corner of the HUD shows the pack's name and progress. <kbd>K</kbd> turns the active pack off and on again.

Packs need the game to run on Vulkan. See [FAQ](faq.md#shader-packs-do-nothing) if nothing happens.

## The shader pack screen

![The shader pack screen with Complementary Unbound: pack header and sections on the left, options in the middle, profiles, details and the Apply buttons on the right](images/pack-screen.png)

The screen has three columns when the window is wide enough. Narrower windows fold the details and the buttons into
a strip along the bottom.

**Top bar**

- The **pack switcher** (top left) opens the pack library. It shows the selected pack and its state: active,
  loading with a percentage, failed, or waiting for Apply.
- **Search** filters every option of the pack by name, across all its screens.
- **Changed** shows only the options you have changed from the pack's defaults.
- **⋯** has Import, Export, Reset all, Reload from disk and Open packs folder.

**Left: pack and sections**

- The pack's name, its version and whether it is a zip or a folder, how many options it has and how many you have
  changed.
- The pack's own screens, as a list of sections. The section you are in is highlighted.
- At the bottom, what is waiting to be applied, or *Up to date*.

**Middle: options**

| Option | Click | Right-click | Middle-click |
|---|---|---|---|
| Toggle | Switch it | Switch it | Reset |
| Choice | Next value; the **‹** and **›** ends step either way | Previous value | Reset |
| Slider | Drag, or click to jump | One step down | Reset |
| Link to a screen | Open it | | |

An option you have changed has an accent bar on its left and a **Reset** button that returns it to the pack's
default. Holding <kbd>Shift</kbd> while scrolling over an option steps it.

**Right: profile and details**

- **Profile** chips at the top: the pack's presets. Picking one sets every option it covers. Once you change an
  option yourself, **Custom** is selected.
- **Details**: the pack's description of the option under the mouse, its current and default values, and its id.
- **Discard**, **Apply** and **Done** at the bottom. Changes only take effect when applied. Done applies and closes.

**Keyboard**

| Key | Action |
|---|---|
| <kbd>Tab</kbd> (tap), <kbd>↑</kbd> <kbd>↓</kbd> | Move between options |
| <kbd>←</kbd> <kbd>→</kbd> | Change the focused option |
| <kbd>Enter</kbd> | Toggle, or open a screen |
| <kbd>Tab</kbd> (hold) | Preview the world through the screen |
| <kbd>Esc</kbd> | Clear the search or the Changed filter, back out of a screen, then close |

## Pack settings files

Each pack's settings are saved next to it in `shaderpacks/<pack>.txt`, one `option=value` per line, in the same
format other shader loaders use. **Export** writes a dated copy you can share; **Import** (or dropping a settings
`.txt` onto the screen) loads one.

## What packs can use

Aetherium aims to run any standard-format pack as written. The supported range:

- **Programs:** every gbuffer program, shadow, all full-screen stages, compute (including `setup`, `shadow` and
  indirect dispatches), geometry stages, and the level-of-detail programs (`dh_*`).
- **Directives:** the `shaders.properties` directives that affect rendering, including `program.*.enabled`,
  `blend.*`, `alphaTest.*`, `flip.*`, `scale.*`, `texture.*`, `image.*`, `bufferObject.*`,
  `layer.*`, `separateAo`, `oldLighting`, `shadowTerrain`/`shadowEntities` and the like.
- **Uniforms:** the standard uniform set, including camera, time, weather, player state, biome and `dh*` uniforms,
  plus the pack's own custom uniforms and variables.
- **Textures:** colour and depth targets with any listed format, shadow maps (with hardware comparison), noise,
  custom and raw textures, LabPBR normal and specular maps.

Programs and features a pack declares but that Aetherium does not support are listed in the log when the pack loads.

The packs Aetherium has been tested with are listed in [Tested shader packs](tested-packs.md).

## Known limits

- Tessellation stages are not supported yet.
- A few `shaders.properties` keys are read but not applied yet: `particles.ordering`, `fallbackTex`, `backFace.*`,
  `beacon.beam.depth`, `dynamicHandLight`, `prepareBeforeShadow`. See the [Roadmap](roadmap.md).
- `setupN` vertex/fragment programs are not run (compute `setup` programs are).
- Render scale does not accumulate frames over time (no temporal upscaling).
- The game's own full-screen effects (for example the spectator mob views) pause the render scale while they are
  on.
