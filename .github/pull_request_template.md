<!--
Thanks for opening a PR! A few notes before you start:

- One focused change per PR is much easier to review (and to merge quickly) than a bundle. If you have several
  fixes, a PR each is great.
- Open it as a draft while you're still testing, and mark it ready once you'd be happy for it to go in as it is.
- Anything in these comments won't show up on the PR, so feel free to leave them.
-->

## What this changes

<!--
What was wrong (what a player would see) and what this does about it. Describe the feature the code handles rather
than the pack or mod that hit it: "a compute program that clears an R32UI target through an RGBA8 view" rather
than "fixes <pack>". Pack names are welcome in the testing section and the CHANGELOG.
-->

## How it was tested

<!-- Tick what applies, and delete what doesn't. -->

- [ ] `./gradlew :fabric:build :neoforge:build`
- [ ] Fixtures: <!-- e.g. `./gradlew :common:microbench -Ponly=lowering`, plus any new check this PR adds -->
- [ ] Added a fixture or parity test for this change
- [ ] Shader pack compile check: <!-- `AETHERIUM_PACKS=... ./gradlew :common:microbench -Ponly=shaders` -->
- [ ] Checked in game

**Setup:** <!-- OS, GPU and driver, loader, Minecraft version -->

**Packs checked:** <!-- names and versions, which dimensions, and a shader-off or pack-absent control if relevant -->

## Performance

<!--
Only if this touches something that runs every frame or every draw: before/after frame times from a few repeats
with everything else held the same (`benchmarks/run.ps1` or `benchmarks/run.py`). Otherwise, delete this section.
-->

## Checklist

- [ ] Rebased on `main`
- [ ] `CHANGELOG.md` has an entry under **Unreleased** (if a player would notice the change)
- [ ] Comments describe what the code does and why, without naming other packs or mods
- [ ] No inline fully qualified class names, and braces on every `if`
- [ ] Other mods' classes are only imported from classes that load when that mod is present

<!-- If an AI tool wrote some or all of this, a quick note saying which is appreciated. -->
