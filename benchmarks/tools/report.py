#!/usr/bin/env python3
"""Aetherium benchmark report: result JSONs in, one self-contained HTML page out.

    python benchmarks/tools/report.py benchmarks/results/<run-dir> [more dirs or files] -o report.html
        [--baseline baseline]  group label treated as the reference (default: the 'baseline' profile)
        [--markdown out.md]    also write the summary tables as Markdown

Standard library only. Every statistic is computed here from the raw per-frame data in the result files,
so definitions can change without re-running anything. Definitions are listed at the bottom of the page
and in benchmarks/README.md.
"""
from __future__ import annotations

import argparse
import html
import json
import math
import statistics
import sys
from collections import OrderedDict
from datetime import datetime
from pathlib import Path

# ----------------------------------------------------------------------------- loading


def load_results(paths: list[str]) -> tuple[list[dict], list[str]]:
    files: list[Path] = []
    for p in paths:
        path = Path(p)
        if path.is_dir():
            files += sorted(p for p in path.glob("*.json") if not p.name.endswith(".gpuload.json"))
        elif path.suffix == ".json":
            files.append(path)
    runs, problems = [], []
    for f in files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            problems.append(f"{f.name}: unreadable ({e})")
            continue
        if data.get("error"):
            problems.append(f"{f.name}: benchmark failed: {data['error']}")
            continue
        if data.get("mode") != "measure":
            continue
        throttled = data.get("frames", {}).get("throttledFrames")
        if throttled is None:
            problems.append(f"{f.name}: recorded before the frame-limiter guard existed (may be AFK-throttled), excluded")
            continue
        if throttled > 0:
            problems.append(f"{f.name}: {throttled} frames throttled by vanilla's frame limiter (minimised/AFK), excluded")
            continue
        side = f.with_suffix(".gpuload.json")
        if side.exists():
            try:
                load = json.loads(side.read_text(encoding="utf-8-sig"))
                data["_gpuload"] = load
                if load.get("meanPercent", 0) > 2:
                    top = ", ".join(load.get("topProcessesMaxPercent", {}).keys())
                    problems.append(f"{f.name}: other processes used {load['meanPercent']:.1f}% of the GPU 3D engine "
                                    f"(max {load.get('maxPercent', 0):.1f}%: {top}); GPU timings inflated")
            except (OSError, json.JSONDecodeError):
                pass
        data["_file"] = f.name
        runs.append(data)
    return runs, problems


def group_label(run: dict) -> str:
    meta = run.get("meta", {})
    profile = meta.get("profile", "?")
    overrides = meta.get("overrides") or []
    if isinstance(overrides, str):
        overrides = [overrides]
    backend = meta.get("device", {}).get("backend", meta.get("backendRequested", "?"))
    label = profile
    if overrides:
        label += " [" + ", ".join(overrides) + "]"
    return f"{label} · {backend}"


# ----------------------------------------------------------------------------- statistics


def pct(sorted_values: list[float], q: float) -> float:
    """Nearest-rank percentile on an ascending list, q in [0, 100]."""
    if not sorted_values:
        return float("nan")
    k = max(0, min(len(sorted_values) - 1, math.ceil(q / 100.0 * len(sorted_values)) - 1))
    return sorted_values[k]


