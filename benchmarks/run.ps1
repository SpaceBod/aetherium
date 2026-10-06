<#
.SYNOPSIS
  Runs Aetherium benchmark scenarios and renders a comparison report.

.DESCRIPTION
  Every measured run is its own game launch (fresh JVM, fresh copy of the golden world), driven entirely
  by the mod's benchmark driver: it opens the world, waits until the scene is fully built, flies the
  scenario's camera path while recording every frame, writes a result JSON and quits.

  Runs are interleaved (repeat 1 of every scenario x profile, then repeat 2, ...) so slow drift on the
  machine (thermals, background work) spreads over all variants instead of biasing one.

  Golden worlds live in benchmarks/worlds/<id> (gitignored). A missing world is prepared first: created
  from the seed in worlds.json, every pregenerate path flown once, saved.

.PARAMETER Scenario
  Scenario ids (file names in scenarios/). Default: all.
.PARAMETER Profile
  One or more config profiles to compare: baseline (every optimisation off) and/or default. A profile can
  carry its own overrides after '+' (e.g. default+gpu.cull), to compare variants in one interleaved run.
.PARAMETER Set
  Optimisation overrides applied on top of every profile, e.g. -Set light.no_map_clone=true
.PARAMETER Backend
  vulkan or opengl (passed to the game as --graphicsBackend, which overrides options.txt).
.PARAMETER Repeat
  Runs per scenario x profile. 3 is the minimum for a noise estimate.
.PARAMETER RenderDistance
  Overrides every scenario's render distance (the result id gets an @rd<N> suffix).
.PARAMETER Label
  Name of the results folder: results/<date>_<label>/.
.PARAMETER Prepare
  Re-create the golden worlds even if they exist.
.PARAMETER ShaderPack
  Aetherium Shaders: pack file name in fabric/run/shaderpacks to enable for the run.
.PARAMETER RenderScale
  Aetherium Shaders render scale in percent (50-100, 100 = off) for the run; needs -ShaderPack.
.PARAMETER Play
  Opens the scenario's golden world for you to play (creative, on the ground at spawn, noon) instead of measuring:
  nothing is driven, the game does not quit, the script returns once it is launched. A screenshot is saved 15 s in
  (fabric/run/screenshots/aetherium-play.png). Typical: -Play -ShaderPack <zip>
.PARAMETER DistantHorizons
  Runs with the level-of-detail mod (libs/DistantHorizons-fabric-*.jar, built by tools/build-lod.sh) in fabric/run/mods.
  Without the switch the jar is removed from there, so other runs stay without it.
.PARAMETER VerifyLight
  Parity check for light.no_map_clone: every published light snapshot is compared with the full map
  (slow; for correctness runs, not for timing). Failures are counted in work.lightVerifyFailures.

.EXAMPLE
  .\benchmarks\run.ps1 -Scenario idle-vista,flyover -Profile baseline,default -Repeat 3 -Label light-map-clone
.EXAMPLE
  .\benchmarks\run.ps1 -Backend opengl -Label gl-vs-vk
#>
param(
    [string[]]$Scenario,
    [string[]]$Profile = @("baseline"),
    [string[]]$Set = @(),
    [ValidateSet("vulkan", "opengl")]
    [string]$Backend = "vulkan",
    [int]$Repeat = 3,
    [int]$RenderDistance = 0,
    [int]$Width = 1920,
    [int]$Height = 1080,
    [string]$Label = "run",
    [int]$TimeoutMinutes = 15,
    [switch]$Prepare,
    [switch]$VerifyLight,
    [string]$ShaderPack = "",
    [ValidateRange(50, 100)]
    [int]$RenderScale = 100,
    [switch]$Play,
    [switch]$DistantHorizons,
    [switch]$NoReport
)

$ErrorActionPreference = "Stop"
$bench = $PSScriptRoot
$root = Split-Path $bench -Parent
$runDir = Join-Path $root "fabric\run"
$gradle = Join-Path $root "gradlew.bat"
$utf8 = New-Object System.Text.UTF8Encoding $false

