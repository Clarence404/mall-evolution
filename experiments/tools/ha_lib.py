# -*- coding: utf-8 -*-
import csv, glob, os, re, statistics
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


def probe(run):
    """vm-probe.csv（run-batch.ps1 -VmSsh 时才有）: [(epoch_s, fsync_ms, cpu_busy_pct, cpu_iowait_pct)]"""
    p = os.path.join(run, "vm-probe.csv"); out = []
    if not os.path.exists(p): return out
    with open(p, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            try: out.append((int(r["epoch_s"]), int(r["fsync_ms"]), int(r["cpu_busy_pct"]), int(r["cpu_iowait_pct"])))
            except (ValueError, KeyError): pass
    return out


def levels(run):
    """events.csv 里相邻两个事件之间算一段: [(from_s, to_s, 事件名)]"""
    ev = events(run)
    return [(int(e["t_s"]), int(ev[i + 1]["t_s"]), e["event"]) for i, e in enumerate(ev) if i + 1 < len(ev)]


def mysql_delta(run):
    """vm-mysql-after.txt 减 vm-mysql-before.txt：本轮的行锁等待次数、提交数等"""
    def rd(n):
        d = {}; p = os.path.join(run, n)
        if not os.path.exists(p): return d
        for line in open(p, encoding="utf-8-sig"):
            a = line.split()
            if len(a) == 2 and a[1].lstrip("-").isdigit(): d[a[0]] = int(a[1])
        return d
    b, a = rd("vm-mysql-before.txt"), rd("vm-mysql-after.txt")
    return {k: a[k] - b.get(k, 0) for k in a}


def phase_stats(rows, a, b, req_rx):
    """[a, b) 秒内匹配 req_rx 的请求：到达与成功的速率、成功率、成功请求的 P50（按次数加权）与逐秒 P99 的中位数"""
    rx = re.compile(req_rx); st = defaultdict(int); p99 = []; w50 = 0.0; okn = 0
    for r in rows:
        if a <= r["t"] < b and rx.search(r["req"]):
            st[r["st"]] += r["n"]
            if r["st"].startswith("2"):
                p99.append(r["p99"]); w50 += r["p50"] * r["n"]; okn += r["n"]
    tot = sum(st.values())
    return dict(tot=tot, ok=okn, okpct=100 * okn / tot if tot else 0, rate=tot / (b - a), okrate=okn / (b - a),
                p50=w50 / okn if okn else 0, p99=statistics.median(p99) if p99 else 0, p99max=max(p99) if p99 else 0, st=dict(st))


def prom(run, name):
    """prometheus/<name>.csv（queries.txt 里的一行）: [(epoch_s, series, value)]"""
    p = os.path.join(run, "prometheus", name + ".csv")
    if not os.path.exists(p): return []
    with open(p, encoding="utf-8-sig") as f:
        return [(int(r["epoch_s"]), r["series"], float(r["value"])) for r in csv.DictReader(f) if r["value"] not in ("", "NaN")]
