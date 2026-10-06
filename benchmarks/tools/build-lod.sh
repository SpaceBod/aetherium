#!/usr/bin/env bash
# Builds the level-of-detail mod (Distant Horizons) for 26.3 (Fabric) at a pinned commit and copies the jars into
# libs/: the mod jar (compile-only for Aetherium's compat code; run with benchmarks/run.ps1 -DistantHorizons) and its
# API jar. Run once after cloning, before the first Gradle build. The checkout lives in build/lod-src.
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
src="$root/build/lod-src"
url="https://gitlab.com/distant-horizons-team/distant-horizons.git"
rev="7fc66709300a84ae868331d310087d200d5c9e71" # main, 2026-09-29: mod 3.3.5-dev, API 7.2.0

if [ ! -d "$src/.git" ]; then
	git init -q "$src"
	git -C "$src" config core.longpaths true
	git -C "$src" remote add origin "$url"
fi
if [ "$(git -C "$src" rev-parse -q --verify HEAD 2>/dev/null)" != "$rev" ]; then
	git -C "$src" fetch -q --depth 1 origin "$rev"
	git -C "$src" checkout -q --force FETCH_HEAD
fi
git -C "$src" submodule update -q --init --recursive

case "${OSTYPE:-}" in
	msys*|cygwin*|win32*) gradlew=./gradlew.bat; default_jdk="/c/Program Files/Java/jdk-25.0.4.1" ;;
	darwin*) gradlew=./gradlew; default_jdk="$(/usr/libexec/java_home -v 25 2>/dev/null || true)" ;;
	*) gradlew=./gradlew; default_jdk="" ;;
esac
export JAVA_HOME="${JAVA_HOME:-$default_jdk}"
(cd "$src" && "$gradlew" :fabric:assemble -PmcVer=26.3.0 --console=plain -q)

mkdir -p "$root/libs"
rm -f "$root"/libs/DistantHorizons*.jar
cp "$src"/fabric/build/libs/DistantHorizons-fabric-*-26.3.jar "$root/libs/"
cp "$src"/coreSubProjects/api/build/libs/DistantHorizonsApi-*.jar "$root/libs/"
ls "$root/libs"