function Write-Utf8([string]$path, [string]$text) {
    New-Item -ItemType Directory -Force (Split-Path $path -Parent) | Out-Null
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}

function Get-Machine {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $cs = Get-CimInstance Win32_ComputerSystem
    $os = Get-CimInstance Win32_OperatingSystem
    $gpus = @(Get-CimInstance Win32_VideoController | ForEach-Object { "$($_.Name) (driver $($_.DriverVersion))" })
    [ordered]@{
        host    = $env:COMPUTERNAME
        cpu     = $cpu.Name.Trim()
        cores   = [int](Get-CimInstance Win32_Processor | Measure-Object NumberOfCores -Sum).Sum
        threads = [int](Get-CimInstance Win32_Processor | Measure-Object NumberOfLogicalProcessors -Sum).Sum
        ramGB   = [math]::Round($cs.TotalPhysicalMemory / 1GB, 1)
        gpus    = $gpus
        os      = "$($os.Caption) $($os.Version)"
        power   = ((powercfg /getactivescheme) -replace '^.*\((.*)\).*$', '$1')
    }
}

function Get-GradleProperty([string]$name) {
    $line = Get-Content (Join-Path $root "gradle.properties") | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    return ($line -split '=', 2)[1].Trim()
}

# Moves the game window to the second (non-primary) monitor once it appears.
function Start-WindowMover {
    Start-Process -FilePath "powershell.exe" -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $bench "tools\move-window.ps1")) `
        -WindowStyle Hidden | Out-Null
}

# Launches the dev client once and waits for it. The mod reads aetherium/bench/request.json on start-up.
function Invoke-Game([string]$requestJson, [string]$resultPath, [string]$configJson) {
    Write-Utf8 (Join-Path $runDir "aetherium\bench\request.json") $req
    Write-Utf8 (Join-Path $runDir "config\aetherium.json") $configJson
    if (Test-Path $resultPath) { Remove-Item $resultPath -Force }

    $clientArgs = "--graphicsBackend $Backend --width $Width --height $Height"
    $gradleArgs = @(":fabric:runClient", "-PclientArgs=`"$clientArgs`"", "--console=plain", "-q")
    $log = Join-Path $bench "results\.last-game.log"
    Start-WindowMover
    $proc = Start-Process -FilePath $gradle -ArgumentList $gradleArgs -WorkingDirectory $root -PassThru -NoNewWindow `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    # Other processes' 3D-engine use while the game runs (wallpaper engines, browsers, the compositor): the GPU
    # time-slices between them, so a busy neighbour stretches every GPU pass of ours by a varying amount.
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    $gpuSamples = New-Object System.Collections.Generic.List[double]
    $gpuByProcess = @{}
    while (-not $proc.WaitForExit(2000)) {
        if ((Get-Date) -gt $deadline) {
            Write-Warning "Run exceeded $TimeoutMinutes min, killing the game"
            & taskkill /PID $proc.Id /T /F | Out-Null
            break
        }
        $other = Get-OtherGpuLoad
        if ($null -ne $other) {
            $gpuSamples.Add($other.total)
            foreach ($k in $other.byProcess.Keys) { $gpuByProcess[$k] = [math]::Max([double]$gpuByProcess[$k], $other.byProcess[$k]) }
        }
    }
    if (-not (Test-Path $resultPath)) {
        throw "No result written ($resultPath). See $log and fabric/run/logs/latest.log"
    }
    $result = Get-Content $resultPath -Raw | ConvertFrom-Json
    if ($result.error) { throw "Benchmark failed: $($result.error)" }
    if ($gpuSamples.Count -gt 0) {
        $mean = ($gpuSamples | Measure-Object -Average).Average
        $max = ($gpuSamples | Measure-Object -Maximum).Maximum
        $top = [ordered]@{}
        $gpuByProcess.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 5 | ForEach-Object { $top[$_.Key] = [math]::Round($_.Value, 1) }
        $side = [ordered]@{ samples = $gpuSamples.Count; meanPercent = [math]::Round($mean, 2); maxPercent = [math]::Round($max, 2); topProcessesMaxPercent = $top }
        Write-Utf8 ([IO.Path]::ChangeExtension($resultPath, ".gpuload.json")) ($side | ConvertTo-Json -Depth 4)
        if ($mean -gt 2) {
            $names = ($top.Keys | Select-Object -First 3) -join ", "
            Write-Warning ("Other processes used {0:N1}% of the GPU 3D engine on average (max {1:N1}%: {2}); this run's GPU timings are inflated" -f $mean, $max, $names)
        }
    }
    return $result
}

# Sums the 3D-engine utilisation of every process except the game (java/javaw) and the compositor, grouped by
# process name.
function Get-OtherGpuLoad {
    try {
        $samples = (Get-Counter '\GPU Engine(*engtype_3D)\Utilization Percentage' -ErrorAction Stop).CounterSamples
    } catch { return $null }
    $byPid = @{}
    foreach ($c in $samples) {
        if ($c.InstanceName -match '^pid_(\d+)_') { $byPid[[int]$Matches[1]] = [double]$byPid[[int]$Matches[1]] + $c.CookedValue }
    }
    $total = 0.0
    $byProcess = @{}
    foreach ($id in $byPid.Keys) {
        if ($byPid[$id] -lt 0.1) { continue }
        $name = (Get-Process -Id $id -ErrorAction SilentlyContinue).ProcessName
        if (-not $name) { $name = "pid $id" }
        # dwm composites the game's own window (about 12% at 1080p windowed), so it is recorded but not counted.
        if ($name -in @("java", "javaw")) { continue }
        if ($name -ne "dwm") { $total += $byPid[$id] }
        $byProcess[$name] = [double]$byProcess[$name] + $byPid[$id]
    }
    return @{ total = $total; byProcess = $byProcess }
}

# A profile may carry its own overrides after '+', e.g. "default+gpu.cull" or "default+gpu.cull=false+mem.palette_lock",
# so two variants of one profile can be compared in one interleaved run. They apply after -Set.
function New-Config([string]$profileSpec) {
    $parts = $profileSpec -split '\+'
    $profileName = $parts[0]
    $opts = [ordered]@{}
    foreach ($s in ($Set + ($parts | Select-Object -Skip 1))) {
        $kv = $s -split '=', 2
        $opts[$kv[0]] = if ($kv.Count -eq 2) { [bool]::Parse($kv[1]) } else { $true }
    }
    $debug = @{ verifyLightSnapshots = [bool]$VerifyLight }
    $config = @{ profile = $profileName; optimisations = $opts; bench = @{ gpuTimers = $true; passTimers = $true }; debug = $debug }
    if ($ShaderPack) { $config.shaders = @{ enabled = $true; pack = $ShaderPack; renderScale = $RenderScale } }
    return ($config | ConvertTo-Json -Depth 5)
}

# ---------------------------------------------------------------- optional mods

$modsDir = Join-Path $runDir "mods"
Get-ChildItem $modsDir -Filter "DistantHorizons*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
if ($DistantHorizons) {
    $lod = Get-ChildItem (Join-Path $root "libs") -Filter "DistantHorizons-fabric-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $lod) { throw "libs/DistantHorizons-fabric-*.jar missing: run benchmarks/tools/build-lod.sh first" }
    New-Item -ItemType Directory -Force $modsDir | Out-Null
    Copy-Item $lod.FullName $modsDir
}

# ---------------------------------------------------------------- scenarios and worlds

$allScenarioFiles = Get-ChildItem (Join-Path $bench "scenarios") -Filter *.json | Sort-Object Name
if (-not $Scenario) { $Scenario = $allScenarioFiles | ForEach-Object { $_.BaseName } }
$worlds = Get-Content (Join-Path $bench "worlds.json") -Raw | ConvertFrom-Json

$scenarioText = @{}
$scenarioWorld = @{}
$scenarioRd = @{}
foreach ($f in $allScenarioFiles) {
    $text = Get-Content $f.FullName -Raw
    if ($RenderDistance -gt 0) {
        $text = $text -replace '"renderDistance":\s*\d+', "`"renderDistance`": $RenderDistance"
        $text = $text -replace '"id":\s*"([^"]+)"', "`"id`": `"`$1@rd$RenderDistance`""
    }
    $parsed = $text | ConvertFrom-Json
    $scenarioText[$f.BaseName] = $text
    $scenarioWorld[$f.BaseName] = $parsed.world
    $scenarioRd[$f.BaseName] = [int]$parsed.renderDistance
}
foreach ($s in $Scenario) {
    if (-not $scenarioText.ContainsKey($s)) { throw "Unknown scenario '$s' (see benchmarks/scenarios/)" }
}

