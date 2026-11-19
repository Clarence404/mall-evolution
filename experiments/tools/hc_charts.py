# -*- coding: utf-8 -*-
"""高并发实验（E 热点库存、H 连接池与实例数、F 多级缓存、G 异步削峰）的图，数据取 experiments/out/ 下的各轮结果。

用法：python hc_charts.py e h f1 f2 g        # 要画哪几张
环境变量：HA_OUT（结果目录，默认 ../out）、HA_ASSETS（图输出目录）、HC_SINCE（只用目录名不小于它的轮次，默认 20261118-1938）
同一变体的多轮取中位数；带 CONTAMINATED.txt 的轮次也参与（卡顿只有几秒，中位数不受影响），想剔除就先把目录挪走。
"""
import statistics, sys
from collections import defaultdict
from ha_lib import *
from ha_charts import chart

SINCE = os.environ.get("HC_SINCE", "20261118-1938")


def by_variant(exp):
    res = defaultdict(list)
    for name, v, d, bad in runs(exp, include_bad=True):
        if name >= SINCE: res[v].append(d)
    return res


def med(xs): return statistics.median(xs) if xs else 0.0


def chart_e():
    """到达速率逐级升高时，各变体每秒下单成功数（各轮同一级取中位数）"""
    rv = by_variant("e-hot-stock"); series = []
    for v, label, color in [("e0-single-row", "单行", "r"), ("e1-buckets-4", "4 个桶", "y"), ("e2-buckets-8", "8 个桶", "b"), ("e3-buckets-16", "16 个桶", "g")]:
        lv = defaultdict(list)
        for d in rv[v]:
            rows = timeline(d)
            for a, b, nm in levels(d):
                m = re.search(r"下单 (\d+)/s", nm)
                if m and "预热" not in nm: lv[int(m.group(1))].append(phase_stats(rows, a, b, r"^POST /api/orders")["okrate"])
        series.append((label, color, [(x, med(lv[x])) for x in sorted(lv)]))
    chart("exp-hot-stock-throughput.svg", "热点 SKU 的下单到达速率逐级升高时，每秒下单成功数", series, 450, 450, "成功 单/秒",
          xlabel="下单到达速率（单/秒，每级 90 秒）", xstep=50, yticks=9, guides=[((0, 0), (450, 450))])


def chart_h():
    """连接池 5–80：查订单列表与下单成功的吞吐，都换算成相对连接池 20 的百分比"""
    rv = by_variant("h-pool-sizing"); pools = [5, 10, 20, 40, 80]; q = {}; o = {}
    for p in pools:
        gs, ps = [], []
        for d in rv[f"h1-pool-{p}"]:
            rows = timeline(d)
            gs.append(phase_stats(rows, 40, 330, r"^GET /api/orders$")["okrate"]); ps.append(phase_stats(rows, 40, 330, r"^POST /api/orders$")["okrate"])
        q[p], o[p] = med(gs), med(ps)
    chart("exp-pool-sizing-throughput.svg", "mall-app 连接池从 5 加到 80：吞吐相对连接池 20 的比例", [
        ("查订单列表", "b", [(i, 100 * q[p] / q[20]) for i, p in enumerate(pools)]),
        ("下单成功", "r", [(i, 100 * o[p] / o[20]) for i, p in enumerate(pools)]),
    ], 4, 140, "相对 20 条 %", xlabel="mall-app 的数据库连接池大小（条）", xticks=[(i, str(p)) for i, p in enumerate(pools)], yticks=7, ymarks=[(100, "连接池 20（正式配置）")])


def chart_f1():
    """详情请求逐级升高：三种缓存配置下的 P99（各轮同一级取中位数）"""
    rv = by_variant("f-cache-layers"); series = []; xs = set()
    for v, label, color in [("f1-no-cache", "不用缓存", "r"), ("f1-redis-only", "只有 Redis", "y"), ("f1-two-level", "本机 + Redis", "g")]:
        lv = defaultdict(list)
        for d in rv[v]:
            rows = timeline(d)
            for a, b, nm in levels(d):
                m = re.search(r"详情 (\d+)/s", nm)
                if m and "预热" not in nm and int(m.group(1)) >= 2000:
                    lv[int(m.group(1))].append(phase_stats(rows, a, b, r"^GET /api/products/\{skuId\}$")["p99"])
        series.append((label, color, [(x, med(lv[x])) for x in sorted(lv)])); xs |= set(lv)
    if not xs: print("没有 2000/s 以上的 F1 轮次"); return
    ymax = max(y for _, _, pts in series for _, y in pts)
    ymax = 50 if ymax <= 50 else 100 if ymax <= 100 else 500 if ymax <= 500 else 3000
    chart("exp-cache-layers-p99.svg", "商品详情请求逐级升高时的 P99 耗时", series, max(xs), ymax, "P99 ms",
          xlabel="详情请求到达速率（次/秒，每级 60 秒）", xstep=1000, yticks=5)


def chart_f2():
    """击穿：热点 SKU 每 20 秒失效一次，逐秒最慢请求的耗时"""
    rv = by_variant("f-cache-layers"); series = []; ev = []
    for v, label, color in [("f2-breakdown-no-single-flight", "不单飞", "r"), ("f2-breakdown-single-flight", "单飞", "g")]:
        d = rv[v][-1]; mx = defaultdict(float)
        for r in timeline(d):
            if "预热" not in r["req"]: mx[r["t"]] = max(mx[r["t"]], r["mx"])
        series.append((label, color, [(t, mx[t]) for t in sorted(mx) if 30 <= t <= 210]))
        ev = [int(e["t_s"]) for e in events(d) if re.search(r"第 \d+ 次失效", e["event"])]
    chart("exp-cache-breakdown-max.svg", "热点商品的缓存每 20 秒失效一次：每秒最慢的详情请求耗时", series, 210, 800, "最慢 ms",
          events=[(ev[0], "缓存失效")] if ev else (), xstep=30, yticks=4)


def chart_g():
    """关单高峰前后，释放预占那个消费组的平均消费延迟（Prometheus 里 sum / count，5 秒一个点；直方图的分位数在 120 秒以上的桶太粗）"""
    rv = by_variant("g-async-peak"); series = []
    for v, label, color in [("g1-slow-2", "2 个线程（约 19 条/秒）", "r"), ("g2-slow-4", "4 个线程（约 37 条/秒）", "y"), ("g3-slow-8", "8 个线程（约 75 条/秒）", "g")]:
        ds = [d for d in rv[v] if not os.path.exists(os.path.join(d, "CONTAMINATED.txt"))] or rv[v]
        d = ds[-1]; t0 = started_ms(d) // 1000
        pts = sorted((t - t0, val) for t, s, val in prom(d, "consume_delay_avg") if "inventory-release-consumer" in s and 60 <= t - t0 <= 600)
        series.append((label, color, pts))
    chart("exp-async-peak-delay.svg", "下单高峰过后，释放预占的平均消费延迟", series, 600, 200, "延迟 秒",
          events=[(150, "关单高峰开始（60 条/秒）"), (210, ""), (390, "停止下单")], xstep=60, yticks=4)


if __name__ == "__main__":
    for n in sys.argv[1:]: globals()["chart_" + n]()
