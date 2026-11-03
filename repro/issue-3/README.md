# Issue #3 repro: ACT flicker with Complementary Unbound + Euphoria Patches

The reporter of [#3](https://github.com/SpaceBod/aetherium/issues/3) sees glowing blocks flicker when Advanced Color
Tracing (ACT) is on. This folder recreates their setup on another machine so the flicker can be looked for there.

## What it installs

- Their mods at their versions: Fabric API 0.165.0, Sodium 0.9.3-beta.1, Euphoria Patcher 1.10.5, BadOptimizations,
  Cloth Config, EntityCulling, Faster Shadow Mapper, FerriteCore, ImmediatelyFast, Lithium, Lomka, More Culling,
  Optimized Block Entities, Pureformance and Tachyo Optimizer. ModernFix 5.27.20-build.2 is left out: there is no public
  26.3 Fabric build, and the reporter still saw the flicker with it disabled.
- Complementary Unbound r5.9.3. Euphoria Patcher builds "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5" from it
  on the first launch.
- Their `config/aetherium.json` and their shader options file, which turns ACT on (`COLORED_LIGHTING=128`).
- Aetherium: the 0.1.1 release they run, or this checkout's build.

Every download is checked against the SHA-512 in `manifest.tsv`.

## Steps

1. In your launcher, create a Minecraft 26.3 instance with Fabric Loader 0.19.5 and no mods.
2. From the repository root, in Bash (Git Bash on Windows):

   ```bash
   bash repro/issue-3/fetch.sh "<the instance's game dir>"
   ```

   The game dir is the folder that holds `mods/`, `config/` and `shaderpacks/` (`.minecraft` in most launchers).
   Anything the script replaces is moved to `issue-3-backup/` in that folder.
3. Launch the game once so Euphoria Patcher builds the patched pack, then load a world. The pack and its options are
   already selected.
4. To try current `main` instead of 0.1.1, build it and run the script again with `main`:

   ```bash
   ./gradlew :fabric:build
   bash repro/issue-3/fetch.sh "<the instance's game dir>" main
   ```

## What to look for

The flicker shows on blocks that get their glow only from ACT: exposed ores, bamboo, and Nether and End portals.
Torches, glowstone and sea lanterns are lit by vanilla light too, which hides it. Look at night or underground, and
keep moving: the reporter sees it most while walking, and in the End it mostly stops when standing still.

Note which Aetherium version you ran (0.1.1 or main) and your GPU, driver and OS.
