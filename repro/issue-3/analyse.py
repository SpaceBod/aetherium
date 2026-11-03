"""Summarises an Aetherium diagnostics recording (aetherium-diagnostics/diag-*.txt).

    python repro/issue-3/analyse.py <diag file>

Checks, frame by frame: that frameCounter and framemod2 advance by one and alternate, that the camera uniforms follow
the camera, that the shadow matrix the shadow pass drew with equals the one the pack read, how the frame's work
(passes, submits, computes) varies, and how each pack image's readback changes from frame to frame.
"""
import re
import sys
from collections import Counter, defaultdict

path = sys.argv[1]
frames = {}
readback = defaultdict(dict)
header = []
for line in open(path, encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("frame "):
        parts = line.split(" ")
        n = int(parts[1])
        fields = defaultdict(list)
        for p in parts[2:]:
            if "=" in p:
                k, v = p.split("=", 1)
                fields[k].append(v)
        frames[n] = fields
    elif line.startswith("readback frame "):
        m = re.match(r"readback frame (-?\d+) img=(\S+) (.*)", line)
        readback[m.group(2)][int(m.group(1))] = dict(kv.split("=", 1) for kv in m.group(3).split(" ") if "=" in kv)
    else:
        header.append(line)

print("\n".join(h[:300] for h in header[:14]))
print(f"\n{len(frames)} frames")


def one(f, k):
    v = f.get(k)
    return v[0] if v else None


def uniform(f, name):
    u = one(f, "uniforms") or ""
    m = re.search(r"[{,]" + re.escape(name) + r":([^,}]+)", u)
    return m.group(1) if m else None


order = sorted(frames)
issues = Counter()
prev = None
for n in order:
    f = frames[n]
    fc = one(f, "frameCounter")
    fm2 = uniform(f, "framemod2")
    if fc is not None and fm2 is not None and int(float(fm2)) != int(fc) % 2:
        issues["framemod2 != frameCounter % 2"] += 1
    if prev is not None and fc is not None and one(prev, "frameCounter") is not None:
        step = int(fc) - int(one(prev, "frameCounter"))
        if step != 1:
            issues[f"frameCounter stepped by {step}"] += 1
        pc = one(f, "previousCameraPosition")
        if pc != one(prev, "cameraPosition"):
            issues["previousCameraPosition != last frame's cameraPosition"] += 1
    if one(f, "shadowDrawn") is not None and one(f, "shadowDrawn") != one(f, "shadowModelView"):
        issues["shadow pass drew with a different matrix than shadowModelView"] += 1
    if one(f, "camera") != one(f, "cameraPosition"):
        issues["cameraPosition uniform != camera (shifted or stale)"] += 1
    prev = f

print("\nconsistency:")
for k, v in issues.most_common() or [("none found", 0)]:
    print(f"  {k}: {v}")

print("\nwork per frame (value: frames):")
for key in ("prevPasses", "prevClears", "prevCopies", "prevSubmits", "casters"):
    c = Counter(one(frames[n], key) for n in order)
    print(f"  {key}: {dict(c.most_common(6))}")
cs = Counter(" ".join(frames[n].get("cs", [])) for n in order)
print(f"  computes: {dict(cs.most_common(4))}")

print("\nreadback (texel box at each image's centre; distinct values over the recording):")
for img, by_frame in readback.items():
    keys = [k for k in ("nonzero", "eq1", "gt1", "sum") if any(k in d for d in by_frame.values())]
    desc = []
    for k in keys:
        c = Counter(d.get(k) for d in by_frame.values())
        desc.append(f"{k}: {len(c)} distinct, most common {c.most_common(3)}")
    print(f"  {img} ({len(by_frame)} frames)")
    for d in desc:
        print(f"      {d}")

# Light-source voxels that drop out on single frames point at the voxel side; floodfill images that alternate at
# frame parity point at the ping-pong.
vox = readback.get("voxel_img")
if vox:
    gt = [int(vox[n]["gt1"]) for n in sorted(vox)]
    drops = sum(1 for i in range(1, len(gt)) if gt[i] < gt[i - 1] * 0.8)
    print(f"\nvoxel_img light sources (gt1): min {min(gt)} max {max(gt)}, sudden drops >20%: {drops}")
for img in ("floodfill_img", "floodfill_img_copy"):
    ff = readback.get(img)
    if ff:
        s = [sum(float(x) for x in ff[n]["sum"].split(",")[:3]) for n in sorted(ff)]
        even = [s[i] for i in range(0, len(s), 2)]
        odd = [s[i] for i in range(1, len(s), 2)]
        print(f"{img} light sum: min {min(s):.1f} max {max(s):.1f}, even frames avg {sum(even)/len(even):.1f}, odd avg {sum(odd)/len(odd):.1f}")
