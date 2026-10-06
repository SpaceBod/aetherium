#!/usr/bin/env bash
# Publishes a GitHub release of the version in gradle.properties: builds both loader jars, takes the release notes from
# that version's section of CHANGELOG.md, and creates the release (and its tag, v<version>) with the jars attached.
#
#   bash scripts/release.sh             build and publish
#   bash scripts/release.sh --draft     publish as a draft, to review on GitHub before it goes out
#   bash scripts/release.sh --dry-run   build and show what would be published, without publishing
#
# Needs: Git, the GitHub CLI (gh) signed in to an account that can push to the repository, and JDK 25 for the build.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

draft=""
dry=0
for arg in "$@"; do
	case "$arg" in
		--draft) draft="--draft" ;;
		--dry-run) dry=1 ;;
		*) echo "unknown option: $arg" >&2; exit 2 ;;
	esac
done

fail() { echo "release: $*" >&2; exit 1; }

version="$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')"
[ -n "$version" ] || fail "no mod_version in gradle.properties"
tag="v$version"
remote_branch="$(git rev-parse --abbrev-ref '@{upstream}' 2>/dev/null)" || fail "the current branch has no upstream"

# The release must be exactly what is on GitHub: committed, pushed, and not released before.
[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || fail "switch to main first"
[ -z "$(git status --porcelain)" ] || fail "commit or stash your changes first"
git fetch --quiet --tags origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse "$remote_branch")" ] || fail "main differs from $remote_branch: push or pull first"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || gh release view "$tag" >/dev/null 2>&1; then
	fail "$tag already exists: bump mod_version in gradle.properties"
fi

# Release notes: the version's section of the changelog, without its heading.
notes="$(mktemp)"
trap 'rm -f "$notes"' EXIT
awk -v v="$version" '
	$0 ~ "^## \\[" v "\\]" { on = 1; next }
	on && /^## \[/ { exit }
	on && /^\[[^]]+\]: / { exit }
	on { print }
' CHANGELOG.md | sed -e '/./,$!d' > "$notes"
[ -s "$notes" ] || fail "CHANGELOG.md has no section for [$version]"

echo "Building $version ..."
./gradlew --quiet :fabric:build :neoforge:build
jars=()
for loader in fabric neoforge; do
	jar="$(ls "$loader"/build/libs/aetherium-"$version"+*-"$loader".jar 2>/dev/null | head -n 1)"
	[ -n "$jar" ] || fail "no $loader jar for $version in $loader/build/libs"
	jars+=("$jar")
done

echo
echo "Release:  Aetherium $version ($tag${draft:+, draft}) on $(git rev-parse --short HEAD)"
for jar in "${jars[@]}"; do echo "Jar:      $jar ($(du -k "$jar" | cut -f1) KB)"; done
echo "Notes:"
sed 's/^/          /' "$notes"
echo

if [ "$dry" = 1 ]; then
	echo "Dry run: nothing published."
	exit 0
fi

gh release create "$tag" "${jars[@]}" --target "$(git rev-parse HEAD)" --title "Aetherium $version" --notes-file "$notes" $draft
git fetch --quiet --tags origin
echo "Published: $(gh release view "$tag" --json url --jq .url)"
