# -*- coding: utf-8 -*-
"""Summarize HA experiment runs from timeline.csv: per phase x request x status counts and latency.
Usage: python ha_analyze.py <experiment> <phases e.g. 10-60,60-240,240-360> [variant-filter]
"""
import csv, glob, os, sys, statistics
from collections import defaultdict

from ha_lib import OUT


def load(run):
    rows = []
    with open(os.path.join(run, "timeline.csv"), encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            rows.append((int(r["t_s"]), r["request"], r["status"], int(r["count"]),
                         float(r["p50_ms"]), float(r["p95_ms"]), float(r["p99_ms"]), float(r["max_ms"])))
    return rows


def summarize(rows, a, b):
    agg = defaultdict(lambda: {"n": 0, "w50": 0.0, "p99s": [], "max": 0.0})
    for t, req, st, n, p50, p95, p99, mx in rows:
        if a <= t < b:
            g = agg[(req, st)]
            g["n"] += n; g["w50"] += p50 * n; g["p99s"].append(p99); g["max"] = max(g["max"], mx)
    return agg


def main():
    exp, phases = sys.argv[1], [tuple(map(int, p.split("-"))) for p in sys.argv[2].split(",")]
    flt = sys.argv[3] if len(sys.argv) > 3 else ""
    for run in sorted(glob.glob(os.path.join(OUT, exp, "*"))):
        name = os.path.basename(run)
        if flt and flt not in name: continue
        if not os.path.exists(os.path.join(run, "timeline.csv")): continue
        mark = " [CONTAMINATED]" if os.path.exists(os.path.join(run, "CONTAMINATED.txt")) else ""
        print(f"\n===== {name}{mark}")
        rows = load(run)
        for a, b in phases:
            agg = summarize(rows, a, b)
            tot = defaultdict(int)
            for (req, st), g in agg.items(): tot[req] += g["n"]
            print(f"  -- {a}-{b}s")
            for (req, st), g in sorted(agg.items()):
                print(f"     {req:38s} {st:>4s} n={g['n']:6d} ({100*g['n']/tot[req]:5.1f}%) "
                      f"p50w={g['w50']/g['n']:7.0f} p99med={statistics.median(g['p99s']):7.0f} p99max={max(g['p99s']):7.0f} max={g['max']:7.0f}")


main()
