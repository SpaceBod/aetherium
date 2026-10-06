#!/usr/bin/env bash
# Downloads the reference shader packs (BSL, Complementary Reimagined/Unbound, Photon) from Modrinth into
# fabric/run/shaderpacks, where the dev client and benchmarks/run.sh -ShaderPack look for them. Already-present
# files are kept. Prints an AETHERIUM_PACKS line for the game-free compile check.
#
#   bash benchmarks/tools/fetch-packs.sh            # all reference packs, latest version
#   bash benchmarks/tools/fetch-packs.sh bsl photon # a subset (bsl, complementary, unbound, photon)
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
dest="$root/fabric/run/shaderpacks"
mkdir -p "$dest"

slug_for() {
	case "$1" in
		bsl) echo bsl-shaders ;;
		complementary) echo complementary-reimagined ;;
		unbound) echo complementary-unbound ;;
		photon) echo photon-shader ;;
		*) echo "unknown pack '$1' (bsl, complementary, unbound, photon)" >&2; return 1 ;;
	esac
}

[ $# -gt 0 ] || set -- bsl complementary unbound photon
packs=""
for name in "$@"; do
	slug="$(slug_for "$name")"
	# Newest version's primary file: "<filename> <url>".
	line="$(curl -fsSL "https://api.modrinth.com/v2/project/$slug/version" | python3 -c '
import json, sys
v = json.load(sys.stdin)[0]
f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
print(f["filename"], f["url"])
')"
	file="${line%% *}"
	url="${line#* }"
	if [ -f "$dest/$file" ]; then
		echo "have  $file"
	else
		echo "fetch $file"
		curl -fsSL -o "$dest/$file.part" "$url"
		mv "$dest/$file.part" "$dest/$file"
	fi
	packs="${packs:+$packs;}$dest/$file"
done

echo
echo "Compile check:"
echo "  AETHERIUM_PACKS=\"$packs\" ./gradlew :common:microbench -Ponly=shaders"