def run_metrics(run: dict) -> dict:
    frames = run["frames"]
    # Frame 0's interval spans the warm-up boundary; zeros are frames the surface skipped.
    interval = [v / 1000.0 for v in frames["interval"][1:] if v > 0]
    cpu = [v / 1000.0 for v in frames["cpu"][1:] if v > 0]
    gpu = [v / 1000.0 for v in frames["gpu"][1:] if v >= 0]
    m: dict = {}
    if not interval:
        return m
    total_s = sum(interval) / 1000.0
    si = sorted(interval)
    m["avg_fps"] = len(interval) / total_s
    for q in (50, 90, 95, 99, 99.9):
        m[f"p{q}_ms"] = pct(si, q)
    worst1 = si[-max(1, len(si) // 100):]
    worst01 = si[-max(1, len(si) // 1000):]
    m["low1_fps"] = 1000.0 / statistics.fmean(worst1)
    m["low01_fps"] = 1000.0 / statistics.fmean(worst01)
    med = pct(si, 50)
    # A stutter is a frame a player notices: well above the typical frame and at least ~10 ms longer
    # (at 400 fps a 6 ms frame is not a hitch; at 60 fps a 30 ms one is).
    threshold = max(2.5 * med, med + 10.0)
    m["stutters_per_min"] = sum(1 for v in interval if v > threshold) / (total_s / 60.0)
    m["max_ms"] = si[-1]
    m["stdev_ms"] = statistics.pstdev(interval)
    if cpu:
        sc = sorted(cpu)
        m["cpu_p50_ms"] = pct(sc, 50)
        m["cpu_p95_ms"] = pct(sc, 95)
    if gpu:
        sg = sorted(gpu)
        m["gpu_p50_ms"] = pct(sg, 50)
        m["gpu_p95_ms"] = pct(sg, 95)
        m["gpu_coverage"] = len(gpu) / len(interval)
        m["gpu_bound"] = m["gpu_p50_ms"] >= 0.85 * med
    m["duration_s"] = total_s
    m["frames"] = len(interval)

    work = run.get("work", {})
    if total_s > 0 and work:
        m["sections_per_s"] = work.get("sectionsCompiled", 0) / total_s
        m["compile_ms_per_s"] = work.get("compileMs", 0.0) / total_s
        if work.get("sectionsCompiled"):
            m["compile_ms_per_section"] = work.get("compileMs", 0.0) / work["sectionsCompiled"]
        m["resorts_per_s"] = work.get("resorts", 0) / total_s
        if "lightPublishes" in work:
            m["light_publishes_per_s"] = work["lightPublishes"] / total_s
            m["light_publish_ms_per_s"] = work["lightPublishMs"] / total_s
            m["light_publish_us_each"] = work["lightPublishMs"] * 1000.0 / work["lightPublishes"] if work["lightPublishes"] else None
            m["light_notifications_per_s"] = work["lightNotifications"] / total_s
        if "rebuiltWithin1s" in work:
            m["rebuilt_1s_per_s"] = work["rebuiltWithin1s"] / total_s
            m["rebuilt_share"] = 100.0 * work["rebuiltWithin1s"] / max(1, work.get("sectionsCompiled", 0))
            tasks = work.get("clientLightTasks", 0)
            m["light_wait_ms"] = work["clientLightWaitMs"] / tasks if tasks else None
            m["light_packet_sections_per_s"] = work["lightPacketSections"] / total_s
            m["light_packet_dirtied_per_s"] = work["lightPacketDirtied"] / total_s
            m["light_packet_unchanged_pct"] = 100.0 * work["lightPacketUnchanged"] / work["lightPacketSections"] if work["lightPacketSections"] else None
        if "lightPublishMsRender" in work:
            m["light_publish_render_ms_per_s"] = work["lightPublishMsRender"] / total_s
        if work.get("noiseFills"):
            m["noise_fills_per_s"] = work["noiseFills"] / total_s
            m["noise_fill_ms_each"] = work["noiseFillMs"] / work["noiseFills"]
            m["noise_fill_fast_pct"] = 100.0 * work.get("noiseFillsFast", 0) / work["noiseFills"]
        if work.get("gpuCullTested"):
            m["gpu_cull_hidden_pct"] = 100.0 * work.get("gpuCullHidden", 0) / work["gpuCullTested"]
        if work.get("biomeFills"):
            m["biome_fill_ms_each"] = work["biomeFillMs"] / work["biomeFills"]
    load = run.get("load", {})
    if load.get("settledSeconds", -1) >= 0:
        m["settled_s"] = load["settledSeconds"]
    if load.get("joinSeconds", -1) >= 0:
        m["join_s"] = load["joinSeconds"]
    if load.get("heapAfterGcSettledMB", -1) >= 0:
        m["heap_live_settled_mb"] = load["heapAfterGcSettledMB"]
    if load.get("heapAfterGcEndMB", -1) >= 0:
        m["heap_live_end_mb"] = load["heapAfterGcEndMB"]

    # System samples (1 Hz) inside the measured window: the timeline's 'running' phase gives its bounds.
    tl = run.get("timeline", [])
    running = [s for s in tl if s.get("phase") == "running"]
    if running:
        t0, t1 = running[0]["t"] - 1.0, running[-1]["t"]
        sysw = [s for s in run.get("system", []) if t0 <= s["t"] <= t1 + 1.0]
    else:
        sysw = run.get("system", [])
    if sysw:
        m["heap_peak_mb"] = max(s["heapUsedMB"] for s in sysw)
        alloc = [s["allocMB"] for s in sysw if "allocMB" in s]
        if alloc:
            m["alloc_mb_per_s"] = statistics.fmean(alloc)
        m["gc_ms_per_min"] = sum(s.get("gcMs", 0) for s in sysw) / max(1e-9, len(sysw) / 60.0)
        cpu_load = [s["processCpu"] for s in sysw if s.get("processCpu", -1) >= 0]
        if cpu_load:
            m["process_cpu_pct"] = 100.0 * statistics.fmean(cpu_load)
    tick = [s["serverTickMs"] for s in running if "serverTickMs" in s]
    if tick:
        m["server_mspt"] = statistics.fmean(tick)
    queue = [s["compileQueue"] for s in running if "compileQueue" in s]
    if queue:
        m["compile_queue_avg"] = statistics.fmean(queue)
    return m


# name, label, unit, higher_is_better, decimals
METRICS = [
    ("avg_fps", "Avg FPS", "", True, 1),
    ("low1_fps", "1% low", "fps", True, 1),
    ("low01_fps", "0.1% low", "fps", True, 1),
    ("p50_ms", "Frame p50", "ms", False, 2),
    ("p95_ms", "Frame p95", "ms", False, 2),
    ("p99_ms", "Frame p99", "ms", False, 2),
    ("p99.9_ms", "Frame p99.9", "ms", False, 2),
    ("stutters_per_min", "Stutters", "/min", False, 1),
    ("cpu_p50_ms", "CPU frame p50", "ms", False, 2),
    ("gpu_p50_ms", "GPU frame p50", "ms", False, 2),
    ("gpu_p95_ms", "GPU frame p95", "ms", False, 2),
    ("sections_per_s", "Sections built", "/s", None, 0),
    ("compile_ms_per_s", "Mesh worker time", "ms/s", False, 0),
    ("compile_ms_per_section", "Mesh time per section", "ms", False, 2),
    ("compile_queue_avg", "Compile queue", "", False, 0),
    ("rebuilt_1s_per_s", "Rebuilt again within 1 s", "/s", False, 1),
    ("rebuilt_share", "…share of all builds", "%", False, 1),
    ("light_wait_ms", "Client light task wait", "ms", False, 2),
    ("light_packet_sections_per_s", "Light-update sections", "/s", None, 0),
    ("light_packet_unchanged_pct", "…unchanged", "%", None, 0),
    ("light_packet_dirtied_per_s", "Meshes dirtied by light packets", "/s", False, 0),
    ("light_publishes_per_s", "Light publishes", "/s", None, 0),
    ("light_publish_us_each", "Light publish cost", "µs each", False, 1),
    ("light_publish_ms_per_s", "Light publish time", "ms/s", False, 2),
    ("light_publish_render_ms_per_s", "…of which render thread", "ms/s", False, 2),
    ("light_notifications_per_s", "Light section updates", "/s", None, 0),
    ("settled_s", "Time to settled", "s", False, 1),
    ("join_s", "World open", "s", False, 1),
    ("server_mspt", "Server MSPT", "ms", False, 2),
    ("noise_fills_per_s", "Noise fills (chunks)", "/s", None, 1),
    ("noise_fill_ms_each", "Noise fill cost", "ms each", False, 2),
    ("noise_fill_fast_pct", "…by worldgen.fast_noise", "%", None, 0),
    ("biome_fill_ms_each", "Biome fill cost", "ms each", False, 3),
    ("gpu_cull_hidden_pct", "Terrain draws hidden by gpu.cull", "%", None, 1),
    ("heap_live_settled_mb", "Live heap after GC (settled)", "MB", False, 1),
    ("heap_live_end_mb", "Live heap after GC (end)", "MB", False, 1),
    ("heap_peak_mb", "Heap peak", "MB", False, 0),
    ("alloc_mb_per_s", "Allocation", "MB/s", False, 0),
    ("gc_ms_per_min", "GC pause", "ms/min", False, 0),
    ("process_cpu_pct", "Process CPU", "%", None, 0),
]


def aggregate(values: list[float]) -> dict | None:
    values = [v for v in values if v is not None and not (isinstance(v, float) and math.isnan(v))]
    if not values:
        return None
    mean = statistics.fmean(values)
    sd = statistics.stdev(values) if len(values) > 1 else 0.0
    return {"mean": mean, "sd": sd, "n": len(values), "min": min(values), "max": max(values),
            "cv": (sd / mean * 100.0) if mean else 0.0}


# ----------------------------------------------------------------------------- formatting helpers

PALETTE = ["#3b82f6", "#f97316", "#10b981", "#a855f7", "#ef4444", "#14b8a6", "#eab308", "#64748b"]


def esc(s) -> str:
    return html.escape(str(s))


def fmt(v: float | None, d: int) -> str:
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "–"
    return f"{v:,.{d}f}"


def delta_cell(base: dict | None, cur: dict | None, higher_better, d: int) -> str:
    if not base or not cur or base["mean"] == 0:
        return "<td class='num muted'>–</td>"
    change = (cur["mean"] - base["mean"]) / abs(base["mean"]) * 100.0
    noise = 2.0 * math.sqrt(base["sd"] ** 2 + cur["sd"] ** 2)
    significant = abs(cur["mean"] - base["mean"]) > noise and base["n"] > 1 and cur["n"] > 1
    if higher_better is None or abs(change) < 0.05:
        cls = "muted"
    else:
        better = change > 0 if higher_better else change < 0
        cls = ("good" if better else "bad") + ("" if significant else " weak")
    mark = "" if significant or base["n"] < 2 or cur["n"] < 2 else " title='within 2σ of run-to-run noise'"
    return f"<td class='num delta {cls}'{mark}>{change:+.1f}%</td>"


# ----------------------------------------------------------------------------- SVG charts


def svg_frametime(series: list[tuple[str, str, list[float]]], width=920, height=240) -> str:
    """Frame time over the run: per-bucket median line with a faint max band, one per group."""
    pad_l, pad_r, pad_t, pad_b = 44, 12, 12, 26
    buckets = 240
    prepared = []
    ymax = 0.0
    tmax = 0.0
    for label, color, intervals in series:
        if not intervals:
            continue
        t, times = 0.0, []
        for v in intervals:
            t += v / 1000.0
            times.append(t)
        tmax = max(tmax, t)
        prepared.append((label, color, intervals, times))
    if not prepared:
        return ""
    for _, _, intervals, _ in prepared:
        s = sorted(intervals)
        ymax = max(ymax, pct(s, 99.5))
    ymax = max(1.0, ymax * 1.15)
    w, h = width - pad_l - pad_r, height - pad_t - pad_b

    def x(tt):
        return pad_l + tt / tmax * w

    def y(ms):
        return pad_t + h - min(ms, ymax) / ymax * h

    out = [f"<svg viewBox='0 0 {width} {height}' class='chart' role='img' aria-label='frame time over time'>"]
    for i in range(5):
        v = ymax * i / 4
        yy = y(v)
        out.append(f"<line x1='{pad_l}' x2='{width - pad_r}' y1='{yy:.1f}' y2='{yy:.1f}' class='grid'/>")
        out.append(f"<text x='{pad_l - 6}' y='{yy + 4:.1f}' class='axis' text-anchor='end'>{v:.1f}</text>")
    for i in range(6):
        tt = tmax * i / 5
        out.append(f"<text x='{x(tt):.1f}' y='{height - 8}' class='axis' text-anchor='middle'>{tt:.0f}s</text>")
    for label, color, intervals, times in prepared:
        per = max(1, len(intervals) // buckets)
        med_pts, max_pts = [], []
        for b in range(0, len(intervals), per):
            chunk = intervals[b:b + per]
            tt = times[min(len(times) - 1, b + per - 1)]
            med_pts.append((x(tt), y(statistics.median(chunk))))
            max_pts.append((x(tt), y(max(chunk))))
        out.append("<polyline fill='none' stroke='{}' stroke-opacity='0.25' stroke-width='1' points='{}'/>".format(
            color, " ".join(f"{a:.1f},{b:.1f}" for a, b in max_pts)))
        out.append("<polyline fill='none' stroke='{}' stroke-width='1.8' points='{}'><title>{}</title></polyline>".format(
            color, " ".join(f"{a:.1f},{b:.1f}" for a, b in med_pts), esc(label)))
    out.append(f"<text x='{pad_l}' y='{pad_t - 2}' class='axis'>ms</text></svg>")
    return "".join(out)


def svg_percentiles(series: list[tuple[str, str, list[float]]], width=450, height=240) -> str:
    """Frame-time percentile curve from p50 to p99.9 on a log-of-tail axis."""
    pad_l, pad_r, pad_t, pad_b = 44, 12, 12, 26
    qs = [50, 60, 70, 80, 90, 95, 97, 98, 99, 99.5, 99.8, 99.9]

    def xpos(q):
        return -math.log10(100.0 - q)  # 50 -> -1.7, 99.9 -> 1

    x0, x1 = xpos(50), xpos(99.9)
    curves, ymax = [], 1.0
    for label, color, intervals in series:
        if not intervals:
            continue
        s = sorted(intervals)
        pts = [(q, pct(s, q)) for q in qs]
        ymax = max(ymax, pts[-1][1])
        curves.append((label, color, pts))
    if not curves:
        return ""
    ymax *= 1.1
    w, h = width - pad_l - pad_r, height - pad_t - pad_b
    out = [f"<svg viewBox='0 0 {width} {height}' class='chart' role='img' aria-label='frame time percentiles'>"]
    for i in range(5):
        v = ymax * i / 4
        yy = pad_t + h - v / ymax * h
        out.append(f"<line x1='{pad_l}' x2='{width - pad_r}' y1='{yy:.1f}' y2='{yy:.1f}' class='grid'/>")
        out.append(f"<text x='{pad_l - 6}' y='{yy + 4:.1f}' class='axis' text-anchor='end'>{v:.1f}</text>")
    for q in (50, 90, 99, 99.9):
        xx = pad_l + (xpos(q) - x0) / (x1 - x0) * w
        out.append(f"<text x='{xx:.1f}' y='{height - 8}' class='axis' text-anchor='middle'>p{q:g}</text>")
    for label, color, pts in curves:
        coords = " ".join(f"{pad_l + (xpos(q) - x0) / (x1 - x0) * w:.1f},{pad_t + h - v / ymax * h:.1f}" for q, v in pts)
        out.append(f"<polyline fill='none' stroke='{color}' stroke-width='2' points='{coords}'><title>{esc(label)}</title></polyline>")
    out.append(f"<text x='{pad_l}' y='{pad_t - 2}' class='axis'>ms</text></svg>")
    return "".join(out)


def svg_passes(groups: list[tuple[str, str, dict[str, float]]], width=450) -> str:
    """Mean GPU time per frame-graph pass, one stacked bar per group."""
    names: list[str] = []
    for _, _, passes in groups:
        for n in passes:
            if n not in names:
                names.append(n)
    if not names:
        return ""
    row_h, pad_l, pad_r = 26, 150, 60
    total_max = max((sum(p.values()) for _, _, p in groups), default=1.0) or 1.0
    height = 20 + row_h * len(groups) + 18 * ((len(names) + 2) // 3) + 10
    w = width - pad_l - pad_r
    out = [f"<svg viewBox='0 0 {width} {height}' class='chart' role='img' aria-label='GPU time per pass'>"]
    for gi, (label, _, passes) in enumerate(groups):
        yy = 10 + gi * row_h
        out.append(f"<text x='{pad_l - 8}' y='{yy + 15}' class='axis' text-anchor='end'>{esc(label[:22])}</text>")
        xx = pad_l
        for ni, n in enumerate(names):
            v = passes.get(n, 0.0)
            bw = v / total_max * w
            if bw <= 0:
                continue
            out.append(f"<rect x='{xx:.1f}' y='{yy}' width='{bw:.1f}' height='{row_h - 8}' fill='{PALETTE[ni % len(PALETTE)]}'>"
                       f"<title>{esc(n)}: {v:.3f} ms</title></rect>")
            xx += bw
        out.append(f"<text x='{xx + 6:.1f}' y='{yy + 15}' class='axis'>{sum(passes.values()):.2f} ms</text>")
    ly = 20 + row_h * len(groups)
    for ni, n in enumerate(names):
        col, row = ni % 3, ni // 3
        lx = 10 + col * (width / 3)
        out.append(f"<rect x='{lx:.0f}' y='{ly + row * 18}' width='10' height='10' fill='{PALETTE[ni % len(PALETTE)]}'/>"
                   f"<text x='{lx + 14:.0f}' y='{ly + row * 18 + 9}' class='axis'>{esc(n)}</text>")
    out.append("</svg>")
    return "".join(out)


def svg_timeline(series: list[tuple[str, str, list[tuple[float, float]]]], ylabel: str, width=450, height=170) -> str:
    pad_l, pad_r, pad_t, pad_b = 44, 12, 14, 24
    pts_all = [p for _, _, pts in series for p in pts]
    if not pts_all:
        return ""
    tmax = max(t for t, _ in pts_all) or 1.0
    ymax = max(v for _, v in pts_all) * 1.1 or 1.0
    w, h = width - pad_l - pad_r, height - pad_t - pad_b
    out = [f"<svg viewBox='0 0 {width} {height}' class='chart' role='img' aria-label='{esc(ylabel)}'>"]
    for i in range(3):
        v = ymax * i / 2
        yy = pad_t + h - v / ymax * h
        out.append(f"<line x1='{pad_l}' x2='{width - pad_r}' y1='{yy:.1f}' y2='{yy:.1f}' class='grid'/>")
        out.append(f"<text x='{pad_l - 6}' y='{yy + 4:.1f}' class='axis' text-anchor='end'>{v:,.0f}</text>")
    for label, color, pts in series:
        coords = " ".join(f"{pad_l + t / tmax * w:.1f},{pad_t + h - v / ymax * h:.1f}" for t, v in pts)
        out.append(f"<polyline fill='none' stroke='{color}' stroke-width='1.6' points='{coords}'><title>{esc(label)}</title></polyline>")
    out.append(f"<text x='{pad_l}' y='{pad_t - 3}' class='axis'>{esc(ylabel)}</text>")
    out.append(f"<text x='{width - pad_r}' y='{height - 6}' class='axis' text-anchor='end'>{tmax:.0f}s</text></svg>")
    return "".join(out)


# ----------------------------------------------------------------------------- page


CSS = """
:root { --bg:#f7f7f5; --panel:#ffffff; --ink:#1c1c1a; --muted:#6b6b66; --line:#e3e2dd; --grid:#ecebe6;
  --good:#12805c; --bad:#c2410c; --accent:#3b5bdb; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg:#141413; --panel:#1d1d1b; --ink:#ecebe6;
  --muted:#9a9a93; --line:#2e2e2b; --grid:#262624; --good:#34d399; --bad:#fb923c; --accent:#8da2fb; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
main { max-width:1000px; margin:0 auto; padding:28px 16px 60px; }
h1 { font-size:24px; margin:0 0 4px; } h2 { font-size:18px; margin:36px 0 6px; } h3 { font-size:14px; margin:18px 0 6px; color:var(--muted); font-weight:600; }
p.lede { color:var(--muted); margin:0 0 18px; }
.panel { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:14px 16px; margin:12px 0; }
.meta { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:6px 18px; font-size:13px; }
.meta b { color:var(--muted); font-weight:500; display:block; font-size:12px; }
.scroll { overflow-x:auto; }
table { border-collapse:collapse; width:100%; font-size:13px; }
th, td { padding:5px 8px; border-bottom:1px solid var(--line); text-align:left; white-space:nowrap; }
th { color:var(--muted); font-weight:500; }
td.num, th.num { text-align:right; font-variant-numeric:tabular-nums; }
.sd { color:var(--muted); font-size:11px; }
.delta.good { color:var(--good); font-weight:600; } .delta.bad { color:var(--bad); font-weight:600; }
.delta.weak { font-weight:400; opacity:.65; } .muted { color:var(--muted); }
.legend { display:flex; flex-wrap:wrap; gap:14px; font-size:13px; margin:6px 0; }
.legend > span::before { content:""; display:inline-block; width:12px; height:3px; margin-right:6px; vertical-align:middle; background:var(--c); }
.grid2 { display:grid; grid-template-columns:repeat(auto-fit,minmax(300px,1fr)); gap:12px; }
svg.chart { width:100%; height:auto; display:block; }
svg .grid { stroke:var(--grid); stroke-width:1; } svg .axis { fill:var(--muted); font-size:11px; }
.tag { display:inline-block; font-size:11px; padding:1px 7px; border-radius:99px; border:1px solid var(--line); color:var(--muted); margin-left:6px; }
.warn { border-color:var(--bad); }
dl.defs { display:grid; grid-template-columns:max-content 1fr; gap:4px 14px; font-size:13px; }
dl.defs dt { color:var(--muted); }
"""


def build_page(runs: list[dict], problems: list[str], baseline_hint: str) -> tuple[str, str]:
    by_scenario: "OrderedDict[str, OrderedDict[str, list[dict]]]" = OrderedDict()
    for r in sorted(runs, key=lambda r: (r["scenario"]["id"], group_label(r), r.get("meta", {}).get("repeat", 0))):
        by_scenario.setdefault(r["scenario"]["id"], OrderedDict()).setdefault(group_label(r), []).append(r)

    all_groups: list[str] = []
    for groups in by_scenario.values():
        for g in groups:
            if g not in all_groups:
                all_groups.append(g)
    colors = {g: PALETTE[i % len(PALETTE)] for i, g in enumerate(all_groups)}

    first = runs[0]
    meta = first.get("meta", {})
    machine = meta.get("machine", {})
    device = meta.get("device", {})
    jvm = meta.get("jvm", {})
    opts = meta.get("options", {})
    dates = sorted(r.get("finishedAt", "") for r in runs)

    h: list[str] = []
    md: list[str] = []
    title = f"Aetherium benchmark · {meta.get('label', '')}".strip(" ·")
    h.append(f"<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
             f"<title>{esc(title)}</title><style>{CSS}</style></head><body><main>")
    h.append(f"<h1>{esc(title)}</h1><p class='lede'>{len(runs)} runs across {len(by_scenario)} scenario(s), "
             f"{esc(dates[0][:16].replace('T', ' '))} to {esc(dates[-1][:16].replace('T', ' '))} UTC.</p>")
    h.append("<div class='panel meta'>")
    for k, v in [("CPU", f"{machine.get('cpu', '?')} ({machine.get('cores', '?')}C/{machine.get('threads', '?')}T)"),
                 ("JVM sees", f"{jvm.get('availableProcessors', '?')} processors, {jvm.get('maxHeapMB', '?')} MB heap"),
                 ("GPU", f"{device.get('name', '?')}"),
                 ("Driver", device.get("driver", "?")),
                 ("Minecraft", f"{meta.get('minecraft', '?')} · {meta.get('loader', '?')} {meta.get('loaderVersion', '')}"),
                 ("Aetherium", meta.get("modVersion", "?")),
                 ("Resolution", f"{opts.get('framebufferWidth', '?')}×{opts.get('framebufferHeight', '?')} · preset {opts.get('graphicsPreset', '?')}"),
                 ("Terrain MDI", str(device.get("terrainMultiDrawIndirect", "?"))),
                 ("OS", f"{machine.get('os', meta.get('os', '?'))} · power: {machine.get('power', '?')}"),
                 ("RAM", f"{machine.get('ramGB', '?')} GB")]:
        h.append(f"<div><b>{esc(k)}</b>{esc(v)}</div>")
    h.append("</div>")
    if problems:
        h.append("<div class='panel warn'><b>Skipped files</b><ul>" + "".join(f"<li>{esc(p)}</li>" for p in problems) + "</ul></div>")

    md.append(f"## {title}\n")
    md.append(f"{machine.get('cpu', '?')} · {device.get('name', '?')} · {device.get('backend', '?')} · "
              f"{opts.get('framebufferWidth', '?')}x{opts.get('framebufferHeight', '?')} · MC {meta.get('minecraft', '?')}\n")

    for sid, groups in by_scenario.items():
        scen = next(iter(groups.values()))[0]["scenario"]
        labels = list(groups)
        base_label = next((g for g in labels if g.startswith(baseline_hint)), labels[0])
        metrics = {g: [run_metrics(r) for r in rs] for g, rs in groups.items()}
        agg = {g: {k: aggregate([m.get(k) for m in ms]) for k, *_ in METRICS} for g, ms in metrics.items()}
        bound = {g: [m.get("gpu_bound") for m in ms if "gpu_bound" in m] for g, ms in metrics.items()}

        h.append(f"<h2>{esc(sid)}<span class='tag'>RD {scen.get('renderDistance', '?')}</span>"
                 f"<span class='tag'>{esc(scen.get('start', 'settled'))}</span></h2>")
        h.append(f"<p class='lede'>{esc(scen.get('description', ''))}</p>")
        h.append("<div class='legend'>" + "".join(
            f"<span style='--c:{colors[g]}'>{esc(g)} <span class='muted'>(n={len(groups[g])}"
            f"{', GPU-bound' if bound[g] and all(bound[g]) else (', CPU-bound' if bound[g] and not any(bound[g]) else '')})</span></span>"
            for g in labels) + "</div>")

        # Summary table: rows = metrics, columns = groups (+ delta vs baseline)
        h.append("<div class='panel scroll'><table><thead><tr><th>Metric</th>")
        for g in labels:
            h.append(f"<th class='num'>{esc(g)}</th>")
            if g != base_label:
                h.append("<th class='num'>Δ</th>")
        h.append("</tr></thead><tbody>")
        md.append(f"\n### {sid} (RD {scen.get('renderDistance', '?')})\n")
        md.append("| " + " | ".join(["Metric"] + labels + [f"Δ {g}" for g in labels if g != base_label]) + " |")
        md.append("|---|" + "---:|" * (len(labels) + len(labels) - 1))
        for key, label, unit, higher, d in METRICS:
            if all(agg[g][key] is None for g in labels):
                continue
            h.append(f"<tr><td>{esc(label)} <span class='muted'>{esc(unit)}</span></td>")
            row_md = [f"{label} {unit}".strip()]
            deltas_md = []
            for g in labels:
                a = agg[g][key]
                if a is None:
                    h.append("<td class='num muted'>–</td>")
                    row_md.append("–")
                else:
                    sd = f"<div class='sd'>±{fmt(a['sd'], d)} ({a['cv']:.1f}%)</div>" if a["n"] > 1 else ""
                    h.append(f"<td class='num'>{fmt(a['mean'], d)}{sd}</td>")
                    row_md.append(fmt(a["mean"], d) + (f" ±{fmt(a['sd'], d)}" if a["n"] > 1 else ""))
                if g != base_label:
                    cell = delta_cell(agg[base_label][key], a, higher, d)
                    h.append(cell)
                    b = agg[base_label][key]
                    deltas_md.append(f"{(a['mean'] - b['mean']) / abs(b['mean']) * 100:+.1f}%" if a and b and b["mean"] else "–")
            h.append("</tr>")
            md.append("| " + " | ".join(row_md + deltas_md) + " |")
        h.append("</tbody></table></div>")

        # Charts: first run of each group (runs are interchangeable repeats)
        series = []
        for g in labels:
            r = groups[g][0]
            series.append((g, colors[g], [v / 1000.0 for v in r["frames"]["interval"][1:] if v > 0]))
        h.append("<h3>Frame time over the run (line: median per slice, band: slowest frame per slice)</h3>")
        h.append(f"<div class='panel'>{svg_frametime(series)}</div>")
        pass_groups = []
        for g in labels:
            acc: dict[str, list[float]] = {}
            for r in groups[g]:
                for name, p in r.get("passes", {}).items():
                    gpu = [v / 1000.0 for v in p.get("gpu", []) if v >= 0]
                    if gpu:
                        acc.setdefault(name, []).append(statistics.fmean(gpu))
            pass_groups.append((g, colors[g], {n: statistics.fmean(v) for n, v in acc.items()}))
        h.append("<div class='grid2'>")
        h.append(f"<div><h3>Frame time percentiles</h3><div class='panel'>{svg_percentiles(series)}</div></div>")
        if any(p for _, _, p in pass_groups):
            h.append(f"<div><h3>GPU time per frame-graph pass (mean)</h3><div class='panel'>{svg_passes(pass_groups)}</div></div>")
        h.append("</div>")

        tl_series = {"compileQueue": [], "sectionsCompiled": [], "loadedChunks": [], "serverTickMs": []}
        for g in labels:
            tl = groups[g][0].get("timeline", [])
            for key in tl_series:
                pts = [(s["t"], s[key]) for s in tl if key in s]
                if pts:
                    tl_series[key].append((g, colors[g], pts))
        titles = {"compileQueue": "Compile queue (sections)", "sectionsCompiled": "Sections built per second",
                  "loadedChunks": "Loaded chunks", "serverTickMs": "Integrated server MSPT"}
        charts = [(titles[k], svg_timeline(v, titles[k])) for k, v in tl_series.items() if v]
        if charts:
            h.append("<h3>Timeline from world open (includes settle and warm-up)</h3><div class='grid2'>")
            for t, svg in charts:
                h.append(f"<div class='panel'>{svg}</div>")
            h.append("</div>")

    h.append("<h2>Definitions</h2><div class='panel'><dl class='defs'>")
    for k, v in [
        ("Avg FPS", "frames in the measured window divided by its wall time (first frame excluded)."),
        ("1% / 0.1% low", "1000 / mean of the slowest 1% (0.1%) frame intervals."),
        ("Frame pN", "nearest-rank percentile of the frame interval (end of one frame to the end of the next)."),
        ("Stutters", "frames longer than max(2.5 × median, median + 10 ms), per minute."),
        ("CPU frame", "vanilla's frame-time figure: render-thread time from frame start to the swapchain blit (excludes present and the limiter)."),
        ("GPU frame", "GPU timestamp span of the frame; 'GPU-bound' means GPU p50 ≥ 85% of the frame p50."),
        ("GPU per pass", "timestamps around every frame-graph pass (main, sky, clouds, weather, post, ...)."),
        ("Sections built", "section compile tasks completed per second during the measured window (worker threads)."),
        ("Mesh worker time", "summed worker-thread time spent in section compiles, per second of wall time."),
        ("Light publish", "producing the reader-visible light section map after a light batch (block + sky, client + integrated server); vanilla clones the whole map, light.no_map_clone shares unchanged shards."),
        ("Time to settled", "from world open until the compile queue and loaded-chunk count stayed quiet for 3 s."),
        ("Heap / Allocation / GC", "1 Hz JVM samples inside the measured window, from a sampler thread."),
        ("± and %", "standard deviation across repeats and its coefficient of variation; deltas within 2σ of the combined noise are faded."),
    ]:
        h.append(f"<dt>{esc(k)}</dt><dd>{esc(v)}</dd>")
    h.append("</dl></div>")
    h.append(f"<p class='lede'>Generated {datetime.now().strftime('%Y-%m-%d %H:%M')} by benchmarks/tools/report.py.</p>")
    h.append("</main></body></html>")
    return "".join(h), "\n".join(md) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="result directories or JSON files")
    ap.add_argument("-o", "--output", required=True, help="HTML file to write")
    ap.add_argument("--baseline", default="baseline", help="group label prefix used as the reference")
    ap.add_argument("--markdown", help="also write the summary tables as Markdown")
    args = ap.parse_args()

    runs, problems = load_results(args.inputs)
    for p in problems:
        print("warning:", p, file=sys.stderr)
    if not runs:
        print("no measured runs found", file=sys.stderr)
        return 1
    page, md = build_page(runs, problems, args.baseline)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8")
    md_path = Path(args.markdown) if args.markdown else out.with_suffix(".md")
    md_path.write_text(md, encoding="utf-8")
    print(f"wrote {out} and {md_path} ({len(runs)} runs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
