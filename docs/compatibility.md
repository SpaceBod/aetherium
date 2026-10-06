# Compatibility

- [Platforms](#platforms)
- [Mods](#mods)
- [Shader packs](#shader-packs)

## Platforms

| Platform | Status |
|---|---|
| Windows, Vulkan | Supported; the main development platform. |
| Linux, Vulkan | <!-- TODO: test and fill in. --> Expected to work. |
| macOS (Apple silicon), Vulkan through MoltenVK | Supported with differences. See [macOS](macos.md). |
| OpenGL (any OS) | Not supported for shader packs. Aetherium offers to switch the game to Vulkan. |

| GPU vendor | Status |
|---|---|
| NVIDIA | <!-- TODO --> Tested on an RTX 3080. |
| AMD | <!-- TODO: test and fill in. --> |
| Intel | <!-- TODO: test and fill in. --> |
| Apple | Through MoltenVK; see [macOS](macos.md). |

## Mods

| Mod | Status |
|---|---|
| [Sodium](https://modrinth.com/mod/sodium) 0.9.2+ | **Required.** Aetherium draws shader-pack terrain through Sodium's chunk renderer. |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) 3.3.4 | Compatible. Packs with `dh_*` programs shade its far terrain; others hide it. Without a pack it draws as usual. |
| Other shader-pack mods | **Incompatible.** Only one mod can run shader packs; the mod metadata refuses to load with them. |
| <!-- TODO: other mods tested together, e.g. performance and HUD mods --> | |

<!-- TODO: known conflicts and their workarounds. -->

## Shader packs

Aetherium has been tested with 22 shader packs, among them Complementary, BSL, Photon and Sildur's; every one of them
compiles completely. The full list, with versions and results, is in [Tested shader packs](tested-packs.md).
