# -*- coding: utf-8 -*-
"""C rolling restart: failures per restart and per stage, shutdown durations, requests after deregistration."""
import sys
from collections import defaultdict
from ha_lib import *

OKS = {"200", "201", "409"}
for name, v, d, bad in runs("c-rolling-restart", include_bad=True):
    rows = timeline(d); s0 = started_ms(d)
    se = [((int(e["epoch_ms"]) - s0) / 1000, e["source"], e["event"]) for e in events(d, "script-events.csv")]
    tot = defaultdict(int); fail = defaultdict(lambda: defaultdict(int))
    for r in rows:
        tot[r["req"]] += r["n"]
        if r["st"] not in OKS: fail[r["req"]][r["st"]] += r["n"]
    nf = sum(sum(x.values()) for x in fail.values()); nt = sum(tot.values())
    print(f"\n===== {name}{' [X]' if bad else ''}  总请求 {nt}，失败 {nf}（{100*nf/nt:.3f}%）")
    for req in tot:
        if fail[req]: print(f"    {req}: " + " ".join(f"{k}={n}" for k, n in sorted(fail[req].items())) + f" / {tot[req]}")
    apps = sorted({s for _, s, _ in se if s.startswith("mall-app")})
    # per-second failures
    fsec = defaultdict(lambda: defaultdict(int))
    for r in rows:
        if r["st"] not in OKS: fsec[r["t"]][r["st"]] += r["n"]
    for app in apps:
        ev = [(t, e) for t, s, e in se if s == app]
        def find(key):
            return next((t for t, e in ev if key in e), None)
        t_off = find("注销"); t_term = find("SIGTERM") or find("kill") or find("结束进程"); t_exit = find("已退出"); t_new = find("新进程已启动"); t_ready = find("就绪") or find("端口")
        marks = "; ".join(f"{t:.0f}s {e}" for t, e in ev)
        print(f"  [{app}] {marks}")
        t_begin = t_off if t_off is not None else (t_term if t_term is not None else ev[0][0])
        t_last = (t_ready if t_ready is not None else ev[-1][0])
        stages = []
        if t_off is not None and t_term is not None: stages.append(("注销→SIGTERM", t_off, t_term))
        if t_term is not None and t_exit is not None: stages.append(("停→进程退出", t_term, t_exit))
        if t_exit is not None and t_ready is not None: stages.append(("进程退出→就绪", t_exit, t_ready))
        stages.append(("就绪后 40 秒", t_last, t_last + 40))
        for label, a, b in stages:
            mix = defaultdict(int)
            for t in range(int(a), int(b) + 1):
                if a <= t < b or (int(a) == int(b) and t == int(a)):
                    for k, n in fsec[t].items(): mix[k] += n
            print(f"      {label:12s} {a:6.1f}→{b:6.1f}s（{b-a:5.1f}s）失败 {sum(mix.values()):4d} " + " ".join(f"{k}={n}" for k, n in sorted(mix.items())))
        # requests still arriving after deregistration / stop
        port = {"mall-app-1": "8080", "mall-app-2": "8180", "mall-app-3": "8280"}[app]
        m = [((ms - s0) / 1000, val) for ms, tg, s, val in metrics(d, r'^http_server_requests_seconds_count\{.*uri=""?/api/') if tg == app]
        by = defaultdict(float)
        for t, val in m: by[round(t)] += val
        ts = sorted(by)
        ref = t_off if t_off is not None else t_term
        if ref is not None and ts:
            last_inc = None; prev = None
            for t in ts:
                if t < ref - 2: prev = by[t]; continue
                if t_exit is not None and t > t_exit: break
                if prev is not None and by[t] > prev: last_inc = t
                prev = by[t]
            base = [by[t] for t in ts if t <= ref]
            endv = [by[t] for t in ts if (t_exit is None or t <= t_exit)]
            extra = (endv[-1] - base[-1]) if base and endv else 0
            print(f"      {'注销' if t_off is not None else '停'}后还收到 {extra:.0f} 个请求，最后一次计数增长在其后 {(last_inc - ref) if last_inc else 0:.0f} 秒")
    # failure seconds list
    fs = sorted(fsec)
    if fs:
        print("    失败出现的秒：" + " ".join(f"{t}({sum(fsec[t].values())})" for t in fs[:60]) + (" …" if len(fs) > 60 else ""))
