#!/usr/bin/env bash
# Sets up a Fabric 26.3 instance as the reporter of issue #3 runs it (ACT flicker with Complementary Unbound +
# Euphoria Patches): their mods at their versions, their Aetherium config and their shader options.
#
#   bash repro/issue-3/fetch.sh <instance game dir> [0.1.1|main]
#
# The game dir is the folder holding mods/, config/ and shaderpacks/ (".minecraft" in most launchers). 0.1.1 (the
# default) installs the release the reporter runs; main installs fabric/build/libs from this checkout, so build it
# first with ./gradlew :fabric:build. Existing files the script replaces are moved to <game dir>/issue-3-backup/.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
game="${1:?usage: fetch.sh <instance game dir> [0.1.1|main]}"
which="${2:-0.1.1}"
backup="$game/issue-3-backup"

if command -v sha512sum >/dev/null; then
	sha512() { sha512sum "$1" | cut -d' ' -f1; }
else
	sha512() { shasum -a 512 "$1" | cut -d' ' -f1; }
fi

# Moves an existing file out of the way once, so a second run does not overwrite the first backup.
keep() {
	if [ -e "$1" ]; then
		mkdir -p "$backup/$(dirname "${1#$game/}")"
		[ -e "$backup/${1#$game/}" ] || mv "$1" "$backup/${1#$game/}"
	fi
}

fetch() {
	local dir="$1" name="$2" url="$3" sum="$4" target="$game/$1/$2"
	mkdir -p "$game/$dir"
	if [ -f "$target" ] && [ "$(sha512 "$target")" = "$sum" ]; then
		echo "have   $dir/$name"
		return
	fi
	curl -fsSL -A "aetherium-issue-3-repro" -o "$target.part" "$url"
	if [ "$(sha512 "$target.part")" != "$sum" ]; then
		rm -f "$target.part"
		echo "SHA-512 mismatch for $name, stopping" >&2
		exit 1
	fi
	mv "$target.part" "$target"
	echo "got    $dir/$name"
}

mkdir -p "$game/mods"
for old in "$game"/mods/aetherium-*.jar; do
	if [ -e "$old" ]; then
		keep "$old"
		# Only one Aetherium jar may load: drop one an earlier run already backed up.
		rm -f "$old"
	fi
done

while IFS=$'\t' read -r dir name url sum; do
	case "$dir" in
		mods|shaderpacks) fetch "$dir" "$name" "$url" "$sum" ;;
		aetherium-0.1.1) [ "$which" = "0.1.1" ] && fetch mods "$name" "$url" "$sum" ;;
	esac
done < "$here/manifest.tsv"

if [ "$which" = "main" ]; then
	# The newest build: fabric/build/libs keeps the jars of earlier versions too.
	jar="$(ls -t "$here"/../../fabric/build/libs/aetherium-*-fabric.jar 2>/dev/null | head -n 1 || true)"
	[ -n "$jar" ] || { echo "No fabric jar in fabric/build/libs: run ./gradlew :fabric:build first" >&2; exit 1; }
	# Renamed: until the version is bumped, a build of main has the release's file name.
	cp "$jar" "$game/mods/aetherium-main-fabric.jar"
	echo "copied mods/aetherium-main-fabric.jar (this checkout's $(basename "$jar"))"
elif [ "$which" != "0.1.1" ]; then
	echo "Unknown version '$which': use 0.1.1 or main" >&2
	exit 1
fi

keep "$game/config/aetherium.json"
mkdir -p "$game/config" "$game/shaderpacks"
cp "$here/config/aetherium.json" "$game/config/aetherium.json"
options="ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt"
keep "$game/shaderpacks/$options"
cp "$here/options/$options" "$game/shaderpacks/$options"
echo "copied config/aetherium.json and shaderpacks/$options"
echo "Done. Replaced files, if any, are in $backup"
