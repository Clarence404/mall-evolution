# 高可用实验

给 Java Doc「高可用」模块的实验型实战文章准备的实验方案、脚本与参数。每个实验都按同一个套路做：**现象 → 用 mall-evolution 复现 → 看指标定位 → 加上手段再压一次 → 前后数据对比 → 链回原理篇**。文章里的每个数字都要从这里跑出来，不估算、不编造；跑之前的「预期」只是假设，以实测为准。

| 实验 | 现象 | 对照组 → 实验组 | 单轮时长 | 对应原理篇 |
|------|------|----------------|---------|-----------|
| [A 下游变慢](#实验-a下游变慢超时舱壁熔断各挡住什么) | 库存服务 reserve 变慢 5 秒，下单卡死，连不依赖库存的商品详情也跟着超时 | 无保护 → 超时 → 舱壁 → 熔断（→ 连接池） | 约 8 分钟 | 超时重试与隔离、熔断 |
| [B 重试风暴](#实验-b重试风暴与幂等) | 支付查询抖了 20 秒，故障结束后系统却恢复不过来；超时重试下出重复订单 | 不受控重试 → 退避 + 抖动 + 预算 + 熔断；换键重试 → 同键重试 | B1 约 7 分钟，B2 约 3 分钟 | 超时重试与隔离、熔断 |
| [C 滚动发布](#实验-c滚动发布丢不丢请求) | 持续压测中逐个重启 3 个 mall-app，每次重启都有一串 5xx | 裸停 → 优雅停机 → 先注销再停 →（缩短网关实例缓存） | 约 12 分钟 | 优雅上下线与变更、冗余与故障转移 |
| [D 突发流量](#实验-d突发流量与过载保护) | 浏览量突增到容量的 1.8 倍，下单跟着超时 | 不限流 → 入口按路由限流（→ 降级） | 约 8 分钟（D0 约 10 分钟） | 限流与过载保护、降级 |

## 一、已有的能力与这次补上的

实验只用仓库里已有的机制做「实验组」，缺的只补观测和注入手段，不为实验改正式默认值。

| 能力 | 在哪 | 实验里怎么用 |
|------|------|-------------|
| 超时（连接 200ms、读 500ms，网关 3 秒） | `application.yml` 的 `spring.http.serviceclient.*`、网关 `response-timeout` | A 的自变量 |
| 熔断 + 信号量舱壁（`inventory-reserve`、`payment-query`、`inventory-stock-query`） | `InventoryReserveClient`、`PaymentQueryClient`、`StockQueryService`，参数在 `application.yml` | A、B 的自变量 |
| 只读查询重试 1 次 + 随机退避，写接口不重试 | `@Retryable`（Spring Framework 7） | B 的自变量 |
| 下单幂等（`Idempotency-Key` + `uk_user_biz`） | `PlaceOrderService` | B2 |
| 网关按「用户 + 路由」限流（Redis） | `RateLimitConfig`、路由的 `RequestRateLimiter` | 各实验照常生效；实验用户很多，不会被它挡 |
| 降级开关（猜你喜欢、积分、订单列表） | `DegradeProperties`，线上改 Nacos | D3 / D4 |
| 优雅停机、就绪后才注册 Nacos、`/internal/offline` 主动注销 | `server.shutdown: graceful`、`RegisterWhenReady`、`OfflineController`、k8s 清单的 preStop | C 的自变量 |
| 库存服务 reserve 延迟开关（演示控制台用） | `inventory-service` 的 `/internal/demo/fault` | 不用，改用下面的通用故障注入 |
| 补货接口 | `PUT /internal/demo/stocks/{skuId}/restock` | 每轮开始前把库存补到 10 万 |
| Gatling 压测模块、流量模拟器 | `perf/`、`simulator/` | 场景放在 `perf/.../perf/ha/` |
| Prometheus 规则、两个 Grafana 面板 | `deploy/observability/` | 熔断告警、下单 SLO 照常可看 |

这次补上的（都只在 demo profile 下注册，prod 与 demo 同时激活启动即失败，见 `ProdDemoProfileGuard`）：

| 新增 | 说明 |
|------|------|
| 通用故障注入 `/internal/demo/faults/{name}` | mall-common，三个服务都有。按方法 + 路径命中，可组合：处理前延迟、按比例失败、**并发上限（排队，模拟容量有限的瓶颈）**、处理后延迟（服务端成功但响应迟到）、延迟只对一定比例生效；每条规则自带有效期（默认 10 分钟），同名且并发上限不变时原地更新（排队的积压不会凭空消失）。指标 `demo_fault_requests_total`、`demo_fault_waiting`、`demo_fault_inflight` |
| 模拟 SIGTERM `POST /internal/demo/lifecycle/exit` | Windows 上没有 SIGTERM，`Stop-Process` 等同 kill -9。这个接口让 JVM 正常退出、执行关闭钩子，和 Linux 上 `kill <pid>` 走同一条路径 |
| 直接拨降级开关 `GET/PUT /internal/demo/degrade` | 不经 Nacos，改动只在内存里（Nacos 一推送就被重新绑定回去） |
| 重试次数指标 | `remote_retryable_failures_total{method,exception}`（每次失败的尝试）、`remote_retryable_exhausted_total{method}`（重试用完放弃）。**正式代码**，平时也有 |
| 只读查询的重试参数可配 | `mall.remote.payment-query.retry.*`、`mall.remote.inventory-stock-query.retry.*`（次数、基础退避、抖动、倍数），默认值不变 |
| 服务间 HTTP 连接池开关 | `mall.http-client.max-connections-per-route` / `max-connections-total` / `connection-request-timeout`，**不配时行为不变**。见下面「先说一个已经确认的问题」 |
| 网关按路由的总量限流 | `mall.gateway.route-limits.<路由ID>.permits-per-second` / `burst`，单机令牌桶，超出返回 429 + `Retry-After: 1` + `X-RateLimit-Scope: route`，指标 `gateway_route_limited_total{route}`。**默认一条都不配** |
| Gatling 场景与辅助类 | `perf/src/test/java/.../perf/ha/`：5 个场景、实验令牌、客户端重试策略与重试预算、逐秒统计 |
| 高可用实验面板 | `deploy/observability/grafana/ha-experiments-dashboard.json`（uid `mall-ha-experiments`） |
| 运行脚本 | 本目录：每个实验一个 `run.ps1` + 若干变体参数文件，公共函数在 `lib/HaExperiment.psm1` |

还缺、但这次没做的：Tomcat 线程池以外的连接池指标（HttpClient 5 的池子没有接 Micrometer，靠线程栈看）；断开 Redis / MySQL 这类中间件故障（中间件在共用的开发虚拟机上，不能动；要做需要本机起一个 TCP 代理来切断，留到以后）。

### 先说一个已经确认的问题：服务间调用只有 5 条连接

不用跑压测就能确认（`mall-common` 的 `HttpClientPoolConfigTest` 把它写成了测试）：

- `RestClient` 的底层是 Apache HttpClient 5——它是随 `nacos-client` 间接引进来的，Spring Boot 检测到它就优先用它
- Spring Boot 不改 HttpClient 5 的连接池：**每个目标地址（下游一个实例）最多 5 条连接、总共 25 条**，取连接的等待上限 **3 分钟**
- `spring.http.serviceclient.*.read-timeout` 管的是拿到连接之后的读超时，**管不到排队等连接这一段**
- 舱壁许可（下单 60、详情库存查询 20）都大于 5，挡不住「60 个线程排队等 5 条连接」

这意味着：下游一变慢，5 条连接很快被占满，后面的调用在池子里一等就是几分钟；详情页的库存提示和下单共用这个池子（同一个下游地址），也会被拖住。实验 A 实测了它的影响（A3 → A4），随后三个服务的 `application.yml` 都把连接池放开到每个下游实例 100 条、取连接最多等 100ms；A0–A4 的参数文件把修复前的值钉住，仍然可以复现。见「九、实验结论与随后的修改」。

## 二、环境与拓扑

| 位置 | 跑什么 |
|------|-------|
| 开发虚拟机（共用） | MySQL、Redis、Nacos、RocketMQ（`docker compose up -d` 那套）；监控 Prometheus（9091）、Grafana（3001），compose 项目 `mall-monitoring` |
| 本机（Windows） | 四个服务（脚本按 `dev,local,demo` 起）、Gatling 压测、逐秒采样 |

本机端口（脚本固定用这些，开跑前会检查没有被占用）：

| 服务 | 业务端口 | 管理端口 | 说明 |
|------|---------|---------|------|
| mall-app | 8080（C 另有 8180、8280） | 18080（18180、18280） | 雪花机器号 1、2、3 |
| inventory-service | 8082 | 18082 | |
| payment-service | 8083 | 18083 | |
| mall-gateway | 9000 | 19000 | 压测入口 |

几点约定：

- **本机资源**：每个服务最大堆 1GB，A / B / D 起 4 个 JVM、C 起 6 个，加上 Maven 与 Gatling 的 JVM，建议空闲内存 8GB 以上；压测期间本机不要跑别的重活

- **profile**：`dev,local,demo`，工作目录是仓库根目录，中间件地址来自 `config/application-local.yml`（不进仓库）。demo 的演示节奏里，下单熔断「3 次就起算」由 `common.properties` 拨回正式参数；「1 分钟未支付关单」保留（它会把实验订单占的库存及时还回去）
- **参数优先级**：实验参数全部以命令行参数 `--key=value` 传给服务，优先级最高，共用 Nacos 里有同名配置也盖不住。实验 A、B、C、D 各自把要对照的正式值也显式写了一遍
- **实验用户**：用 dev 环境的本地占位 JWT 密钥（`application-dev.yml` 里那串）直接签访问令牌，用户 ID 从 900000001 起（B2 按时间另取一段），不在 `t_user` 里。原因：演示账号只有 8 个，而网关下单限流是每人每秒 1 次、订单服务有「同一 SKU 待支付最多 2 单」的频控，几个账号压不出几十单每秒。只调下单、查订单、查支付状态这些只认令牌里用户 ID 的接口，不调「我的」
- **库存**：每轮开始前把 20001–20003 补到 10 万（只补不减），实验订单 1 分钟后被超时关单、库存归还
- **数据量**：A 一轮最多约 1.4 万单、C 约 6000 单、D 约 7000 单，全是待支付后被关单的订单，加上本地消息与预占记录，MySQL 每轮多几万行
- **Nacos 命名空间**：共用 Nacos 时，别人的 mall-app 实例会被网关当成自己的。建议给实验单独建一个命名空间，跑脚本时加 `-NacosNamespace <命名空间 ID>`

## 三、准备（一次性）

1. JDK 21：脚本用 `-JavaHome` 指定，或设环境变量 `MALL_JAVA_HOME`
2. 打包：`.\mvnw.cmd -B -DskipTests package`（脚本发现缺 jar 会自己打；改了代码后加 `-Build` 重打）
3. `config/application-local.yml` 指向开发虚拟机上的中间件（见根目录 README「在 IntelliJ IDEA 里运行」）
4. 可选，Prometheus：把 `observability/prometheus-ha.yml` 里的任务合并进监控机的 Prometheus（5 秒抓取、含 C 的三个 mall-app 实例），`targets-ha.json` 的地址改成本机地址；不配也能跑，只是没有 Grafana 截图和 `prometheus/` 导出，逐秒数据照样有
5. 可选，Grafana：导入 `deploy/observability/grafana/ha-experiments-dashboard.json`
6. 允许运行脚本：`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`（或每次用 `powershell -ExecutionPolicy Bypass -File ...`）

## 四、怎么跑

```powershell
# 在仓库根目录
.\experiments\a-slow-downstream\run.ps1 -Variant a0-no-protection -JavaHome C:\path\to\jdk-21
.\experiments\a-slow-downstream\run.ps1 -Variant a1-timeout -PrometheusUrl http://<监控机>:9091
# 临时改参数（不改文件）：节名:key=value，可以给多个
.\experiments\d-overload\run.ps1 -Variant d2-limit -Set "mall-gateway:mall.gateway.route-limits.anonymous.permits-per-second=360"
```

| 参数 | 说明 |
|------|------|
| `-Variant` | 变体名，即实验目录下的 `.properties` 文件名 |
| `-Set` | 临时覆盖，`节名:key=value`；节名是 `all`、服务名、`experiment`（Gatling 参数）、`script`（脚本开关） |
| `-PrometheusUrl` | 给了就在结束时按 `queries.txt` 导出曲线（5 秒步长） |
| `-NacosNamespace` | 给服务设 `NACOS_NAMESPACE` |
| `-Build` | 先重新打包 |
| `-KeepServices` | 跑完不停服务（排查用，记得手工停） |

一轮做的事：读参数（`common.properties` → 实验的 `common.properties` → 变体文件 → `-Set`）→ 起服务并等就绪 → 补库存 → 开逐秒采样 → 后台起 Gatling → 按实验需要在固定时刻抓线程栈或重启实例 → 等压测结束 → 导出 Prometheus → 撤销所有故障规则、停服务。中途出错或 Ctrl+C 也会尽量收尾；故障规则都有有效期，脚本没收尾也会自己失效。

每轮的结果在 `experiments/out/<实验>/<时间>-<变体>/`（不进仓库）：

| 文件 | 内容 |
|------|------|
| `variant.properties`、`run-info.txt` | 这一轮的完整参数、提交号、起止时间 |
| `gatling/` | Gatling HTML 报告：每个请求名的总数、OK / KO、P50 / P75 / P95 / P99、每秒请求与响应曲线 |
| `timeline.csv` | **逐秒**结果：`epoch_s,t_s,request,status,count,p50_ms,p95_ms,p99_ms,max_ms`，状态 0 = 没拿到响应（客户端超时、连接被拒）；重试的请求名带「(重试)」 |
| `events.csv` | 场景里的事件（注入、撤销、开降级），和 timeline 同一时间轴 |
| `script-events.csv` | 脚本的事件（抓线程栈、注销、SIGTERM、进程退出、新实例就绪），epoch 毫秒 |
| `params.csv` | Gatling 场景实际用到的参数（密钥打码） |
| `metrics.csv` | 每秒一次的服务指标采样：`epoch_ms,target,series,value`（计数器是累计值，相邻两行相减得速率；抓不到时记 `scrape_error`） |
| `threads-*.txt`、`threads-*.summary.csv` | 线程栈与分类计数：Tomcat 业务线程在等连接池、等下游响应、等本机缓存加载、等许可、sleep、空闲 |
| `prometheus/*.csv` | 给了 `-PrometheusUrl` 时导出的曲线 |
| `logs/` | 各服务的日志 |
| `intents.csv` | 只有 B2：每个下单意图实际生成了几单 |

## 五、四个实验

下面每个实验都写明：假设、前置条件、拓扑、压测、注入、对照组与实验组的差异、看哪些数据、预期、判定、截图。**「预期」是跑之前的假设**，文章以实测为准，和预期不符的地方恰恰值得写。

### 实验 A：下游变慢——超时、舱壁、熔断各挡住什么

- **假设**：库存服务变慢时，没有保护的 mall-app 会被拖垮，而且拖垮的不只是下单——商品详情不依赖 reserve，但它和下单共用 Tomcat 线程池和到库存服务的 HTTP 连接池，也会跟着超时。超时、舱壁、熔断每加一层，能看到一个具体的改善
- **前置条件**：开发虚拟机中间件可用；本机没有别的服务占端口
- **拓扑**：mall-app ×1、库存 ×1、支付 ×1、网关 ×1
- **压测**（`SlowDownstreamSimulation`）：开放模型，下单 40 单/秒 + 商品详情 100 次/秒，共 6 分钟，客户端超时 10 秒
- **注入**：第 60 秒给库存服务的 `POST /internal/inventory/reserve` 加 5000ms 延迟（规则 `a-reserve-slow`），持续 180 秒后撤销，再观察 2 分钟恢复
- **抓线程栈**：第 90、150 秒，mall-app 与库存服务各一份

| 变体 | 读超时（mall-app → 库存） | 网关超时 | 舱壁 | 熔断 | 连接池 |
|------|------|------|------|------|------|
| `a0-no-protection` | 60 秒 | 60 秒 | 10000（等于没有） | 关（最少 1 亿次才统计） | 默认（5 条 / 3 分钟） |
| `a1-timeout` | **500ms** | **3 秒** | 10000 | 关 | 默认 |
| `a2-bulkhead` | 500ms | 3 秒 | **60** | 关 | 默认 |
| `a3-circuit-breaker`（正式配置） | 500ms | 3 秒 | 60 | **开**（10 秒窗口、至少 100 次、失败率或慢调用率 ≥ 50% / 60%，打开 5 秒） | 默认 |
| `a4-pool`（视 A3 结果） | 500ms | 3 秒 | 60 | 开 | **每目标 100 条、取连接最多等 100ms** |
| `a3b-ignore-bulkhead`（验证修复） | 500ms | 3 秒 | 60 | 开，**忽略舱壁满的异常** | 默认 |
| `a5-fixed`（修复后的正式配置） | 500ms | 3 秒 | 60 | 开，忽略舱壁满的异常 | 每目标 100 条、取连接最多等 100ms |

A0–A4 的「默认连接池」「熔断器不忽略舱壁异常」是修复前的值，钉在 `common.properties` 里。

- **看哪些数据**：
  - 压测：`timeline.csv` 里「POST /api/orders」「GET /api/products/{skuId}」逐秒的状态码分布和 P99；Gatling 报告两个请求的 OK 比例、P99
  - mall-app：Tomcat 忙线程（`tomcat_threads_busy_threads`）、到库存的客户端调用耗时（`http_client_requests_seconds_max{uri=/internal/inventory/reserve}`）与库存服务端耗时之差（= 排队等连接的时间）、舱壁剩余许可、熔断状态与拒绝数
  - 线程栈分类：等连接池 / 等下游响应 / 等本机缓存加载 各多少
  - 面板 `mall-ha-experiments` 的「mall-app」「服务间调用的保护」两行
- **预期**：
  - A0：注入后几秒内 Tomcat 忙线程顶到 200，下单与详情都大面积超时；撤销后要等排队的请求慢慢消化才恢复
  - A1：读超时 500ms 让单次调用不再等 5 秒，但如果线程栈显示大量线程在「等连接池」，说明超时管不到这一段，改善有限
  - A2：舱壁把同时调库存的线程压在 60 个，Tomcat 还有余量，下单多出来的部分立刻返回 503 `SYSTEM_BUSY`；详情页是否恢复，取决于它的库存查询是否也卡在同一个连接池上
  - A3：熔断打开后下单在本进程内直接失败（毫秒级），库存服务收到的 reserve 降到半开探测的量；撤销后 5 秒内半开、恢复
  - A4：如果 A3 里详情页仍受影响，连接池开关应当让它和注入前一样
- **判定**：每组算三个数——注入期间详情页成功率与 P99、下单失败的平均耗时（失败得快不快）、撤销后多少秒恢复到注入前的 P99 两倍以内。以 A0 为基线对比
- **截图**：每组一张面板截图（「mall-app」与「服务间调用的保护」两行，时间范围覆盖注入前 30 秒到撤销后 60 秒）；A0、A3 各一张 Gatling 报告的响应时间分布；一份线程栈分类对比表（由 summary.csv 汇总）
- **预计耗时**：每组约 8 分钟（起服务 1 分钟 + 压测 6 分钟 + 收尾），4–5 组约 40 分钟

### 实验 B：重试风暴与幂等

#### B1 重试风暴：同一个 20 秒的小故障，重试策略决定能不能自己恢复

- **假设**：下游容量有限时，不受控的重试（立刻重试、多层叠加、不看 Retry-After、没有预算）会把一次 20 秒的小故障放大成「故障结束后也恢复不了」的亚稳态：排队的积压里大多是调用方早已放弃的请求，下游忙着做白工，新请求继续超时、继续重试。受控的重试（退避 + 抖动 + 预算 + 熔断）让积压在故障结束后很快消化
- **链路**：App 轮询支付状态 `GET /api/orders/{orderNo}/payment` → 网关 → mall-app（`PaymentQueryClient`：重试 + 熔断 + 舱壁）→ 支付服务 `GET /internal/payments/{orderNo}`
- **拓扑**：同 A
- **压测**（`RetryStormSimulation`）：先让 600 个实验用户各下一单（约 12 秒），之后每秒 150 次「首次查询」（不含重试），持续 5 分钟；App 超时 2 秒
- **注入**（规则 `b-pay-capacity`，轮询开始即生效）：支付查询并发上限 10、每个请求占用 40ms（容量约 250 次/秒，平时负载约六成）；轮询第 60 秒起把占用时间改成 400ms（容量掉到约 25 次/秒），持续 20 秒后改回 40ms。排队不设超时，调用方放弃的请求轮到时照样处理
- **两组都放开服务间连接池**（每目标 500 条、取连接最多等 200ms），免得 HttpClient 默认的 5 条连接先成为瓶颈

| 变体 | App 重试 | mall-app 重试 | 熔断 / 舱壁（payment-query） |
|------|---------|---------------|---------------------------|
| `b1-naive` | 立刻重试，最多 3 次，不看 Retry-After，不设预算 | 立刻重试 3 次 | 关 / 10000 |
| `b1-controlled` | 指数退避 + 全抖动（200ms 起、封顶 2 秒），遵守 Retry-After，预算 10% | 正式配置：重试 1 次、退避 20–80ms | 开（10 秒窗口、至少 50 次、失败率 50%，打开 10 秒）/ 20 |
| `b1-client-only`（可选） | 同 controlled | 同 naive | 同 naive |
| `b1-server-only`（可选） | 同 naive | 同 controlled | 同 controlled |

- **看哪些数据**：
  - 放大倍数：Gatling 里「(重试)」请求数 / 首次请求数（App 层）；支付服务收到的请求 / mall-app 收到的请求（服务端层，面板「支付查询放大倍数」）；两者相乘是总放大
  - 积压：`demo_fault_waiting{rule="b-pay-capacity"}`（瓶颈前排队数）、支付服务 Tomcat 忙线程
  - 恢复时间：小故障结束后，首次查询的成功率回到 95% 以上并保持 10 秒的那一刻，减去结束时刻
  - 重试计数：`remote_retryable_failures_total`、`remote_retryable_exhausted_total`；熔断拒绝数
  - 压测结束时日志里的「客户端重试预算」统计（首次请求数、放行与拒绝的重试数）
- **预期**：naive 组小故障期间排队数一路上涨、结束后不回落，成功率在压测剩下的时间里都起不来；controlled 组排队有限，故障结束后十几秒内恢复。两个可选组用来分清 App 端和服务端各贡献了多少
- **判定**：恢复时间（naive 可能是「压测结束前没恢复」）、故障期间与之后的总放大倍数、排队峰值
- **截图**：两组的「支付查询放大倍数」「注入的故障：排队与在途」面板；`timeline.csv` 画出的首次查询成功率曲线（两组叠在一张图上，标出故障开始与结束）
- **预计耗时**：每组约 7 分钟，2–4 组约 15–30 分钟

#### B2 超时重试必须配幂等

- **假设**：服务端已经下单成功、但响应迟到导致 App 超时，App 重试时如果换了新的 `Idempotency-Key`，就会重复下单；整个下单意图沿用同一个键，重试只会拿到第一次的结果
- **压测**（`RetryIdempotencySimulation`）：每秒 5 个新用户各有一次下单意图，持续 60 秒；App 超时 1.5 秒，超时立刻重试最多 3 次；结束后等 5 秒，查每个用户的订单列表数一共生成了几单
- **注入**（规则 `b2-order-late`，mall-app）：30% 的下单请求在业务处理完、订单已写库之后，响应再晚 2500ms 才发出

| 变体 | 重试时的 Idempotency-Key |
|------|------------------------|
| `b2-new-key` | 每次重试换新的 |
| `b2-same-key` | 整个意图同一个 |

- **看哪些数据**：`intents.csv`（「生成了几单、App 最后看到的状态码」→ 用户数），Gatling 里「POST /api/orders (重试)」的数量，响应头 `Idempotent-Replayed`
- **预期**：new-key 组约三成的意图生成 2 单（订单服务「同一 SKU 待支付最多 2 单」的频控把重复单封顶在 2，第 3 次重试会收到 409），same-key 组全部是 1 单
- **判定**：重复下单的意图占比
- **截图**：两组 `intents.csv` 的对比表即可
- **预计耗时**：每组约 3 分钟

### 实验 C：滚动发布丢不丢请求

- **假设**：逐个重启实例时，丢请求来自三处——在途请求被掐断（没有优雅停机）、实例还在注册中心里就已经不接请求（停之前没注销）、调用方的实例列表缓存还没过期（网关 LoadBalancer 默认缓存 35 秒）。优雅停机只解决第一处；先注销再停解决第二处；第三处要看缓存过期时间与注销后等待时间谁长
- **拓扑**：mall-app ×3（8080 / 8180 / 8280）、库存 ×1、支付 ×1、网关 ×1
- **压测**（`RollingRestartSimulation`）：商品详情 60 次/秒 + 下单 10 单/秒 + 查订单 10 次/秒，共 10 分钟，客户端不重试（要看发布本身丢了什么），超时 5 秒
- **注入**：第 60 秒起逐个重启三个实例，两次之间隔 60 秒；每一步写进 `script-events.csv`

| 变体 | 停 | 起 | 网关实例缓存 |
|------|----|----|------------|
| `c0-hard` | SIGTERM + `server.shutdown=immediate`，不先注销 | Web 服务器一起就注册 Nacos（不等就绪）；端口一监听就重启下一个 | 35 秒 |
| `c1-graceful` | SIGTERM + 优雅停机（正式配置），不先注销 | 就绪后才注册（正式配置）；等就绪 | 35 秒 |
| `c2-offline-first` | 先 `/internal/offline` 注销，等 15 秒，再 SIGTERM（同 k8s preStop） | 同 C1 | 35 秒 |
| `c3-short-lb-cache`（视 C2 结果） | 同 C2 | 同 C1 | **5 秒** |
| `c3b-longer-wait`（可选） | 注销后等 **40 秒** | 同 C1 | 35 秒 |
| `c4-crash`（可选，故障演练） | kill -9，5 秒后拉起 | 同 C1 | 35 秒 |
| `c5-fixed`（修复后的正式配置） | SIGTERM；停机一开始主动注销，等 10 秒后优雅停机，消费者并行关闭 | 同 C1 | **5 秒** |

C0、C1 用 `mall.lifecycle.deregister-on-shutdown=false` 复现修复前「停机时不主动注销」的行为。

- **看哪些数据**：
  - 每次重启丢了几个请求：`timeline.csv` 里非 2xx / 409 的请求（含状态 0），按 `script-events.csv` 的时间切成三段统计
  - 丢在哪一步：失败集中在「SIGTERM 到进程退出」之间（在途被掐断），还是「进程退出之后」（网关还往已经没了的实例上发，网关返回 5xx）
  - **Nacos 下线时序**（C2 起）：从「注销」那一刻起，这个实例的请求计数（`metrics.csv` 里该实例的 `http_server_requests_seconds_count`）还涨了多少秒——即网关还在往它身上发请求的时长
  - 面板「实例（滚动发布）」两行：各实例每秒请求、抓取状态；网关 5xx
- **预期**：C0 每次重启都有一串 5xx；C1 在途请求不再被掐断，但进程退出后到网关缓存过期前仍有失败；C2 若「注销后还收到请求的时长」超过 15 秒，说明 15 秒的等待不够，仍会丢少量请求——这时跑 C3（或 C3b）确认缩短缓存（或加长等待）后能做到零失败
- **判定**：每次重启的失败数（三次取平均）、注销后还收到请求的时长；零失败为通过
- **截图**：每组「各 mall-app 实例每秒请求」面板（能看到三个实例轮流掉到 0 再回来）叠加网关 5xx；C2 的「注销 → 最后一个请求」时间线（由 `metrics.csv` 画）
- **预计耗时**：每组约 12 分钟，4 组约 50 分钟

### 实验 D：突发流量与过载保护

- **假设**：流量超过容量时，不限流的结果是所有请求一起变慢、一起超时——包括核心的下单。在入口把超出容量的非核心流量（匿名浏览）拒掉，下单就能保持正常；降级只在被降的那部分真的是瓶颈时才有用
- **拓扑**：同 A
- **容量模型**（默认打开）：给 mall-app 的全部 `/api/**` 请求注入统一的处理能力上限（规则 `d-app-capacity`：并发 10、每个请求占用 20ms，约 500 次/秒），下单和浏览共用。这样拐点不受本机 CPU 和压测自身开销影响，换台机器也能复现；文章里要写明这是模型。想测本机真实容量用 `d0-probe-real`
- **压测**（`OverloadSimulation`）：下单始终 20 单/秒；匿名浏览 = 商品详情 70% + 猜你喜欢 30%，都走网关的 `anonymous` 路由（没有用户身份，按用户限流挡不住）；客户端超时 3 秒
  - D0（`d0-probe`）：浏览量从 100/s 起每分钟加 100，加到 800/s，找下单 P99 超过 800ms 或开始出错的拐点
  - D1–D4（burst）：平时浏览 150/s，第 60 秒起升到 900/s（约容量的 1.8 倍）持续 180 秒，再回落

| 变体 | 网关 anonymous 路由限流 | 降级（猜你喜欢改固定列表） |
|------|------------------------|--------------------------|
| `d1-none` | 无 | 无 |
| `d2-limit` | **240/s，突发 240**（压满后实测处理量约 316/s 的四分之三；贴着容量的 300/s 用 `-Set` 跑） | 无 |
| `d3-degrade`（可选） | 无 | 高峰开始 30 秒后打开 |
| `d4-limit-degrade` | 240/s | 高峰开始 30 秒后打开 |

- **看哪些数据**：下单的成功率与 P99（高峰前、高峰中、高峰后三段）；网关 429 与 `gateway_route_limited_total`；容量模型的排队数 `demo_fault_waiting{rule="d-app-capacity"}`；mall-app Tomcat 忙线程；浏览请求的成功率（被限流的部分算拒绝，不算故障）
- **预期**：D1 高峰中排队数一路上涨，下单 P99 冲到 3 秒、大量 504；D2 被拒的只有超出阈值的浏览请求，下单 P99 与高峰前相当；D3 与 D1 差别不大——这个模型里每个请求成本一样，降级关掉的只是查库，「猜你喜欢」的请求照样占容量
- **判定**：高峰期间下单成功率与 P99；被限流拒绝的浏览请求占比
- **截图**：D1、D2 的「网关每秒请求（按状态码）」「P99 耗时」「注入的故障：排队与在途」面板；D0 的阶梯曲线（浏览量 vs 下单 P99）
- **预计耗时**：D0 约 10 分钟，其余每组约 8 分钟，共约 40 分钟

## 六、跑完之后

- 每组至少跑两遍，数字差异大的再跑一遍；文章里写中位数并注明次数
- 写文章时引用的数字都要能在 `out/` 下找到出处（文件 + 时间段），图由 CSV 画或面板截图，不手工改数
- 实验暴露出的问题（比如连接池、网关实例缓存）是否改正式默认值，单独决定，不在实验分支里顺手改

## 七、安全与清理

- 故障注入、模拟 SIGTERM、降级开关都只在 demo profile 下存在，生产不注册；`prod` 与 `demo` 同时激活启动即失败
- 故障规则都有有效期（默认 10 分钟、最长 1 小时），脚本收尾时还会主动撤销；服务重启后规则全部消失
- 脚本只连本机服务和 `config/application-local.yml` 里的中间件，不连生产；内网地址只写在本地文件和命令行参数里，不进仓库
- 实验数据（订单、预占、本地消息）留在开发库里，想清空就在开发虚拟机上重建中间件的数据卷（需要先和共用这台机器的人确认）

## 八、局限

- 四个服务、压测、采样都在同一台机器上，CPU 互相抢；D 用容量模型规避，A、B、C 的绝对数值只对这台机器成立，看的是组间差异
- 中间件在虚拟机上，本机到虚拟机的网络延迟会计入每次数据库与 Redis 访问
- 实验用户不在 `t_user` 里，只覆盖只认令牌用户 ID 的接口
- Prometheus 5 秒一个点，秒级的细节以本机逐秒采样（`metrics.csv`）和 `timeline.csv` 为准

## 九、实验结论与随后的修改

2026-11 跑完全部四个实验（每种做法 2 轮以上），文章在 Java Doc「高可用」模块的 12–15 篇。实验暴露的问题与对应的修改：

| 实验 | 发现 | 修改 |
|------|------|------|
| A | 服务间连接池是 HttpClient 5 的默认值（每个下游实例 5 条、取连接等 3 分钟），下游一慢调用全排在池子里，读超时不起作用 | 三个服务的 `application.yml`：`mall.http-client` 每实例 100 条、总数 200、取连接最多等 100ms |
| A、B | 舱壁包在熔断器里面，舱壁满抛的 `BulkheadFullException` 被熔断器记成成功，舱壁占满时熔断器打不开 | 三个熔断器实例加 `ignore-exceptions`（`application.yml` 与 `deploy/nacos/mall-app.yaml`） |
| C | 关掉 Nacos 自动注册后，停机时不会自动注销，不经过 preStop 的停机每次重启丢几百个请求 | `RegisterWhenReady` 在 `ContextClosedEvent` 最前面主动注销 |
| C | 注销后的等待（15 + 10 秒）短于调用方的实例缓存（35 秒） | LoadBalancer 实例缓存改成 5 秒（网关与三个服务） |
| C | 停机要 75–90 秒（消费者逐个关闭，每个约 30 秒），超过 60 秒宽限期 | `ParallelConsumerShutdown` 并行关闭消费者；Kubernetes 清单的宽限期改为 90 秒 |
| 跑实验时 | 启动瞬间连不上 Redis，应用启动失败但进程不退出 | 四个服务的 `main` 捕获启动异常后 `System.exit(1)` |
| D | 限流阈值 300/s 贴着容量（压满后实际处理约 316/s），一次积压就回不来 | `d2-limit`、`d4-limit-degrade` 的阈值改为 240/s |