$machine = Get-Machine
$meta = [ordered]@{
    runner          = "benchmarks/run.ps1"
    modVersion      = Get-GradleProperty "mod_version"
    loader          = "fabric"
    loaderVersion   = Get-GradleProperty "loader_version"
    backendRequested = $Backend
    machine         = $machine
    extraMods       = @(Get-ChildItem $modsDir -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
}

# Windows PowerShell turns any stderr line of a native command into an error record, and every JVM on a
# multi-processor-group machine prints a warning there. Run native tools with errors non-terminating and
# decide on the exit code instead.
function Invoke-Native([string]$exe, [string[]]$arguments) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $exe @arguments 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'UseAllWindowsProcessorGroups' } | Write-Host
    } finally {
        $ErrorActionPreference = $prev
    }
    return $LASTEXITCODE
}

Write-Host "Building..." -ForegroundColor Cyan
if ((Invoke-Native $gradle @(":fabric:classes", "--console=plain", "-q")) -ne 0) { throw "Build failed" }

# A golden world is generated at exactly the render distance of the scenarios that use it: generating
# further out would pre-build terrain a worldgen scenario is meant to generate live. Keyed "<world>-rd<N>".
function Get-GoldenName([string]$scenarioId) {
    $w = $scenarioWorld[$scenarioId]
    $rd = ($Scenario | Where-Object { $scenarioWorld[$_] -eq $w } | ForEach-Object { $scenarioRd[$_] } | Measure-Object -Maximum).Maximum
    return "$w-rd$rd"
}

