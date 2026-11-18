# -*- coding: utf-8 -*-
"""B1 retry storm: per-run summary + 10s buckets."""
import sys
from collections import defaultdict
from ha_lib import *

FIRST = "GET /api/orders/{orderNo}/payment"
RETRY = FIRST + " (重试)"

for name, v, d, bad in runs("b-retry-storm", include_bad=True):
    if not v.startswith("b1"): continue
    rows = timeline(d)
    ev = events(d)
    t_fault = next(int(e["t_s"]) for e in ev if "小故障开始" in e["event"])
    t_end = next(int(e["t_s"]) for e in ev if "小故障结束" in e["event"])
    t_stop = next(int(e["t_s"]) for e in ev if "撤销瓶颈" in e["event"])
    budget = next((e["event"] for e in ev if "重试预算" in e["event"]), "")
    ps = per_second(rows, FIRST, ok=lambda s: s == "200")
    first = sum(r["n"] for r in rows if r["req"] == FIRST)
    retry = sum(r["n"] for r in rows if r["req"] == RETRY)
    print(f"\n===== {name}{' [X]' if bad else ''}  fault {t_fault}-{t_end}s, stop {t_stop}s")
    print(f"  首次 {first}，重试 {retry}，App 层放大 {(first+retry)/first:.2f}；{budget}")
    # status mix of first queries per phase
    for a, b, label in ((20, t_fault, "故障前"), (t_fault, t_end, "故障中"), (t_end, t_end + 30, "结束后 0-30s"), (t_end + 30, t_stop, "结束后 30s-末")):
        mix = defaultdict(int); rt = 0
        for r in rows:
            if a <= r["t"] < b:
                if r["req"] == FIRST: mix[r["st"]] += r["n"]
                if r["req"] == RETRY: rt += r["n"]
        tot = sum(mix.values()) or 1
        print(f"  {label:14s} 首次 {tot:6d}: " + " ".join(f"{k}={100*n/tot:.1f}%" for k, n in sorted(mix.items())) + f" | 重试 {rt}（{rt/tot:.2f} 倍）")
    # recovery: first second t>=t_end where success >=95% for 10 consecutive seconds
    rec = None
    for t in range(t_end, t_stop - 10):
        if all(ps[x][1] > 0 and ps[x][0] / ps[x][1] >= 0.95 for x in range(t, t + 10)):
            rec = t - t_end; break
    print(f"  恢复时间（成功率≥95% 持续 10 秒）：{rec if rec is not None else '压测结束前没恢复'} 秒")
    # server-side: payment-service passed requests vs mall-app first attempts; waiting peak
    w = metrics(d, r'^demo_fault_waiting\{.*b-pay-capacity')
    s0 = started_ms(d)
    wmax = max(w, key=lambda x: x[3]) if w else None
    if wmax: print(f"  排队峰值 {wmax[3]:.0f}（第 {(wmax[0]-s0)/1000:.0f} 秒）；压测末排队 {[x[3] for x in w if (x[0]-s0)/1000 <= t_stop][-1]:.0f}")
    tb = metrics(d, r'^tomcat_threads_busy_threads\{application=""?payment-service')
    if tb: print(f"  支付服务 Tomcat 忙线程峰值 {max(x[3] for x in tb):.0f}")
    def cum(pattern, a, b):
        m = metrics(d, pattern)
        by = defaultdict(list)
        for ms, tg, s, val in m: by[s].append(((ms - s0) / 1000, val))
        tot = 0
        for s, pts in by.items():
            va = [v for t, v in pts if t <= a]; vb = [v for t, v in pts if t <= b]
            if vb: tot += vb[-1] - (va[-1] if va else 0)
        return tot
    for a, b, label in ((20, t_fault, "故障前"), (t_fault, t_end, "故障中"), (t_end, t_stop, "结束后")):
        pay = cum(r'^demo_fault_requests_total\{.*b-pay-capacity', a, b)
        app = cum(r'^http_server_requests_seconds_count\{application=""?mall-app.*uri=""?/api/orders/\{orderNo\}/payment', a, b)
        rf = cum(r'^remote_retryable_failures_total\{.*PaymentQueryClient', a, b)
        ex = cum(r'^remote_retryable_exhausted_total\{.*PaymentQueryClient', a, b)
        np = cum(r'^resilience4j_circuitbreaker_not_permitted_calls_total\{.*payment-query', a, b)
        fq = sum(r["n"] for r in rows if a <= r["t"] < b and r["req"] == FIRST)
        print(f"  {label}: App 首次 {fq}，mall-app 收到 {app:.0f}，支付服务收到 {pay:.0f}（服务端放大 {pay/app if app else 0:.2f}，总放大 {pay/fq if fq else 0:.2f}）；失败的尝试 {rf:.0f}，重试用完 {ex:.0f}，熔断拒绝 {np:.0f}")
    if len(sys.argv) > 1:
        print("  t(10s)  成功率  首次  重试  排队")
        wq = defaultdict(float)
        for ms, tg, s, val in w: wq[int((ms - s0) / 10000)] = max(wq[int((ms - s0) / 10000)], val)
        for b10 in range(0, t_stop // 10 + 1):
            ok = sum(ps[t][0] for t in range(b10 * 10, b10 * 10 + 10)); tot = sum(ps[t][1] for t in range(b10 * 10, b10 * 10 + 10))
            rt = sum(r["n"] for r in rows if r["req"] == RETRY and b10 * 10 <= r["t"] < b10 * 10 + 10)
            if tot: print(f"  {b10*10:4d}   {100*ok/tot:5.1f}%  {tot:5d} {rt:5d} {wq[b10]:6.0f}")
