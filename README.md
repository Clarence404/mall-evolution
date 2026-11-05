# mall-evolution

Java Doc「项目演进」模块的配套代码：示例商城订单服务，从 3 人的模块化单体一步步演进到大促下单峰值 5000 QPS 的分布式系统。

**版本基线**：JDK 21、Spring Boot 4.0.9、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0、MySQL 8.4、Redis 8、RocketMQ 5（rocketmq-client-java 5.2.2）、Flyway 11、Testcontainers 2、Resilience4j 2、Gatling 3.16。

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

演示账号 `13800000000` / `mall123`（用户 10001），运营演示账号 `13800000009` / `mall123`（ADMIN，可调 `/api/admin/**` 后台改价接口，普通用户调用返回 403），演示商品 20001–20004（20004 已下架）。经网关下单：

```bash
TOKEN=$(curl -s localhost:9000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"mall123"}' | jq -r .accessToken)
curl -i localhost:9000/api/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"lines":[{"skuId":"20001","quantity":2}],"address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}'
```

接口文档：<http://localhost:8080/swagger-ui.html>（仅 dev / test 环境开启）。看早期阶段时 `git checkout stage-1-monolith`，那时只有 `mall-app` 一个进程，`docker compose up -d mysql` 即可。

### 在 IntelliJ IDEA 里运行

仓库自带共享运行配置（`.run/` 目录），用 IDEA 打开项目、等 Maven 导入完成后，右上角运行下拉框里就能看到：

| 运行配置 | 作用 |
|----------|------|
| 0. 中间件（docker compose，需 Docker） | 起 MySQL、Redis、RocketMQ、Nacos，等同 `docker compose up -d` |
| 1. 库存服务 / 2. 支付服务 / 3. 订单与商品 / 4. 网关 | 分别启动四个服务，`dev` profile |
| 全部服务（先起中间件） | 按 1→4 一次启动四个服务 |
| 测试：mvn verify | 跑全部检查与测试（没有 Docker 时集成测试自动跳过） |

前提：项目 SDK 设为 JDK 21；本机需要 Docker（Docker Desktop 即可）来跑中间件。没有 Docker 时，可以把中间件放在另一台机器上，在运行配置的环境变量里覆盖地址，例如 `SPRING_DATASOURCE_URL`、`SPRING_DATA_REDIS_HOST`、`NACOS_ADDR`、`ROCKETMQ_ENDPOINTS`。运行配置只在 `main` 上；切到早期章节（如 `git checkout ch03-api-design`）后 `.run/` 会随之消失，这时在 IDEA 里对 `MallApplication` 右键运行即可，那时只有这一个进程，也只需要 MySQL。

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
├── api/openapi.json      对外接口契约基线
├── deploy/
│   ├── k8s/              Deployment / Rollout + AnalysisTemplate、Service、Ingress、HPA、Flyway PreSync Job、Secret 模板
│   ├── argocd/           Argo CD Application
│   ├── demo/             一条命令体验的镜像构建（容器里跑 Maven，四个服务共用）
│   ├── observability/    OTel Collector（两层）、Prometheus SLO 与组件告警、PodMonitor、Alertmanager、Grafana 业务大盘
│   ├── nacos/            Nacos 配置样例（熔断舱壁、降级开关、活动白名单、限流路由、结构化日志）
│   ├── redis/ rocketmq/ mysql/   本地与自建环境的 Redis Sentinel、Broker、建库建 Topic 脚本
│   ├── promo/            大促预案脚本（扩容、分桶与合并）
│   └── mall-evolution.service、deploy.sh   阶段一的 systemd 单元与逐台发布脚本
├── config/checkstyle/
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
