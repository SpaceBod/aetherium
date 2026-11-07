#!/usr/bin/env bash
# Uploads a GitHub release's jars to the CurseForge project, one file per loader, as the release was published:
# display name = the jar's file name, game versions = the Minecraft version in the jar name plus its loader,
# changelog = the release notes from their first "###" heading on (markdown), release type = release.
#
#   bash scripts/curseforge-upload.sh v0.1.2             upload
#   bash scripts/curseforge-upload.sh v0.1.2 --dry-run   download the jars and show what would be uploaded
#
# Needs: the GitHub CLI (gh) able to read the repository's releases, curl, Python 3, and CURSEFORGE_TOKEN (an API
# token from https://authors.curseforge.com/account/api-tokens). A dry run without the token skips checking the
# game version names against CurseForge. The GitHub workflow .github/workflows/curseforge.yml runs this for every
# published (non-pre-release) release.
set -euo pipefail

project_id=1730933
api=https://minecraft.curseforge.com/api

fail() { echo "curseforge: $*" >&2; exit 1; }

tag=""
dry=0
for arg in "$@"; do
	case "$arg" in
		--dry-run) dry=1 ;;
		-*) fail "unknown option: $arg" ;;
		*) [ -z "$tag" ] || fail "one tag only"; tag="$arg" ;;
	esac
done
[ -n "$tag" ] || fail "usage: curseforge-upload.sh <tag> [--dry-run]"
token="${CURSEFORGE_TOKEN:-}"
[ "$dry" = 1 ] || [ -n "$token" ] || fail "CURSEFORGE_TOKEN is not set"
py="$(command -v python3 || command -v python)" || fail "Python 3 is needed"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

gh release download "$tag" --pattern '*.jar' --dir "$work"
gh release view "$tag" --json body --jq .body > "$work/notes.md"

# The changelog as the project's files show it: the release notes from the first "###" heading on.
"$py" - "$work/notes.md" "$work/changelog.md" <<'EOF'
import sys
lines = open(sys.argv[1], encoding="utf-8").read().replace("\r\n", "\n").split("\n")
start = next((i for i, l in enumerate(lines) if l.startswith("### ")), 0)
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(lines[start:]).strip() + "\n")
EOF
[ -s "$work/changelog.md" ] || fail "$tag has no release notes"

# One upload per jar: aetherium-<version>+mc<minecraft>-<loader>.jar.
uploads=()
for jar in "$work"/*.jar; do
	name="$(basename "$jar")"
	if [[ ! "$name" =~ ^aetherium-.+\+mc([^-]+)-(fabric|neoforge)\.jar$ ]]; then
		echo "curseforge: skipping $name (not a loader jar)"
		continue
	fi
	minecraft="${BASH_REMATCH[1]}"
	case "${BASH_REMATCH[2]}" in
		fabric) loader=Fabric ;;
		neoforge) loader=NeoForge ;;
	esac
	"$py" - "$work/changelog.md" "$name" "$minecraft" "$loader" > "$work/$name.json" <<'EOF'
import json, sys
changelog, name, minecraft, loader = sys.argv[1:]
print(json.dumps({
    "changelog": open(changelog, encoding="utf-8").read(),
    "changelogType": "markdown",
    "displayName": name,
    "gameVersionNames": [minecraft, loader],
    "releaseType": "release",
}))
EOF
	uploads+=("$name")
done
[ "${#uploads[@]}" -gt 0 ] || fail "$tag has no loader jars"

# Every game version name must exist on CurseForge before anything is uploaded (a missing one would leave the
# release half uploaded).
if [ -n "$token" ]; then
	curl --silent --show-error --fail-with-body -H "X-Api-Token: $token" "$api/game/versions" > "$work/versions.json" \
		|| fail "could not read CurseForge's game versions (is the token valid?)"
	"$py" - "$work/versions.json" "$work" "${uploads[@]}" <<'EOF'
import json, sys
known = {v["name"] for v in json.load(open(sys.argv[1], encoding="utf-8"))}
missing = set()
for name in sys.argv[3:]:
    meta = json.load(open(f"{sys.argv[2]}/{name}.json", encoding="utf-8"))
    missing |= {n for n in meta["gameVersionNames"] if n not in known}
if missing:
    sys.exit("curseforge: CurseForge has no game version named " + ", ".join(sorted(missing)))
EOF
fi

echo "Project:  $project_id"
echo "Release:  $tag"
for name in "${uploads[@]}"; do
	echo "Upload:   $name  $(cat "$work/$name.json" | "$py" -c 'import json,sys; m=json.load(sys.stdin); print(m["gameVersionNames"], m["releaseType"])')"
done
echo "Changelog:"
sed 's/^/          /' "$work/changelog.md"
echo

if [ "$dry" = 1 ]; then
	echo "Dry run: nothing uploaded."
	exit 0
fi

for name in "${uploads[@]}"; do
	response="$(curl --silent --show-error --fail-with-body -H "X-Api-Token: $token" \
		-F "metadata=<$work/$name.json;type=application/json" -F "file=@$work/$name" \
		"$api/projects/$project_id/upload-file")" || fail "upload of $name failed: $response"
	echo "Uploaded: $name -> $response"
done
