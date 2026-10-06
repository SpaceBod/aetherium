#!/usr/bin/env bash
# One-shot macOS (Apple silicon) development setup for Aetherium on Minecraft 26.3. Safe to re-run: every step skips
# what is already there.
#
#   bash benchmarks/tools/setup-macos.sh            # toolchain check, LOD jars, build, reference packs, compile check
#   bash benchmarks/tools/setup-macos.sh --vk-debug # also Homebrew's Vulkan loader, MoltenVK and validation layers
#
# The game needs nothing Vulkan-related installed: Minecraft 26.3 ships MoltenVK in its LWJGL natives
# (lwjgl-vulkan natives-macos-arm64) and enables VK_KHR_portability_enumeration on macOS itself. The Homebrew Vulkan
# packages are only for benchmarks/run.py -VkDebug (validation layers) and vulkaninfo.
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

vk_debug=0
for arg in "$@"; do
	case "$arg" in
		--vk-debug) vk_debug=1 ;;
		*) echo "unknown option $arg" >&2; exit 2 ;;
	esac
done

step() { printf '\n\033[36m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

step "Machine"
[ "$(uname -s)" = Darwin ] || fail "This script is for macOS."
[ "$(uname -m)" = arm64 ] || echo "Not Apple silicon: Intel Macs run MoltenVK too, but are untested here."
echo "$(sysctl -n machdep.cpu.brand_string), $(( $(sysctl -n hw.memsize) / 1073741824 )) GB, macOS $(sw_vers -productVersion)"

step "JDK 25"
jdk="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
if [ -z "$jdk" ]; then
	command -v brew >/dev/null || fail "No JDK 25 and no Homebrew. Install a JDK 25 (e.g. Azul Zulu 25 for arm64) and re-run."
	brew install --cask zulu@25
	jdk="$(/usr/libexec/java_home -v 25)"
fi
echo "$jdk"
export JAVA_HOME="$jdk"

step "Tools"
command -v git >/dev/null || fail "git missing: xcode-select --install"
command -v python3 >/dev/null || fail "python3 missing: brew install python"
command -v curl >/dev/null || fail "curl missing"
echo "git, python3 ($(python3 --version 2>&1)), curl"

if [ "$vk_debug" = 1 ]; then
	step "Vulkan debugging tools (Homebrew)"
	command -v brew >/dev/null || fail "Homebrew is needed for --vk-debug: https://brew.sh"
	brew install vulkan-loader molten-vk vulkan-validationlayers vulkan-tools
	vulkaninfo --summary 2>/dev/null | sed -n '/Devices:/,$p' | head -20 || true
fi

step "Executable bits (files committed from Windows)"
chmod +x gradlew benchmarks/run.py benchmarks/tools/*.sh

step "Level-of-detail jars (libs/)"
if ls libs/DistantHorizons-fabric-*.jar >/dev/null 2>&1 && ls libs/DistantHorizonsApi-*.jar >/dev/null 2>&1; then
	ls libs
else
	bash benchmarks/tools/build-lod.sh
fi

step "Build (first run downloads and decompiles Minecraft 26.3: a few minutes)"
./gradlew :fabric:build :fabric:devClasses --console=plain -q

step "Reference shader packs"
bash benchmarks/tools/fetch-packs.sh | sed '/^$/,$d'

step "Shader compile check (SPIR-V, plus the Metal translation MoltenVK does)"
packs=""
for z in fabric/run/shaderpacks/*.zip; do packs="${packs:+$packs;}$root/$z"; done
AETHERIUM_PACKS="$packs" ./gradlew :common:microbench -Ponly=shaders --console=plain -q 2>&1 | grep -E 'compiled to|Metal|failing|^ +[0-9]+x' || true
echo "(dh_* programs only build with AETHERIUM_SHADERS_LOD=1: those failures are expected here)"

step "Done"
cat <<'EOF'
Play in the benchmark world with a pack (first run prepares the golden world: several minutes):
  benchmarks/run.py -Scenario ground-vista-quick -Play -ShaderPack BSL_v10.1.8.zip
Plain dev client (title screen):
  ./gradlew :fabric:runClient -PclientArgs="--graphicsBackend vulkan"
Measure:
  benchmarks/run.py -Scenario ground-vista-quick -Profile baseline,default -Label my-change
Debug Vulkan/MoltenVK (after setup-macos.sh --vk-debug):
  benchmarks/run.py -Scenario ground-vista-quick -Play -ShaderPack BSL_v10.1.8.zip -VkDebug
EOF
