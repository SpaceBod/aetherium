![Aetherium](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/banner.jpg)

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge)
![Requires Sodium 0.9.2+](https://img.shields.io/badge/Requires-Sodium%200.9.2%2B-35B1A1?style=for-the-badge&logo=modrinth&logoColor=white)
![Vulkan required](https://img.shields.io/badge/Vulkan-required-A41E22?style=for-the-badge&logo=vulkan&logoColor=white)

**Shader packs on Minecraft's Vulkan renderer.** Aetherium runs standard shader packs natively on Vulkan, with its
own shader-engine optimisations, a searchable settings screen and a few quality-of-life extras. Available for Fabric
and NeoForge.

![A cathedral and gatehouse at night](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/showcase-1.jpg)

![A palace with a glowing blossom tree at dusk](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/showcase-2.jpg)

![A city of stone towers in daylight](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/showcase-3.jpg)

![A lush cave lit by glow berries](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/showcase-4.jpg)

*Shot with Complementary Unbound and Spooklementary.*

![Northern lights over far terrain from Distant Horizons](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/showcase-lod.jpg)

![Sunset over a lake with far terrain from Distant Horizons](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/showcase-lod-2.jpg)

*Far terrain from Distant Horizons, shaded by the pack.*

## Why Aetherium

Minecraft is moving to Vulkan. The game now ships its own Vulkan renderer alongside OpenGL, and that is where its
rendering is heading. Shader packs, though, grew up on OpenGL: OptiFine created the format, and Iris carried it to
modern versions, both built around the OpenGL pipeline. Aetherium runs those same packs natively on the Vulkan
renderer, so they move forward with the game.

At the same time, the optimisations Sodium pioneered are finding their way into vanilla bit by bit. Aetherium leaves
terrain rendering to Sodium and builds on it, and puts its own effort where nothing else does: a fast shader engine
on Vulkan, with every optimisation measured before it is turned on.

## Highlights

- **Shader packs on Vulkan.** Standard-format packs run on the game's own Vulkan renderer: gbuffer, shadow,
  full-screen, compute and geometry programs, custom images and buffers and LabPBR material maps. Tested with
  [22 popular packs](https://github.com/SpaceBod/aetherium/blob/main/docs/tested-packs.md), among them
  Complementary, BSL, Photon and Sildur's.
- **Works with Distant Horizons.** Its far terrain is shaded by the pack's own Distant Horizons programs, shadows
  included. Without a pack it draws as usual.
- **Built for frame rate.** About twenty measured engine optimisations, each of which can be switched off on its own.
- **Render scale with FSR 1.0.** Draw the world at 50–100% of the window and upscale it, while the interface stays
  sharp.
- **A pack screen that is easy to use.** Profiles, search, a "changed only" filter, inline reset, live preview (hold
  Tab), import and export, and drag-and-drop install.
- **Fast pack loading.** Packs load in the background with a small progress indicator, and a disk cache makes
  loading the same pack again much faster.
- **Extras.** Hold-to-zoom with a coordinates and target readout, a pixel-exact centred crosshair, and a prompt that
  offers to switch the game to Vulkan.

![Zooming in on a poppy 370 blocks away](https://raw.githubusercontent.com/SpaceBod/aetherium/main/docs/images/zoom-showcase.jpg)

## Requirements

- Minecraft 26.3
- Fabric Loader 0.19.5+ with Fabric API, or NeoForge 26.3.0.23-beta+
- [Sodium](https://modrinth.com/mod/sodium) 0.9.2 or newer
- The Vulkan graphics API. If the game starts on OpenGL, Aetherium offers to switch it.

Aetherium does not work alongside other shader-pack mods.

## Getting started

1. Put Aetherium and Sodium (plus Fabric API on Fabric) into your `mods` folder and start the game.
2. Put shader packs into `shaderpacks`, or drop them onto the shader pack screen.
3. Press **O** to open the shader pack screen, pick a pack and press **Apply**. **K** turns it on and off.

Aetherium's own settings are under **Options › Aetherium...**, next to **Shader Packs...**. Hold **Z** to zoom.

## Links

- [Documentation](https://github.com/SpaceBod/aetherium/tree/main/docs)
- [Tested shader packs](https://github.com/SpaceBod/aetherium/blob/main/docs/tested-packs.md)
- [FAQ and troubleshooting](https://github.com/SpaceBod/aetherium/blob/main/docs/faq.md)
- [Report a problem](https://github.com/SpaceBod/aetherium/issues/new/choose)

## Credits

With thanks to [OptiFine](https://optifine.net), which created the shader pack format;
[Iris](https://github.com/IrisShaders/Iris), the inspiration for bringing shader packs to modern Minecraft;
[FerriteCore](https://github.com/malte0811/FerriteCore), the inspiration for the memory optimisations; and
[VulkanMod](https://github.com/xCollateral/VulkanMod) and Beryl, which showed what a Vulkan renderer for Minecraft
can do.

Aetherium is free software under the LGPL-3.0.
