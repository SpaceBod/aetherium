# Roadmap

Planned and open work, roughly in order of value within each area. Effort: **S** about a day, **M** a few days,
**L** a week or more.

<!-- TODO: decide priorities for the first release and mark them here. -->

## Shader packs

| Item | Effort |
|---|---|
| Visual verdict for every pack in the [tested packs list](tested-packs.md), and more packs in the corpus | S per pack |
| Particle ordering (`particles.ordering`, `particles.before.deferred`): parsed, not applied yet | M |
| `setupN` vertex/fragment programs; routing for `gbuffers_item`, `entities_glowing` and `shadow_solid` where packs use them | S–M |
| Tessellation stages, once a pack needs them | L |
| More than 8 gbuffer attachments across programs; `size.buffer` for targets world passes draw into | M |
| `fallbackTex`, `backFace.*`, `beacon.beam.depth`, `dynamicHandLight`, `prepareBeforeShadow`: parsed, not applied yet | S each |
| Colour-space conversion (`currentColorSpace`) | S–M |

## Frame rate

| Item | Effort |
|---|---|
| Re-measure every shader engine optimisation on the current terrain path | S |
| Automatic render scale: lower it when fps drops below a target, raise it when there is headroom | M |
| Temporal accumulation on top of the render scale | L |
| One shader compiler per thread during pack loads | S |
| Shadow-caster cost with many entities: cap count or distance if it costs | S |
| Custom uniforms honour their update frequency (per tick, once) instead of updating every frame | S |

## Features

| Item | Effort |
|---|---|
| Frame-time overlay: frame time and per-stage GPU time in the HUD corner style | S–M |
| High-resolution screenshots through the render scale path | M |
| Chunk fade-in for new terrain | S |
| Quick switching between favourite packs | S |
| Translations beyond English | S each |

## Engineering

| Item | Effort |
|---|---|
| Shader transform cache misses about 30% of programs on every load (37 of 125 with Complementary Unbound): find the input that changes between launches | S |
| JUnit tests in `check`: parity, lowering fixtures and parser tests (today they are harness tasks) | S–M |
| `-Xlint` warnings, then `-Werror` outside vendored code and mixins | S |
| Key the pipeline cache on the build identity as well as the driver and device | S |
| One config service for `aetherium.json` with a `version` key; one logger per area | S |
| Pass harness-only settings as parameters instead of static hooks in shipped classes | S |
| Ask Distant Horizons for public API in place of the three internals the bridge uses | S |