$neededWorlds = $Scenario | ForEach-Object { Get-GoldenName $_ } | Sort-Object -Unique
foreach ($golden in $neededWorlds) {
    $w = $golden -replace '-rd\d+$', ''
    $prepRd = [int]($golden -replace '^.*-rd', '')
    $goldenName = $golden
    $golden = Join-Path $bench "worlds\$goldenName"
    if ((Test-Path (Join-Path $golden "level.dat")) -and -not $Prepare) { continue }
    $def = $worlds.$w
    if (-not $def) { throw "World '$w' is not defined in worlds.json" }
    Write-Host "Preparing golden world '$goldenName' (seed $($def.seed), render distance $prepRd)..." -ForegroundColor Cyan
    $saveName = "aeth-prep-$goldenName"
    $save = Join-Path $runDir "saves\$saveName"
    if (Test-Path $save) { Remove-Item $save -Recurse -Force }
    $prepScenarios = ($allScenarioFiles | Where-Object { $scenarioWorld[$_.BaseName] -eq $w } |
        ForEach-Object { $scenarioText[$_.BaseName] }) -join ","
    $out = Join-Path $bench "worlds\.prepare-$goldenName.json"
    $req = [ordered]@{
        mode = "prepare"; saveName = $saveName; seed = [long]$def.seed; prepareRenderDistance = $prepRd
        prepareTimeScale = 3.0; output = $out; exitWhenDone = $true; scenarios = "__SCENARIOS__"; meta = $meta
    } | ConvertTo-Json -Depth 10
    $req = $req.Replace('"__SCENARIOS__"', "[$prepScenarios]")
    $r = Invoke-Game $req $out (New-Config "baseline")
    if (Test-Path $golden) { Remove-Item $golden -Recurse -Force }
    Copy-Item $save $golden -Recurse
    Write-Host ("  prepared in {0:N0} s" -f $r.seconds)
}

# ---------------------------------------------------------------- play (interactive)

