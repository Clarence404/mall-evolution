# mall-evolution

Java Doc「项目演进」模块的配套代码：示例商城订单服务，从 3 人的模块化单体一步步演进到大促下单峰值 5000 QPS 的分布式系统。

**版本基线**：JDK 21、Spring Boot 4.1.2、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0、MySQL 8.4、Redis 8、RocketMQ 5（rocketmq-client-java 5.2.2）、Flyway 11、Testcontainers 2、Resilience4j 2、Gatling 3.16。

[![verify](https://github.com/Clarence404/mall-evolution/actions/workflows/verify.yml/badge.svg)](https://github.com/Clarence404/mall-evolution/actions/workflows/verify.yml) [测试报告](https://clarence404.github.io/mall-evolution/)

## 阶段

每个阶段打一个标签，`git checkout <标签>` 即可看到当时的代码；每篇文章一个提交，提交信息里写了对应哪一篇。

| 标签 | 对应文章 | 文章链接 |
|------|---------|---------|
| — | 项目演进总览 | <https://clarence404.github.io/java-doc/project/0_overview.html> |
| `stage-1-monolith` | 工程搭建 | <https://clarence404.github.io/java-doc/project/1_project_setup.html> |
| `stage-1-monolith` | 数据建模 | <https://clarence404.github.io/java-doc/project/2_data_model.html> |
| `stage-1-monolith` | 接口设计 | <https://clarence404.github.io/java-doc/project/3_api_design.html> |
| `stage-2-cache-async` | 引入缓存 | <https://clarence404.github.io/java-doc/project/4_cache.html> |
| `stage-2-cache-async` | 异步化 | <https://clarence404.github.io/java-doc/project/5_async.html> |
| `stage-3-split` | 拆分服务 | <https://clarence404.github.io/java-doc/project/6_split.html> |
| `stage-3-split` | 跨服务一致性 | <https://clarence404.github.io/java-doc/project/7_consistency.html> |
| `stage-4-stability` | 稳定性 | <https://clarence404.github.io/java-doc/project/8_stability.html> |
| `stage-4-stability` | 可观测与发布 | <https://clarence404.github.io/java-doc/project/9_observability_release.html> |
| `main` | 复盘 | <https://clarence404.github.io/java-doc/project/10_retrospective.html> |

## 本地运行（最终形态）

### 一条命令体验

只需要 Docker（Compose v2.24+），不用装 JDK 和 Maven：

```bash
docker compose --profile demo up -d --build   # 首次要在容器里跑一遍 Maven 构建，几分钟（取决于下载依赖的速度）
```

等 `mall-gateway` 变为 healthy（`docker compose ps`）后打开 <http://localhost:9000/> 即是演示控制台，用演示账号 `13800000000` / `mall123` 登录，按卡片依次体验：登录、商品详情两级缓存（响应头 `X-Cache`）、幂等下单重放、模拟支付回调（订单变待发货、积分到账）、1 分钟超时关单与库存归还、注入延迟后熔断打开返回 503 与 `Retry-After`、每个请求的 traceId。每张卡片显示原始请求与响应，并链接到对应文章的小节。

- 整套约占 4.5 GB 内存（Nacos 约 1 GB、RocketMQ Broker 堆压到 512 MB，4 个 Java 服务各 400–650 MB）；四个服务共用一个镜像，只对外开放网关的 9000 端口，依赖的端口与下面「逐个启动」相同
- 服务以 `dev,demo` 两个 profile 运行：demo 只用于演示，把支付超时缩到 1 分钟、熔断改为按次数统计，并打开控制台页面、`X-Cache` / `X-Trace-Id` 响应头和库存服务的故障注入开关；这些在生产（prod）都不存在
- Maven Central 访问慢时可以指定镜像仓库：`MAVEN_MIRROR=https://maven.aliyun.com/repository/public docker compose --profile demo up -d --build`
- 停止并清理（含数据卷）：`docker compose --profile demo down -v`

### 逐个启动

需要 JDK 21；依赖用 Docker 起（也只有集成测试需要 Docker）。所有密码都是本地开发用的占位值 `mall_dev`。

```bash
docker compose up -d     # MySQL 8.4（含 mall_inventory / mall_payment 库）、Redis 8、RocketMQ 5（NameServer + Broker/Proxy + 建 Topic）、Nacos 3
./mvnw -B -DskipTests package

# 各开一个终端，默认 dev profile，Flyway 自动建表并加载演示数据
java -jar inventory-service/target/inventory-service.jar   # :8082
java -jar payment-service/target/payment-service.jar       # :8083
java -jar mall-app/target/mall-app.jar                     # :8080
java -jar mall-gateway/target/mall-gateway.jar             # :9000，对外入口
```

演示账号 `13800000000` / `mall123`（用户 10001；`13800000001`–`13800000007` 是另外 7 个普通演示用户，密码相同，主要给下文的流量模拟器分散用户），运营演示账号 `13800000009` / `mall123`（ADMIN，可调 `/api/admin/**` 后台改价接口，普通用户调用返回 403），演示商品 20001–20004（20004 已下架）。经网关下单：

```bash
TOKEN=$(curl -s localhost:9000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"mall123"}' | jq -r .accessToken)
curl -i localhost:9000/api/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"lines":[{"skuId":"20001","quantity":2}],"address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}'
```

接口文档：<http://localhost:8080/swagger-ui.html>（仅 dev / test 环境开启）。看早期阶段时 `git checkout stage-1-monolith`，那时只有 `mall-app` 一个进程，`docker compose up -d mysql` 即可。

启动后浏览器打开 <http://localhost:9000/> 就是演示控制台（登录、缓存命中、幂等下单、模拟支付、超时关单、熔断、traceId）。本地运行默认带 `demo`：多了控制台、缓存来源响应头和库存故障开关，并且**未支付订单 1 分钟自动关闭**、熔断更易触发；想按正式参数跑，把运行配置里的 `demo` 去掉即可。

### 端口规划

| 服务 | 端口 | 管理端口（dev） |
|------|------|------|
| 订单与商品 mall-app | 8080 | 18080 |
| 库存服务 inventory-service | 8082 | 18082 |
| 支付服务 payment-service | 8083 | 18083 |
| 网关 mall-gateway（对外入口） | 9000 | 19000 |
| 配套的智能客服 [mall-assistant](https://github.com/Clarence404/mall-assistant) | 8088（与本仓库错开，可同时运行） | |

**抓指标**：dev profile 给每个服务开了独立管理端口（业务端口 + 10000，环境变量 `MANAGEMENT_PORT` 可改），Prometheus 匿名抓 `http://<主机>:18080/actuator/prometheus`（其余三个同理），健康检查也在管理端口（`/actuator/health/readiness`）。业务端口上的 `/actuator/prometheus` 仍要登录、匿名返回 401；管理端口上只放行 health、info、prometheus，其余端点拒绝。管理端口不经网关，只给同网段的监控访问。prod profile 同样开了管理端口：四个服务业务端口都是 8080，管理端口统一 18080（`MANAGEMENT_PORT` 可改）；`deploy/k8s` 里容器端口分别命名为 `http`（8080）和 `management`（18080），存活 / 就绪 / 启动探针与 PodMonitor 都指向 `management`，Service 只暴露 `http`；云主机部署（`deploy/deploy.sh`、`mall-evolution.service`）的健康检查也走 18080。

### 在 IntelliJ IDEA 里运行

仓库自带共享运行配置（`.run/` 目录），用 IDEA 打开项目、等 Maven 导入完成后，右上角运行下拉框里就能看到：

| 运行配置 | 作用 |
|----------|------|
| 0. 中间件（docker compose，需 Docker） | 起 MySQL、Redis、RocketMQ、Nacos，等同 `docker compose up -d` |
| 1. 库存服务 / 2. 支付服务 / 3. 订单与商品 / 4. 网关 | 分别启动四个服务，`dev,local` 两个 profile |
| 全部服务（先起中间件） | 按 1→4 一次启动四个服务 |
| 5. 流量模拟（演示账号） | 四个服务起来之后再启动：长期模拟真人浏览、下单、支付，让监控面板一直有数据，见下文「流量模拟器」 |
| 测试：mvn verify | 跑全部检查与测试（没有 Docker 时集成测试自动跳过） |

四个服务用的是 Spring Boot 类型的运行配置（需 IDEA Ultimate），profile 在「Active profiles」里设为 `dev,local,demo`；用 Community 版时，对各服务的启动类右键运行，并在运行配置里加环境变量 `SPRING_PROFILES_ACTIVE=dev,local`、工作目录设为仓库根目录。IDEA 自动识别出的同名 Spring Boot 配置不带 `local` profile，用仓库里这几个即可。

运行配置里带了 VM 参数 `--enable-native-access=ALL-UNNAMED`：用 JDK 24 及以上运行时，RocketMQ 客户端内置的 Netty 会加载本地网络库，JVM 会打印「A restricted method in java.lang.System has been called」的警告，加上这个参数就不再提示；JDK 21 也认识这个参数，没有副作用。

前提：项目 SDK 设为 JDK 21；中间件默认连本机 `docker compose` 起的那套（Docker Desktop 即可）。

本机没有 Docker 时，可以连另一台机器（比如共享的开发虚拟机）上的中间件：

1. 在那台机器上用仓库的 `docker-compose.yml` 只起中间件（`docker compose up -d`），端口冲突时改映射端口；
2. 把 `config/application-local.example.yml` 复制为同目录下的 `config/application-local.yml`（已加入 `.gitignore`，不会提交），填上那台机器的地址与端口（`MALL_DB_HOST` / `MALL_DB_PORT`、`MALL_REDIS_HOST` / `MALL_REDIS_PORT`、`NACOS_ADDR`、`ROCKETMQ_ENDPOINTS`）；
3. 直接点上面的运行配置。它们启用 `dev,local` 两个 profile、工作目录是仓库根目录，Spring Boot 默认会读 `./config/` 下的 `application-{profile}.yml`，所以能自动读到 `config/application-local.yml`，没有这个文件时照常连 localhost。

示例里的 `spring.cloud.nacos.discovery.ip: 127.0.0.1` 让四个服务以 127.0.0.1 注册到 Nacos，本机互相调用（网关转发、服务间 HTTP 调用）都能连上；有服务跑在别的机器上时改成本机局域网 IP。几个人共用同一个 Nacos 时，各自设不同的 `NACOS_NAMESPACE`（先在 Nacos 控制台建好命名空间），免得互相调到对方的实例。运行配置只在 `main` 上；切到早期章节（如 `git checkout ch03-api-design`）后 `.run/` 会随之消失，这时在 IDEA 里对 `MallApplication` 右键运行即可，那时只有这一个进程，也只需要 MySQL。

### 流量模拟器（让监控一直有数据）

`simulator` 模块是一个不开端口的 Spring Boot 应用，用演示账号经网关（`http://localhost:9000`）长期模拟真人使用商城，让 Prometheus / Grafana 的业务大盘和 JVM / HTTP 面板一直有数据。它和 `perf`（Gatling 压测，跑一阵子、压到极限）不同：速率低、一直跑、行为像真人。

```bash
# 先起中间件和四个服务（dev,local,demo），再：
./mvnw -B -DskipTests -pl simulator -am package
java -jar simulator/target/simulator.jar                         # Ctrl+C 停止
java -jar simulator/target/simulator.jar --simulator.max-shoppers=20 --simulator.curve.acceleration=24   # 人多一点、一小时走完一天
```

IDEA 里直接选运行配置「5. 流量模拟（演示账号）」，停止按钮即退出（做完手上的请求再走）。配置都在 `simulator/src/main/resources/application.yml`，每项都能用环境变量覆盖（`SIMULATOR_BASE_URL`、`SIMULATOR_MAX_SHOPPERS`、`SIMULATOR_SPIKE_ENABLED=false` 这样）或在 Program arguments 里写 `--simulator.xxx=yyy`。

- **像真人**：最多 12 个虚拟用户，每人「打开 App 逛一会」做 3～12 件事（看商品详情 55%、猜你喜欢 18%、我的 10%、查订单 15%、下单 2%），两件事之间思考 2～8 秒，逛完歇 5～30 秒、可能换个账号再来；同时在逛的人数按时段起伏（24 个整点的曲线，凌晨约 1/4、晚上 8～9 点最多，`curve.acceleration` 可加速）。商城没有搜索接口，「逛」用猜你喜欢代替
- **下单**：下单后 70% 付款（创建支付单 + 用 dev 的占位密钥签名模拟渠道回调，看订单变待发货、积分到账），其余一部分主动取消、其余放着等 demo profile 的 1 分钟超时关单；5% 的下单会用同一个幂等键再提交一次
- **少量异常**：每次操作 1% 输错密码（401）、1% 非法参数（400）、1% 不存在的商品（404），都是 4xx，不算进下单 SLO 的 5xx；不注入故障，熔断、下单 SLO 告警不会被它触发
- **偶尔一阵高峰**：平均 6 小时一次（启动 1 小时后才可能有第一次）、每次 8 分钟，2 个用户狂点「提交订单」（同一个幂等键，每人每秒 3 次），网关按「用户 + 路由」限流返回 429；429 占比超过 5% 满 5 分钟，`GatewayRateLimitedHigh` 会真实 firing 一次、高峰过后自动恢复。不想要就 `SIMULATOR_SPIKE_ENABLED=false`
- **库存保护**：已支付的订单会一直消耗库存。模拟器每分钟看一次库存（demo profile 的 `GET /api/demo/inventory/stocks`），低于 200 就调 `PUT /api/demo/inventory/stocks/{skuId}/restock?target=1000` 补到 1000（只补不减，分桶 SKU 不处理，同样只在 demo profile 下存在）；补货接口不可用时，低于 50 就停买该商品、只逛不买
- **数据量**：默认每小时最多 180 单（`purchase.max-orders-per-hour`），实测白天约每分钟 2 单，按曲线折算**一天约 2000～2500 单**（其中约七成支付），连同支付单、本地消息、消费记录、预占记录，MySQL 一天大约多几万行、十来 MB；本地开发库不用专门清理，想清空就 `docker compose down -v` 重建（演示数据会重新加载）。四个服务的日志也会随请求增长
- 启动时打印目标和速率，之后每分钟一行统计（请求按状态码分类、登录、下单与付款、库存、下一次高峰时间）

演示账号 13800000001–13800000007 是在 `mall-app` 的 `db/seed/R__dev_seed_users.sql`（可重复迁移，只在 dev / 集成测试加载）里加的，重启 mall-app 时 Flyway 自动补上；模拟器用 8 个普通账号，运营账号 13800000009 不用。

## 构建与测试

```bash
./mvnw -B verify          # Enforcer、Spotless（palantir）、Checkstyle、ArchUnit、单元测试、H2 主流程测试、Testcontainers 集成测试
./mvnw -pl perf gatling:test -DbaseUrl=https://<压测环境网关>   # 只在同构压测环境执行
```

测试分三层：

| 层 | 命名 | 需要 Docker | 覆盖 |
|----|------|------------|------|
| 单元测试 | `*Test` | 否 | 下单幂等、预占结果三分类、去重消费、关单、中继投递与 traceparent、熔断谓词、对账比对等 |
| H2 全上下文测试 | `*H2Test`、`ApplicationContextSmokeTest` | 否 | H2 的 MySQL 兼容模式跑全部 Flyway 迁移与真实 SQL：下单与重放、取消、支付消息、库存预占的空回滚与悬挂、分桶、熔断打开返回 503、下单频控；同时导出 `api/openapi.json` |
| 集成测试 | `*IT`、`*ApiTests` | 是 | Testcontainers 起 MySQL 8.4 / Redis 8：雪花机器号租约（两实例不同号、租约丢失拒绝发号）、并发同键只下一单、不超卖、交叉下单不死锁、reserve 与 release 竞态、`FOR UPDATE SKIP LOCKED` 加租约的中继不重发、两级缓存失效、预热后注册到真实 Nacos 3.1 且端口正确 |

没有 Docker 的机器上集成测试会自动跳过（`@Testcontainers(disabledWithoutDocker = true)`），并发、锁、隔离级别相关的结论以集成测试为准。PR 检查用 oasdiff 把导出的 openapi.json 与目标分支（main）上的 `api/openapi.json` 比对，删字段、改类型这类破坏性变更直接失败。

## 测试报告

每次推送 `main` 或章节 / 阶段 tag，GitHub Actions（`.github/workflows/verify.yml`）都会在带 Docker 的 runner 上跑完整的 `./mvnw -B verify`（集成测试不跳过），并把报告发布到 GitHub Pages：

- 地址规律：`https://clarence404.github.io/mall-evolution/reports/<ref>/`，`<ref>` 是分支名或 tag 名，例如 [`reports/main/`](https://clarence404.github.io/mall-evolution/reports/main/)、[`reports/ch03-api-design/`](https://clarence404.github.io/mall-evolution/reports/ch03-api-design/)、[`reports/stage-1-monolith/`](https://clarence404.github.io/mall-evolution/reports/stage-1-monolith/)
- 内容：单元测试（surefire）与集成测试（failsafe）的 HTML 报告、各模块 JaCoCo 覆盖率；[报告首页](https://clarence404.github.io/mall-evolution/)列出所有已发布的 ref
- 测试失败时报告照样发布，workflow 记为失败

## 目录结构

```text
mall-evolution/
├── mall-common/          错误码与 ProblemDetail、雪花 ID（显式配置或向 Redis 租机器号）、JWT、RocketMQ 客户端、本地消息表 t_outbox 与中继、消费去重、远程错误透传、降级开关
├── mall-inventory-api/   库存服务的远程契约：InventoryApi（@HttpExchange，reserve / confirm / release）
├── mall-order-api/       订单的远程契约：OrderQueryApi、OrderErrorCode
├── mall-payment-api/     支付服务的远程契约：PaymentApi（关闭支付单、查询）
├── mall-user/            用户、登录与刷新令牌、积分（points-consumer）
├── mall-product/         商品、详情两级缓存（Caffeine + Redis）、库存展示、猜你喜欢
├── mall-order/           下单（预占 + 熔断舱壁 + 频控）、查询、取消与超时关单、支付消息消费、通知 / 发货单消费者
├── mall-app/             订单、商品、用户的启动模块：配置、Flyway、每日对账、ArchUnit / H2 / 集成测试
├── inventory-service/    库存服务：预占、过期回查、热点分桶，独享 mall_inventory
├── payment-service/      支付服务：渠道回调、payment-succeeded、关单与退款，独享 mall_payment
├── mall-gateway/         入口网关：路由 /api/**、JWT 校验、按「用户 + 路由」限流；demo profile 下托管演示控制台
├── perf/                 Gatling 压测脚本
├── simulator/            流量模拟器：演示账号经网关长期模拟真人浏览、下单、支付，让监控面板一直有数据（只用于本地 / 开发环境）
├── api/openapi.json      对外接口契约基线
├── deploy/
│   ├── k8s/              Deployment / Rollout + AnalysisTemplate、Service、Ingress、HPA、Flyway PreSync Job、Secret 模板
│   ├── argocd/           Argo CD Application
│   ├── demo/             一条命令体验的镜像构建（容器里跑 Maven，四个服务共用）
│   ├── observability/    OTel Collector（两层）、Prometheus SLO 与组件告警、PodMonitor、Alertmanager、Grafana 业务大盘与 JVM / HTTP 通用面板
│   ├── nacos/            Nacos 配置样例（熔断舱壁、降级开关、活动白名单、限流路由、结构化日志）
│   ├── redis/ rocketmq/ mysql/   本地与自建环境的 Redis Sentinel、Broker、建库建 Topic 脚本
│   ├── promo/            大促预案脚本（扩容、分桶与合并）
│   └── mall-evolution.service、deploy.sh   阶段一的 systemd 单元与逐台发布脚本
├── config/               Checkstyle 规则（checkstyle/）、本地覆盖配置模板 application-local.example.yml
├── Dockerfile            按模块构建镜像，内置 OpenTelemetry Java Agent
└── .github/workflows/    完整验证与测试报告发布（verify）、PR 检查（verify + 契约 diff + 密钥扫描 + 清单与告警规则校验）、阶段一部署、发布（镜像 → GitOps）
```

## 复盘：四个阶段的决策怎么落在代码里

对应 [复盘](https://clarence404.github.io/java-doc/project/10_retrospective.html)。按标签对比代码，能直接看到每一步改了什么、没改什么：

| 做对的事 | 在代码里的体现 |
|---------|---------------|
| 边界先于部署 | 阶段一 `ModuleBoundaryTest` 守住「业务模块之间只走 api 包」；阶段三拆库存只是把 `mall-inventory` 的 api 包抽成 `mall-inventory-api`、加上 `@HttpExchange`，订单里注入的仍是 `InventoryApi` |
| 正确性交给数据库约束 | `uk_user_biz`（下单幂等）、`uk_topic_key`（一单一种消息只写一条）、`uk_order_sku`（预占幂等、空回滚与悬挂）、`t_consume_record` 主键，加上所有状态流转的带旧状态条件更新 |
| 一套可靠消息机制用到底 | `t_outbox` 在异步化引入，跨服务一致性时抽到 `mall-common`，三个服务共用；没有引入事务消息 |
| 写操作不自动重试，结果未知按失败处理 | `PlaceOrderService` 对 reserve 的三类处理：明确失败直接抛、没发出去返回 `SYSTEM_BUSY`、结果未知立即释放，未知状态交给过期回查与每日对账收敛 |

| 走过的弯路 | 在提交历史里 |
|-----------|-------------|
| 超时关单从定时扫描起步 | 阶段一 `OrderTimeoutScanJob` 每分钟扫；阶段二改为定时消息 + 每 10 分钟兜底。关单逻辑一开始就写成按订单号的 `closeIfUnpaid(orderNo)`，换触发方式时只换调度 |
| 订单号规则中途更换 | 阶段一 19 位雪花 ID 字符串，阶段三 `OrderNoGenerator` 改为嵌入用户 ID 低 10 位；存量 19 位订单号由 `userGene` 返回 -1 识别 |
| 拆分过渡期的「先扣后补」与同步 `markPaid` | `6_split` 提交里的 `deduct → 写单 → restore`、关单后归还库存的重试表 `t_stock_restore_task` 与 `OrderPayApi.markPaid`，在 `7_consistency` 提交里分别被预占、`order-cancelled` 消息和 `payment-succeeded` 消息替换 |
| 链路追踪接得晚 | 阶段一 `TraceIdFilter` 只在单进程有效，阶段四才换成 OpenTelemetry，并让 traceparent 穿过 `t_outbox` |

明确没做、等触发条件出现再做的：多活与异地容灾、`t_order` 分库分表、服务网格（金丝雀目前按 Pod 数分流）、Redis 预扣库存、生产全链路压测、Seata、继续拆订单 / 商品 / 用户、Redis Cluster、binlog 订阅删缓存、顺序消息、准实时对账（目前每日一次）。

## 许可证

[MIT](LICENSE)
