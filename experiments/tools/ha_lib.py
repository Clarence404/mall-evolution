# -*- coding: utf-8 -*-
import csv, glob, os, re
from collections import defaultdict

# 结果目录：默认是本文件上一级的 out/，可用环境变量 HA_OUT 指到别处
OUT = os.environ.get("HA_OUT") or os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out")


def runs(exp, variant=None, include_bad=False):
    res = []
    for d in sorted(glob.glob(os.path.join(OUT, exp, "*"))):
        if not os.path.exists(os.path.join(d, "timeline.csv")): continue
        bad = os.path.exists(os.path.join(d, "CONTAMINATED.txt"))
        if bad and not include_bad: continue
        v = re.sub(r"^\d+-\d+-", "", os.path.basename(d))
        if variant and v != variant: continue
        res.append((os.path.basename(d), v, d, bad))
    return res


def timeline(run):
    rows = []
    with open(os.path.join(run, "timeline.csv"), encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if r["request"] == "request": continue
            rows.append(dict(t=int(r["t_s"]), es=int(r["epoch_s"]), req=r["request"], st=r["status"], n=int(r["count"]),
                             p50=float(r["p50_ms"]), p95=float(r["p95_ms"]), p99=float(r["p99_ms"]), mx=float(r["max_ms"])))
    return rows


def events(run, name="events.csv"):
    p = os.path.join(run, name)
    if not os.path.exists(p): return []
    with open(p, encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def started_ms(run):
    return int(open(os.path.join(run, "started.txt")).read().strip())


def metrics(run, pattern):
    """yield (epoch_ms, target, series, value) for series matching regex"""
    rx = re.compile(pattern)
    out = []
    with open(os.path.join(run, "metrics.csv"), encoding="utf-8-sig") as f:
        rd = csv.reader(f); next(rd)
        for row in rd:
            if len(row) < 4: continue
            if rx.search(row[2]):
                try: out.append((int(row[0]), row[1], row[2], float(row[3])))
                except ValueError: pass
    return out


def per_second(rows, req, ok=lambda st: st.startswith("2")):
    """t -> (ok, total, p99 of ok rows max)"""
    d = defaultdict(lambda: [0, 0, 0.0])
    for r in rows:
        if r["req"] != req: continue
        x = d[r["t"]]
        x[1] += r["n"]
        if ok(r["st"]):
            x[0] += r["n"]; x[2] = max(x[2], r["p99"])
    return d