if ($Play) {
    $s = $Scenario[0]
    $goldenName = Get-GoldenName $s
    $saveName = "aeth-play-$goldenName"
    $save = Join-Path $runDir "saves\$saveName"
    if (Test-Path $save) { Remove-Item $save -Recurse -Force }
    Copy-Item (Join-Path $bench "worlds\$goldenName") $save -Recurse
    $req = [ordered]@{ mode = "play"; saveName = $saveName; prepareRenderDistance = $scenarioRd[$s]; exitWhenDone = $false; meta = $meta } |
        ConvertTo-Json -Depth 10
    Write-Utf8 (Join-Path $runDir "aetherium\bench\request.json") $req
    # Play mode shows the build as shipped: the default profile unless -Profile is given (measured runs default to baseline).
    $playProfile = if ($PSBoundParameters.ContainsKey('Profile')) { $Profile[0] } else { "default" }
    Write-Utf8 (Join-Path $runDir "config\aetherium.json") (New-Config $playProfile)
    $shot = Join-Path $runDir "screenshots\aetherium-play.png"
    if (Test-Path $shot) { Remove-Item $shot -Force }
    $clientArgs = "--graphicsBackend $Backend --width $Width --height $Height"
    $log = Join-Path $bench "results\.last-game.log"
    Start-WindowMover
    Start-Process -FilePath $gradle -ArgumentList @(":fabric:runClient", "-PclientArgs=`"$clientArgs`"", "--console=plain", "-q") `
        -WorkingDirectory $root -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
    Write-Host "Game launching into '$saveName' (creative, on the ground). Close it when you are done." -ForegroundColor Cyan
    return
}

# ---------------------------------------------------------------- measured runs

$stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
$resultsDir = Join-Path $bench "results\${stamp}_$Label"
New-Item -ItemType Directory -Force $resultsDir | Out-Null
$total = $Repeat * $Scenario.Count * $Profile.Count
$n = 0
Write-Host "Running $total benchmark(s) into $resultsDir" -ForegroundColor Cyan
Write-Host "Leave the machine alone while this runs: input, other windows and background load all show up in the numbers." -ForegroundColor Yellow

for ($rep = 1; $rep -le $Repeat; $rep++) {
    foreach ($s in $Scenario) {
        foreach ($p in $Profile) {
            $n++
            $goldenName = Get-GoldenName $s
            $saveName = "aeth-bench-$goldenName"
            $save = Join-Path $runDir "saves\$saveName"
            if (Test-Path $save) { Remove-Item $save -Recurse -Force }
            Copy-Item (Join-Path $bench "worlds\$goldenName") $save -Recurse
            $sid = ($scenarioText[$s] | ConvertFrom-Json).id
            $out = Join-Path $resultsDir "${sid}_${p}_${Backend}_r$rep.json"
            $runMeta = [ordered]@{}
            foreach ($k in $meta.Keys) { $runMeta[$k] = $meta[$k] }
            $runMeta.label = $Label
            $runMeta.repeat = $rep
            $runMeta.repeats = $Repeat
            $runMeta.profileRequested = $p
            $runMeta.overrides = $Set
            $req = [ordered]@{
                mode = "measure"; saveName = $saveName; output = $out; exitWhenDone = $true
                scenario = "__SCENARIO__"; meta = $runMeta
            } | ConvertTo-Json -Depth 10
            $req = $req.Replace('"__SCENARIO__"', $scenarioText[$s])
            Write-Host ("[{0}/{1}] {2} / {3} / {4} / run {5}" -f $n, $total, $sid, $p, $Backend, $rep)
            $r = Invoke-Game $req $out (New-Config $p)
            Write-Host ("        {0:N1} fps avg, p99 {1:N2} ms, settled after {2:N1} s" -f $r.quick.avgFps, $r.quick.p99Ms, $r.load.settledSeconds)
        }
    }
}

if (-not $NoReport) {
    $report = Join-Path $bench "reports\${stamp}_$Label.html"
    if ((Invoke-Native "python" @((Join-Path $bench "tools\report.py"), $resultsDir, "-o", $report)) -eq 0) {
        Write-Host "Report: $report" -ForegroundColor Green
    }
}
