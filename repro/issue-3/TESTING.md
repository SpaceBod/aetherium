# Helping us find the ACT flicker (issue #3)

Thanks for sticking with this! Here's where we are, and what this build is for.

**What we know so far.** We can't make it flicker on a Mac, or on Windows with an RTX 3080, using your exact mods,
shader settings and Aetherium config. Your setup has one big difference: an **RTX 50-series (Blackwell) card** on Linux
with the 615 driver. Blackwell is a new GPU generation, and its drivers handle some graphics work differently from
older cards. Other games have hit flicker that only happens on RTX 50 cards with the 615 drivers, and some players fixed
it by going back to an older driver. That might help you too, but it wouldn't explain why another Vulkan shader
renderer works fine on your machine. The two are built in a similar way, but Aetherium adds several layers of
performance optimisations on top, and any one of them could be what trips up your card. So we need to find out
**which part** goes wrong on your machine.

**What's in this build.** It's 0.1.1 plus the fixes merged since then: several Vulkan correctness fixes from HighDelay,
and a fix that makes start-of-frame programs see the current frame's values. Those may or may not help with this
problem. On top of that it has a **diagnostics feature** built for this issue:

- a **recording** that writes down what every frame does, including the coloured-lighting data on your graphics card,
  so we can see where it changes when it shouldn't;
- a **Diagnostics page** with every suspect in one place, including each of Aetherium's optimisations, so you can
  switch them off one by one and see whether the flicker stops.

It looks and plays the same as 0.1.1. The steps below take about 15 minutes.

You'll need your usual instance, with Complementary Unbound + Euphoria Patches and Advanced Color Tracing (ACT) on,
exactly as when you see the flicker.

## 1. Install the test build

1. Download **[aetherium-0.1.1-diag.1+mc26.3-fabric.jar](https://github.com/SpaceBod/aetherium/releases/download/v0.1.1-diag.1/aetherium-0.1.1-diag.1%2Bmc26.3-fabric.jar)**
   (from the [0.1.1-diag.1 pre-release](https://github.com/SpaceBod/aetherium/releases/tag/v0.1.1-diag.1)).
2. Open your instance's `mods` folder (in Prism Launcher: right-click the instance → **Folder**, then open `mods`).
3. **Move your current Aetherium jar out of that folder** (put it on your desktop, so you can put it back later).
   There must be only one Aetherium jar in `mods`.
4. Copy the test jar into `mods`. Leave every other mod as it is.

## 2. Find a spot that flickers

1. Start the game and load your world.
2. Go somewhere dark where you can see the flicker clearly: **glowing ores in a cave, or at night**. Stand about
   3–5 blocks from them.
3. Keep looking at those flickering blocks for every test below. That's how you'll tell us whether a test made
   the flicker stop.

## 3. Make a recording (the most important step)

1. Open chat and type **`/aetherium record`**, then press Enter.
2. You have **3 seconds**, then recording starts. **Stand still for 2–3 seconds, then walk slowly a few blocks** while
   watching the ores. The recording lasts about 5–10 seconds.
3. When it's done, chat says **"saved …"**. That file is what we need.

## 4. Try each switch

1. Open chat and type **`/aetherium diagnostics`**. Aetherium's settings open on the **Diagnostics** page.
2. Try the switches below **one at a time**. Change one, press **Apply**, then **Done**. Watch the ores for about 10
   seconds, and write down whether the flicker **stopped**, got **better**, or stayed **the same**. Then put that switch
   back before trying the next one.
3. If a switch **stops** the flicker, please make another recording (`/aetherium record`) while that switch is on.

| # | Switch (on the Diagnostics page) | Set it to | Flicker stopped / better / same? |
|---|-----------------------------------|-----------|----------------------------------|
| 1 | Tests › **Wait for the GPU every frame** | On | |
| 2 | Shader pack › **Pack: anti-aliasing (TAA_DEFINE)** | -1 | |
| 3 | Shader pack › **Render scale** | Off (all the way right) | |
| 4 | Tests › **VSync** | the opposite of what it is now | |
| 5 | Tests › **Frame rate limit** | 60 fps | |
| 6 | Optimisations › **All optimisations off** → press **Stage**, then **Apply** | (restart the game if it asks) | |

Switch 1 makes the game much slower. That's expected.

To undo switch 6: on the **Performance** page, press **Use** next to **Recommended**, then **Apply**. When you've finished
testing, you can also just put your old Aetherium jar back.

## 5. Send us the results

Please attach these to the GitHub issue:

- The recording files from the **`aetherium-diagnostics`** folder in your instance's game folder (the page's
  **Diagnostics folder › Open** button opens it).
- Your **`logs/latest.log`**.
- The table above, filled in.

## Optional: Vulkan validation (only if you're comfortable with it)

This makes the game check every graphics command against the Vulkan rules and write any mistakes to `latest.log`.

1. Install the validation layers. On CachyOS / Arch: `sudo pacman -S vulkan-validation-layers`
2. On the Diagnostics page, turn on **Vulkan validation (next launch)**, press **Apply**, and restart the game.
3. Go to the flickering spot, wait about 30 seconds (the game is slow with this on), then quit and send
   `logs/latest.log`.
4. Turn the switch off again afterwards.

## Optional: an older NVIDIA driver

If you're able to try driver **610.57** or **595**, please tell us whether the flicker is still there with it. Either
answer helps: if an older driver fixes it, we know to look at what the 615 driver changed, and at which part of
Aetherium runs into it.

Thank you!
