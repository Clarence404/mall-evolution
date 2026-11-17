# perf：Gatling 压测

两类场景，都不在 CI 里执行：`./mvnw verify` 只编译 Gatling 场景，不运行（`ha` 包里的辅助类有单元测试，随 verify 跑）。
想让本地监控面板一直有数据，用的是另一个模块 `simulator`（速率低、长期运行、行为像真人），见根目录 README「流量模拟器」。

| 场景 | 用途 | 在哪跑 |
|------|------|-------|
| `PromoSimulation` | 大促前的下单压测（目标 7500 QPS） | 同构压测环境 |
| `ha.SlowDownstreamSimulation` 等 5 个 | 高可用实验：下游变慢、重试风暴与幂等、滚动发布、突发流量 | 本机 + 开发环境的中间件，由 `experiments/` 下的脚本起服务、注入故障、收集数据 |

## 大促压测

```bash
./mvnw -pl perf gatling:test -DbaseUrl=https://<压测环境网关>
```

压测前要准备的数据（`src/test/resources/`）：

| 文件 | 说明 |
|------|------|
| `users.csv` | 仓库里只有表头。压测前由准备脚本为压测环境的 20 万个账号签发访问令牌并写入；令牌只对压测环境有效，不提交到仓库 |
| `skus.csv` | 换成本次活动的 20 个爆款 SKU |
| `place-order.json` | 下单请求体模板 |

压测结论（连接池、超时、分桶数等参数的来历）写在对应文章「稳定性」里，代码里的相关配置旁边有注释。

## 高可用实验

一般不直接跑，用 `experiments/<实验>/run.ps1`（它负责起服务、补库存、开采样、传参数、收尾），方案与参数说明见 `experiments/README.md`。
单独调试某个场景时（四个服务已经按 `dev,local,demo` 起好）：

```bash
./mvnw -pl perf gatling:test \
  -Dgatling.simulationClass=io.github.clarence404.mall.perf.ha.SlowDownstreamSimulation \
  -Dha.jwtSecret=<application-dev.yml 里的本地占位密钥> -Dha.durationSec=60
```

| 类 | 作用 |
|----|------|
| `SlowDownstreamSimulation` | 实验 A：下单 + 商品详情，定时给库存服务 reserve 注入延迟 |
| `RetryStormSimulation` | 实验 B1：先下单再轮询支付状态，给支付查询注入容量瓶颈与 20 秒的小故障；App 端重试策略可配 |
| `RetryIdempotencySimulation` | 实验 B2：下单响应迟到 + App 超时重试，核对每个下单意图生成了几单 |
| `RollingRestartSimulation` | 实验 C：稳定的三路流量，重启由脚本做 |
| `OverloadSimulation` | 实验 D：下单 + 匿名浏览，阶梯找容量或突发高峰，可加容量模型、定时开降级 |
| `ExperimentTokens` | 用 dev 的本地占位密钥直接签实验用户的访问令牌（不走登录，用户不在 `t_user` 里） |
| `ClientRetryPolicy`、`RetryBudget` | 模拟 App 的重试：不重试 / 立刻重试 / 指数退避 + 全抖动 + Retry-After + 重试预算 |
| `Timeline` | 逐秒记录每种请求的状态码与耗时分位，写 `timeline.csv`、`events.csv`、`params.csv` |
| `HaChains` | 共用片段：HTTP 协议（共用连接池）、带计时与重试的请求、故障注入、下单请求体 |

参数一律是 `-Dha.xxx`，每个场景类的注释里有完整列表；结果目录用 `-Dha.out` 指定，不指定时在 `perf/target/ha-results/` 下。
