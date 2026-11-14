"""Contrast gate for the shared appearance palette: every accent x surface x mode must pass."""
import json
import sys

from color import contrast

p = json.load(open(sys.argv[1]))
fails = []
worst = {}


def need(label, a, b, minimum):
    c = contrast(a, b)
    worst[label] = min(worst.get(label, 99), c)
    if c < minimum:
        fails.append(f"{label} {c:.2f} < {minimum}: {a} on {b}")


for s in p["surfaces"]:
    for m in ("dark", "light"):
        t = s[m]
        for bg in ("background", "container", "containerHighest"):
            need(f"text/{bg}", t["text"], t[bg], 7)
            need(f"muted/{bg}", t["muted"], t[bg], 4.5)
        for bg in ("background", "container"):
            need(f"subtle/{bg}", t["subtle"], t[bg], 4.5)
            need(f"outline/{bg}", t["outline"], t[bg], 3)
            need(f"secondary/{bg}", t["secondary"], t[bg], 4.5)
        need("onSecondary/secondary", t["onSecondary"], t["secondary"], 4.5)
        need("onSecondaryContainer/secondaryContainer", t["onSecondaryContainer"], t["secondaryContainer"], 7)
        need("text/hover", t["text"], t["hover"], 7)
        for a in p["accents"]:
            x = a[m]
            for bg in ("background", "container", "containerHigh"):
                need(f"primary/{bg}", x["primary"], t[bg], 4.5)
            for bg in ("containerHighest", "hover"):
                need(f"primary/{bg}", x["primary"], t[bg], 3)
            need("onPrimary/primary", x["onPrimary"], x["primary"], 4.5)
            need("onPrimaryContainer/primaryContainer", x["onPrimaryContainer"], x["primaryContainer"], 7)
            need("primaryContainer/background(visible)", x["primaryContainer"], t["background"], 1.18)

combos = len(p["accents"]) * len(p["surfaces"]) * 2
print(f"{len(p['accents'])} accents x {len(p['surfaces'])} surfaces x 2 modes = {combos} combinations, {len(fails)} failures")
for k, v in sorted(worst.items()):
    print(f"  worst {k:45s} {v:5.2f}")
for f in fails[:40]:
    print("FAIL", f)
sys.exit(1 if fails else 0)
