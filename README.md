# mall-evolution

Java Doc「项目演进」模块的配套代码：示例商城订单服务，从 3 人的模块化单体一步步演进到大促下单峰值 5000 QPS 的分布式系统。

**版本基线**：JDK 21、Spring Boot 4.1.2、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0、MySQL 8.4、Redis 8、RocketMQ 5（rocketmq-client-java 5.2.2）、Flyway 12、Testcontainers 2、Resilience4j 2、Gatling 3.16。

[![verify](https://github.com/Clarence404/mall-evolution/actions/workflows/verify.yml/badge.svg)](https://github.com/Clarence404/mall-evolution/actions/workflows/verify.yml) [测试报告](https://clarence404.github.io/mall-evolution/)

## 阶段

标签有两套，`git checkout <标签>` 即可看到当时的代码：

- **章节标签** `ch01-project-setup` … `ch10-retrospective`：每篇文章一个提交、一个标签，提交信息里写了对应哪一篇；想对着某一篇文章看代码就用它
- **阶段标签** `stage-1-monolith` … `stage-4-stability`：每个阶段最后一篇文章的那个提交（和该篇的章节标签指向同一个提交），想看「这个阶段结束时系统长什么样」就用它

| 阶段标签 | 章节标签 | 对应文章 | 文章链接 |
|---------|---------|---------|---------|
| — | — | 项目演进总览 | <https://clarence404.github.io/java-doc/project/0_overview.html> |
| `stage-1-monolith` | `ch01-project-setup` | 工程搭建 | <https://clarence404.github.io/java-doc/project/1_project_setup.html> |
| `stage-1-monolith` | `ch02-data-model` | 数据建模 | <https://clarence404.github.io/java-doc/project/2_data_model.html> |
| `stage-1-monolith` | `ch03-api-design` | 接口设计 | <https://clarence404.github.io/java-doc/project/3_api_design.html> |
| `stage-2-cache-async` | `ch04-cache` | 引入缓存 | <https://clarence404.github.io/java-doc/project/4_cache.html> |
| `stage-2-cache-async` | `ch05-async` | 异步化 | <https://clarence404.github.io/java-doc/project/5_async.html> |
| `stage-3-split` | `ch06-split` | 拆分服务 | <https://clarence404.github.io/java-doc/project/6_split.html> |
| `stage-3-split` | `ch07-consistency` | 跨服务一致性 | <https://clarence404.github.io/java-doc/project/7_consistency.html> |
| `stage-4-stability` | `ch08-stability` | 稳定性 | <https://clarence404.github.io/java-doc/project/8_stability.html> |
| `stage-4-stability` | `ch09-observability-release` | 可观测与发布 | <https://clarence404.github.io/java-doc/project/9_observability_release.html> |
| `main` | `ch10-retrospective` | 复盘 | <https://clarence404.github.io/java-doc/project/10_retrospective.html> |

`main` 在 `ch10-retrospective` 之后还在继续改（演示控制台、流量模拟器、监控面板、审查后的修复等），这些不对应某一篇文章。

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

# 各开一个终端，都在仓库根目录执行。dev：连本机的中间件，Flyway 自动建表并加载演示数据；demo：演示控制台等演示用的东西
java -jar inventory-service/target/inventory-service.jar --spring.profiles.active=dev,demo   # :8082
java -jar payment-service/target/payment-service.jar --spring.profiles.active=dev,demo       # :8083
java -jar mall-app/target/mall-app.jar --spring.profiles.active=dev,demo                     # :8080
java -jar mall-gateway/target/mall-gateway.jar --spring.profiles.active=dev,demo             # :9000，对外入口
```

三种启动方式用的 profile：

| 启动方式 | profile | 说明 |
|---------|---------|------|
| `docker compose --profile demo up` | `dev,demo` | 连接地址由 compose 的环境变量指向容器 |
| 上面的 `java -jar` | `dev,demo` | 中间件在本机；中间件在别的机器上时改成 `dev,local,demo`，见下文「在 IntelliJ IDEA 里运行」的第 2、3 步 |
| IDEA 运行配置（`.run/`） | `dev,local,demo` | `local` 只是让 Spring Boot 去读 `./config/application-local.yml`，没有这个文件时等同 `dev,demo` |

不带任何参数（`java -jar xxx.jar`）时是默认的 `dev`：按正式参数运行，没有演示控制台。`prod` 与 `demo` 不能同时激活，同时激活会直接启动失败。

演示账号 `13800000000` / `mall123`（用户 10001；`13800000001`–`13800000007` 是另外 7 个普通演示用户，密码相同，主要给下文的流量模拟器分散用户），运营演示账号 `13800000009` / `mall123`（ADMIN，可调 `/api/admin/**` 后台改价接口，普通用户调用返回 403），演示商品 20001–20004（20004 已下架）。经网关下单：

```bash
TOKEN=$(curl -s localhost:9000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"mall123"}' | jq -r .accessToken)
curl -i localhost:9000/api/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"lines":[{"skuId":"20001","quantity":2}],"address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}'
```

接口文档：<http://localhost:8080/swagger-ui.html>（仅 dev / test 环境开启）。看早期阶段时 `git checkout stage-1-monolith`，那时只有 `mall-app` 一个进程，`docker compose up -d mysql` 即可。

四个服务都带 `demo` 启动后，浏览器打开 <http://localhost:9000/> 就是演示控制台（登录、缓存命中、幂等下单、模拟支付、超时关单、熔断、traceId）。带 `demo` 时多了控制台、缓存来源响应头和库存故障开关，并且**未支付订单 1 分钟自动关闭**、熔断更易触发；想按正式参数跑，把命令行或运行配置里的 `demo` 去掉即可（这时 9000 上只有 `/api/**`，没有控制台页面）。

### 端口规划

| 服务 | 端口 | 管理端口（dev） |
|------|------|------|
| 订单与商品 mall-app | 8080 | 18080 |
| 库存服务 inventory-service | 8082 | 18082 |
| 支付服务 payment-service | 8083 | 18083 |
| 网关 mall-gateway（对外入口） | 9000 | 19000 |
| 配套的智能客服 [mall-assistant](https://github.com/Clarence404/mall-assistant) | 8088（与本仓库错开，可同时运行） | |

中间件（`docker compose up -d` 映射到宿主机的端口）：

| 中间件 | 宿主机端口 | 说明 |
|--------|-----------|------|
| MySQL 8.4 | 3306 | `mall`、`mall_inventory`、`mall_payment` 三个库在同一个实例里 |
| Redis 8 | 6379 | |
| RocketMQ 5 | 9876（NameServer）、10911（Broker）、8081（Proxy，gRPC 接入点） | 应用只连 8081 |
| Nacos 3 | 8848（HTTP）、9848（gRPC）、18848（控制台） | 控制台 <http://localhost:18848/>；客户端只用 8848 / 9848 |

Nacos 控制台原先映射在宿主机 18080，和 mall-app 在 dev 下的管理端口撞了（中间件和服务在同一台机器上时，后启动的那个起不来），现在改为 18848。已经用旧的 compose 起过 Nacos 的，`docker compose up -d nacos` 重建一次容器即可，数据不受影响。

**抓指标**：dev profile 给每个服务开了独立管理端口（业务端口 + 10000，环境变量 `MANAGEMENT_PORT` 可改），Prometheus 匿名抓 `http://<主机>:18080/actuator/prometheus`（其余三个同理），健康检查也在管理端口（`/actuator/health/readiness`）。业务端口上的 `/actuator/prometheus` 仍要登录、匿名返回 401；管理端口上只放行 health、info、prometheus，其余端点拒绝。管理端口不经网关，只给同网段的监控访问。prod profile 同样开了管理端口：四个服务业务端口都是 8080，管理端口统一 18080（`MANAGEMENT_PORT` 可改）；`deploy/k8s` 里容器端口分别命名为 `http`（8080）和 `management`（18080），存活 / 就绪 / 启动探针与 PodMonitor 都指向 `management`，Service 只暴露 `http`；云主机部署（`deploy/deploy.sh`、`mall-evolution.service`）的健康检查也走 18080。

### 在 IntelliJ IDEA 里运行

仓库自带共享运行配置（`.run/` 目录），用 IDEA 打开项目、等 Maven 导入完成后，右上角运行下拉框里就能看到：

| 运行配置 | 作用 |
|----------|------|
| 0. 中间件（docker compose，需 Docker） | 起 MySQL、Redis、RocketMQ、Nacos，等同 `docker compose up -d` |
| 1. 库存服务 / 2. 支付服务 / 3. 订单与商品 / 4. 网关 | 分别启动四个服务，`dev,local,demo` 三个 profile |
| 全部服务（先起中间件） | 按 1→4 一次启动四个服务 |
| 5. 流量模拟（演示账号） | 四个服务起来之后再启动：长期模拟真人浏览、下单、支付，让监控面板一直有数据，见下文「流量模拟器」 |
| 测试：mvn verify | 跑全部检查与测试（没有 Docker 时集成测试自动跳过） |

四个服务用的是 Spring Boot 类型的运行配置（需 IDEA Ultimate），profile 在「Active profiles」里设为 `dev,local,demo`；用 Community 版时，对各服务的启动类右键运行，并在运行配置里加环境变量 `SPRING_PROFILES_ACTIVE=dev,local,demo`、工作目录设为仓库根目录。IDEA 自动识别出的同名 Spring Boot 配置不带这几个 profile（只有默认的 `dev`），用仓库里这几个即可。

运行配置里带了 VM 参数 `--enable-native-access=ALL-UNNAMED`：用 JDK 24 及以上运行时，RocketMQ 客户端内置的 Netty 会加载本地网络库，JVM 会打印「A restricted method in java.lang.System has been called」的警告，加上这个参数就不再提示；JDK 21 也认识这个参数，没有副作用。

前提：项目 SDK 设为 JDK 21；中间件默认连本机 `docker compose` 起的那套（Docker Desktop 即可）。

本机没有 Docker 时，可以连另一台机器（比如共享的开发虚拟机）上的中间件：

1. 在那台机器上用仓库的 `docker-compose.yml` 只起中间件（`docker compose up -d`），端口冲突时改映射端口；
2. 把 `config/application-local.example.yml` 复制为同目录下的 `config/application-local.yml`（已加入 `.gitignore`，不会提交），填上那台机器的地址与端口（`MALL_DB_HOST` / `MALL_DB_PORT`、`MALL_REDIS_HOST` / `MALL_REDIS_PORT`、`NACOS_ADDR`、`ROCKETMQ_ENDPOINTS`）；
3. 直接点上面的运行配置。它们启用 `dev,local,demo` 三个 profile、工作目录是仓库根目录：激活了 `local` 之后，Spring Boot 默认会读工作目录下 `./config/application-local.yml`——四个服务都只靠这一条规则读到它，jar 里没有 `application-local.yml`，也没有别的导入方式；没有这个文件时照常连 localhost。用 `java -jar` 时同理：在仓库根目录执行，并把 profile 写成 `dev,local,demo`。

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
- **偶尔一阵高峰**：平均 6 小时一次（启动 1 小时后才可能有第一次）、每次 8 分钟，2 个用户对还没开卖的商品（已下架的 20004）狂点「提交订单」（每人每秒 3 次），网关按「用户 + 路由」限流返回 429，放行的到订单服务返回 422（商品已下架），不产生订单、下单量曲线不受影响；429 占比超过 5% 满 5 分钟，`GatewayRateLimitedHigh` 会真实 firing 一次、高峰过后自动恢复。不想要就 `SIMULATOR_SPIKE_ENABLED=false`
- **库存保护**：已支付的订单会一直消耗库存。模拟器每分钟看一次库存（demo profile 的 `GET /api/demo/inventory/stocks`），低于 200 就调 `PUT /api/demo/inventory/stocks/{skuId}/restock?target=1000` 补到 1000（只补不减，分桶 SKU 不处理，同样只在 demo profile 下存在）；补货接口不可用时，低于 50 就停买该商品、只逛不买
- **数据量**：默认每小时最多 180 单（`purchase.max-orders-per-hour`），实测白天约每分钟 2 单，按曲线折算**一天约 2000～2500 单**（其中约七成支付），连同支付单、本地消息、消费记录、预占记录，MySQL 一天大约多几万行、十来 MB；本地开发库不用专门清理，想清空就 `docker compose down -v` 重建（演示数据会重新加载）。四个服务的日志也会随请求增长
- 启动时打印目标和速率，之后每分钟一行统计（请求按状态码分类、登录、下单与付款、库存、下一次高峰时间）

演示账号都在 `mall-app` 的 `db/seed/R__dev_seed_users.sql`（可重复迁移，只在 dev / 集成测试加载）里：普通账号共 8 个（13800000000–13800000007，用户 10001–10008），其中 13800000002–13800000007 这 6 个是为模拟器新增的，重启 mall-app 时 Flyway 自动补上；模拟器用全部 8 个普通账号，运营账号 13800000009 不用。

### 本地监控（Prometheus + Grafana）

四个服务的指标在各自的管理端口上（见「端口规划」），本仓库没有单独的监控 compose，直接复用配套仓库 [mall-assistant](https://github.com/Clarence404/mall-assistant) 的 `monitoring/`（compose 项目名 `mall-monitoring`：Prometheus 3.5 + Grafana 11.6，启动方法见那边的说明），再把本仓库的抓取任务、告警规则和两个面板加进去。`deploy/observability/local/` 是可以照抄的示例：

| 本仓库的文件 | 用途 | 放到 mall-monitoring 的哪里 |
|-------------|------|---------------------------|
| `deploy/observability/local/prometheus.yml` | `mall-evolution` 抓取任务（file_sd）与 `rule_files` | 把 `scrape_configs` 里的这个任务合并进它的 `prometheus/prometheus.yml`（或者把这个文件原样当配置用） |
| `deploy/observability/local/targets/mall-evolution.json` | 抓哪四个地址、打什么标签 | 放到 Prometheus 配置目录下，路径和任务里 `file_sd_configs.files` 对上；注意不要放进 mall-assistant 自己那个任务的 `targets/` 目录（会被当成它的实例） |
| `deploy/observability/prometheus/rules/*.yml` | 下单 SLO 与组件告警 | 放到 Prometheus 配置目录并在 `rule_files` 里列出；改完 `curl -X POST localhost:9090/-/reload` |
| `deploy/observability/local/grafana/provisioning/` | 数据源与面板目录的 provisioning 示例 | `dashboards/mall-evolution.yml` 放进它的 `grafana/provisioning/dashboards/`；已经有 Prometheus 数据源就不用再放 `datasources/` 那份 |
| `deploy/observability/grafana/business-dashboard.json`、`jvm-http-dashboard.json` | 业务大盘、JVM 与 HTTP 通用面板 | 在 compose 的 grafana 服务上加两行只读挂载，挂到 `/var/lib/grafana/dashboards-evolution/` |

几点说明：

- **抓取地址**：示例里是 `host.docker.internal:18080 / 18082 / 18083 / 19000`，即 Prometheus 在容器里、四个服务跑在同一台宿主机上；服务在别的机器上时改成那台机器的地址（内网地址只写在部署目录里，不要提交）。Prometheus 匿名抓管理端口的 `/actuator/prometheus`，不需要令牌
- **`app` 标签怎么打**：在 file_sd 的 `labels` 里给每个地址写 `"app": "<服务名>"`（`mall-app` / `inventory-service` / `payment-service` / `mall-gateway`），JVM 与 HTTP 面板顶部的服务下拉框按它切换；Kubernetes 里这个标签由 `podmonitor.yaml` 从 Pod 的 `app` 标签带过来，两边取值一致。业务大盘和告警规则用的是另一个标签 `application`，它由应用自己打（`management.metrics.tags.application`），不用配
- **导入面板**：用 provisioning（上表最后两行）时 Grafana 启动后就在 `mall-evolution` 文件夹里；也可以手工导入——Grafana → Dashboards → New → Import，上传 JSON，数据源变量 `datasource` 选 Prometheus。两个面板的 uid 分别是 `mall-business` 和 `mall-jvm-http`
- **确认抓到了**：Prometheus 的 Status → Targets 里 `mall-evolution` 任务四个地址都是 UP；Alerts 页能看到规则。Redis / RocketMQ 的指标来自各自的 exporter，本地没接时相关告警和面板没有数据，属正常
- **想让面板一直有数据**：起上面的「流量模拟器」

## 构建与测试

```bash
./mvnw -B verify          # Enforcer、Spotless（palantir）、Checkstyle、ArchUnit、单元测试、H2 主流程测试、Testcontainers 集成测试
./mvnw spotless:apply     # verify 报格式不对时一键修复（palantir-java-format），提交前跑一下
./mvnw -pl perf gatling:test -DbaseUrl=https://<压测环境网关>   # 只在同构压测环境执行，见 perf/README.md
```

测试分三层：

| 层 | 命名 | 需要 Docker | 覆盖 |
|----|------|------------|------|
| 单元测试 | `*Test` | 否 | 下单幂等、预占结果三分类、去重消费、关单、中继投递（退避封顶、按时间放弃、非法消息立即置失败）与 traceparent、熔断谓词、对账比对、「有货」提示的本机缓存与兜底、消费线程数显式设置、prod 与 demo 互斥；prod 配置下业务端口与管理端口分开（四个服务各一个 `ProdManagementPortConfigTest`）、管理端口过滤链的端口匹配（`ManagementPortMatchTest`）；流量模拟器的速率曲线、权重、库存保护、退避、配额、高峰间隔、签名（`simulator` 模块） |
| H2 全上下文测试 | `*H2Test`、`ApplicationContextSmokeTest`，以及网关不连数据库的全上下文测试 `Gateway*Test`、`DemoConsoleTest` | 否 | H2 的 MySQL 兼容模式跑全部 Flyway 迁移与真实 SQL：下单与重放、取消、支付消息、库存预占的空回滚与悬挂、分桶（含 release / 合并与预占并发的固定顺序复现）、熔断打开返回 503、详情页库存查询熔断后仍 200、下单频控、outbox 重试回写、过期刷新令牌清理、`API-Version` 请求头；管理端口只放行 health / info / prometheus、业务端口上 `/actuator/prometheus` 仍 401（`ManagementPortSecurityH2Test`，网关是 `GatewayManagementPortTest`）；定时任务线程数与消费线程预算；同时导出 `mall-app/target/openapi.json` 供契约比对 |
| 集成测试 | `*IT`、`*ApiTests` | 是 | Testcontainers 起 MySQL 8.4 / Redis 8：雪花机器号租约（两实例不同号、租约丢失拒绝发号）、并发同键只下一单、不超卖、交叉下单不死锁、reserve 与 release 竞态（含分桶 SKU 回到同一个桶）、合并分桶与预占并发不多出库存、`FOR UPDATE SKIP LOCKED` 加租约的中继不重发、中继失败按时间封顶、两级缓存失效、预热后注册到真实 Nacos 3.1 且端口正确 |

没有 Docker 的机器上集成测试会自动跳过（`@Testcontainers(disabledWithoutDocker = true)`），并发、锁、隔离级别相关的结论以集成测试为准。PR 检查（`.github/workflows/pr.yml`）除了 verify 还有三项：用 oasdiff 把导出的 openapi.json 与目标分支（main）上的 `api/openapi.json` 比对，删字段、改类型这类破坏性变更直接失败（契约基线只含 mall-app 的接口，见「接口一览」）；用 gitleaks 扫描密钥，规则在仓库根目录的 `.gitleaks.toml`——沿用默认规则，只放行 `application-dev.yml`、`application-it.yml`、演示数据、`TestTokens.java`、`docker-compose.yml` 里的本地占位值，新增占位值的文件要加到那里的放行列表；Kubernetes 清单（kubeconform）、Prometheus 规则与本地抓取示例（promtool）、Alertmanager 配置（amtool）的静态检查，这一项和 verify 流水线共用 `.github/workflows/static-checks.yml`。

## 测试报告

每次推送 `main` 或章节 / 阶段 tag，GitHub Actions（`.github/workflows/verify.yml`）都会在带 Docker 的 runner 上跑完整的 `./mvnw -B verify`（集成测试不跳过），并把报告发布到 GitHub Pages：

- 地址规律：`https://clarence404.github.io/mall-evolution/reports/<ref>/`，`<ref>` 是分支名或 tag 名，例如 [`reports/main/`](https://clarence404.github.io/mall-evolution/reports/main/)、[`reports/ch03-api-design/`](https://clarence404.github.io/mall-evolution/reports/ch03-api-design/)、[`reports/stage-1-monolith/`](https://clarence404.github.io/mall-evolution/reports/stage-1-monolith/)
- 内容：单元测试（surefire）与集成测试（failsafe）的 HTML 报告、各模块 JaCoCo 覆盖率；[报告首页](https://clarence404.github.io/mall-evolution/)列出所有已发布的 ref
- 测试失败时报告照样发布，workflow 记为失败

## 接口一览

对外接口都经网关（本地 `http://localhost:9000`），除登录与商品详情外都要带 `Authorization: Bearer <访问令牌>`。

| 接口 | 所在服务 | 说明 |
|------|---------|------|
| `POST /api/auth/login`、`/api/auth/refresh`、`/api/auth/logout` | mall-app | 匿名；刷新即轮换，旧刷新令牌立刻作废 |
| `GET /api/users/me` | mall-app | 我的（含积分，可降级） |
| `GET /api/products/{skuId}`、`GET /api/products/{skuId}/recommendations` | mall-app | 匿名；商品详情（两级缓存 + 「有货」提示）、猜你喜欢（可降级为固定列表） |
| `PUT /api/admin/products/{skuId}` | mall-app | 需 ADMIN：改名称、价格、上下架，改完自动失效缓存 |
| `POST /api/admin/products/{skuId}/cache-evict` | mall-app | 需 ADMIN：改价后页面上没生效时，手动清掉这个 SKU 的两级缓存（本机 Caffeine 经广播、Redis 直接删），返回 204 |
| `POST /api/orders` | mall-app | 下单，必须带 `Idempotency-Key`；网关对它单独限流（每用户每秒 1 次、突发 3） |
| `GET /api/orders`、`GET /api/orders/{orderNo}` | mall-app | 我的订单（游标分页）、订单详情 |
| `PATCH /api/orders/{orderNo}/address` | mall-app | 发货前改地址：只传要改的字段（receiver / phone / detail），按 `version` 乐观锁；已发货或同时被别人改过返回 409 `ORDER_STATUS_CONFLICT` |
| `POST /api/orders/{orderNo}/cancel` | mall-app | 取消订单；当前状态不能取消时返回 409 `ORDER_STATUS_CONFLICT` |
| `GET /api/orders/{orderNo}/payment` | mall-app → payment-service | 查这笔订单的支付单状态：「支付成功，订单确认中」时 App 轮询它；只能查自己的订单，经 `payment-query` 熔断与舱壁 |
| `POST /api/payments` | payment-service | 创建支付单 |
| `POST /api/payments/notify/{channel}` | payment-service | 渠道回调：免 JWT，验渠道签名 |

- **接口版本**：mall-app 的接口从请求头 `API-Version` 读版本，不带按 `1.0` 处理，老客户端不用改；不认识的版本返回 400（`ApiVersionH2Test`）
- **契约基线** `api/openapi.json` 只含 mall-app 的对外接口（`/internal/**` 已排除）；payment-service 的 `/api/payments/**` 和 inventory-service 没有导出契约，也不在 PR 的兼容性检查范围内
- **内部接口**（`/internal/**`，网关不路由，只在集群内可达）：

| 接口 | 所在服务 | 用途 |
|------|---------|------|
| `/internal/inventory/reserve`、`/confirm`、`/release`、`GET /internal/inventory/stocks` | inventory-service | 预占三件套与库存查询，契约在 `mall-inventory-api` |
| `POST /internal/inventory/buckets/{skuId}/split?count=10`、`POST /internal/inventory/buckets/{skuId}/merge` | inventory-service | 热点库存分桶与合并：大促前一天对活动 SKU 拆桶，结束后低峰合并（`deploy/promo/promo-checklist.sh split / merge`）。合并时若还有未确认的分桶预占会被拒绝，稍后重试 |
| `GET /internal/orders/{orderNo}` | mall-app | 库存的预占过期回查、支付建单时查订单，契约在 `mall-order-api` |
| `GET /internal/payments/{orderNo}`、`POST /internal/payments/{orderNo}/close` | payment-service | 查询与关闭支付单，契约在 `mall-payment-api` |
| `POST /internal/offline` | 三个服务（mall-common） | 主动从 Nacos 注销本实例；Kubernetes 的 preStop 先调它再等 15 秒，让调用方先摘掉这个实例 |
| `/internal/demo/**` | inventory-service（仅 demo profile） | 故障注入、库存查看与补货；网关在 demo profile 下把 `/api/demo/inventory/**` 转过来 |

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
├── perf/                 Gatling 压测脚本（说明见 perf/README.md）
├── simulator/            流量模拟器：演示账号经网关长期模拟真人浏览、下单、支付，让监控面板一直有数据（只用于本地 / 开发环境）
├── api/openapi.json      对外接口契约基线（只含 mall-app 的接口）
├── deploy/
│   ├── k8s/              Deployment / Rollout + AnalysisTemplate、Service、Ingress、HPA、Flyway PreSync Job、Secret 模板
│   ├── argocd/           Argo CD Application
│   ├── demo/             一条命令体验的镜像构建（容器里跑 Maven，四个服务共用）
│   ├── observability/    OTel Collector（两层）、Prometheus SLO 与组件告警、PodMonitor、Alertmanager、Grafana 业务大盘与 JVM / HTTP 通用面板；local/ 是本地抓取与 Grafana provisioning 示例
│   ├── nacos/            Nacos 配置样例，四个服务各一份加公共的 mall-common.yaml（熔断舱壁、降级开关、活动白名单、消费线程数、限流路由、结构化日志）
│   ├── redis/ rocketmq/ mysql/   redis/ 是阶段二起自建 Redis Sentinel 的配置模板（本地 compose 不用）；rocketmq/、mysql/ 是本地 compose 挂载的 Broker 配置、建 Topic 与建库脚本
│   ├── promo/            大促预案脚本（扩容、分桶与合并）
│   └── env.example、mall-evolution.service、deploy.sh   阶段一遗留：云主机上的环境变量模板、systemd 单元与逐台发布脚本，只对应 stage-1-monolith 标签，main 上不再维护
├── config/               Checkstyle 规则（checkstyle/）、本地覆盖配置模板 application-local.example.yml
├── docker-compose.yml    本地中间件（MySQL、Redis、RocketMQ、Nacos）；加 --profile demo 连四个服务一起起
├── Dockerfile            按模块构建镜像，内置 OpenTelemetry Java Agent
├── .run/                 IntelliJ IDEA 共享运行配置（中间件、四个服务、流量模拟器、mvn verify）
├── .gitleaks.toml        密钥扫描的放行规则（只放行本地占位值）
└── .github/
    ├── workflows/        verify.yml 完整验证与测试报告发布、pr.yml PR 检查（verify + 契约 diff + 密钥扫描）、static-checks.yml 两者共用的清单与告警规则校验、release.yml 发布（镜像 → GitOps）
    └── scripts/          汇总测试报告与覆盖率（collect-reports.sh）、生成报告首页（pages-index.sh）
```

## 部署文件怎么用

`deploy/` 下是生产形态的样例（本仓库是演示仓库，没有真实集群），照着用时注意哪些要自备、哪些要单独 apply：

- **`kubectl apply -k deploy/k8s`** 只包含命名空间、四个服务的工作负载、Service / Ingress、HPA 和 Flyway Job。先决条件：
  - Argo Rollouts（mall-app、inventory-service 是 `Rollout` + `AnalysisTemplate`，金丝雀分析要查 Prometheus）、Ingress Controller、metrics-server（HPA）
  - Secret：按 `deploy/k8s/secrets.example.yaml` 的键名在集群里创建（四个服务各一个，加三个 Flyway 用的 `*-ddl-secret`）；模板不在 kustomization 里，不会被 apply
  - Nacos：把 `deploy/nacos/` 下的配置导入对应命名空间。prod 下 `mall-app.yaml`、`inventory-service.yaml`、`payment-service.yaml`、`mall-gateway.yaml` 是必需的，缺了启动失败；`mall-common.yaml`（group `COMMON_GROUP`）可选
  - 清单里的镜像标签（`2.0.0`）只是占位，发布流水线会把它们改成同一次构建的 digest；`flyway-presync-job.yaml` 是 Argo CD 的 PreSync 钩子，不经 Argo CD 直接 apply 时它就是三个立即执行的普通 Job
- **需要单独 apply、不在 kustomization 里的**：
  - `deploy/observability/prometheus/podmonitor.yaml`：依赖 Prometheus Operator（`PodMonitor` CRD），放在 `monitoring` 命名空间
  - `deploy/observability/prometheus/rules/*.yml`：是 Prometheus 原生的规则文件，不是 `PrometheusRule` 资源——挂进 Prometheus 的 `rule_files`，或自己包一层 `PrometheusRule`。其中 Redis、RocketMQ 的规则依赖 redis_exporter 与 rocketmq-exporter（或云厂商的等价指标）
  - `deploy/observability/alertmanager/alertmanager.yml`：webhook 地址部署时注入，不在仓库里
  - `deploy/observability/otel-collector-agent.yaml`、`otel-collector-gateway.yaml`：依赖 `monitoring` 命名空间、Loki 与 Tempo；agent 用的 ServiceAccount `otel-collector` 及其读 Pod 元数据的 RBAC 需自备
  - `deploy/argocd/mall-evolution.yaml`：依赖 Argo CD，`repoURL` 是占位的 GitOps 仓库地址
- **发布流水线 `.github/workflows/release.yml`**：push 到 main 时默认只跑 verify。要真的构建镜像并写 GitOps 仓库，需要配置仓库变量 `DEPLOY_ENABLED=true`、`REGISTRY`、`GITOPS_REPO` 和密钥 `REGISTRY_USERNAME`、`REGISTRY_PASSWORD`、`GITOPS_TOKEN`；GitOps 仓库的 `prod/` 下要有与 `deploy/k8s` 同名的清单
- **阶段一遗留**：`deploy/env.example`、`deploy/mall-evolution.service`、`deploy/deploy.sh` 和 mall-app 的 `application-test.yml` 只对应 `stage-1-monolith` 标签（那时只有 mall-app 一个进程、部署在云主机上），文件头都标了，main 上不再维护；当时的部署流水线 `.github/workflows/main.yml` 已从 main 删除（它每次 push 都会再跑一遍 verify 并尝试部署测试机），想看就 `git checkout stage-1-monolith`
- **运行参数里值得知道的几项**（默认值在各服务的 `application.yml`，线上以 Nacos 为准）：`spring.task.scheduling.pool.size`（定时任务线程数，默认 4）、`mall.rocketmq.consumption-threads`（每个消费组的消费线程数，按「消费组数 × 线程数不超过连接池一半」取值，可用 `consumption-threads-by-group.<组名>` 单独给）、`mall.outbox.give-up-after`（本地消息重试多久后置为失败，默认 24 小时；置失败的记录要人工把 `status` 改回 0、`created_at` 改成当前时间才会重新投递）

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

已知局限（代码审查里发现、这一轮没有处理的，记在这里）：

- **匿名路由没有限流**：网关按「用户 + 路由」限流，登录、商品详情这类匿名接口走单独的路由、不挂限流（没有用户身份可按）；要防刷需要再加按 IP 或全局的限流
- **每日对账没有多实例抢占**：`ReconcileJob` 在每个 mall-app 实例上都会按时执行，靠补发与释放的幂等保证结果正确，但会白跑几遍、差错表可能重复记录；应该加一把抢占锁或交给调度平台
- **`/internal/**` 没有调用方校验**：只靠网关不路由加网络隔离，服务本身对 `/internal/**` 是放行的，集群内任何能连上的调用方都能调
- **api 模块依赖整个 mall-common**：`mall-inventory-api`、`mall-order-api`、`mall-payment-api` 为了错误码等几个类依赖了 `mall-common`，引用契约的一方会被带进 Web、安全、RocketMQ 等全部依赖；应拆出只含错误码与基础类型的 common-core
- **demo 代码和正式代码打在同一个 jar 里**：故障注入、缓存来源响应头、演示控制台只靠 `@Profile("demo")` 隔离，有的还和正式代码同包（如 `DemoCacheSourceHeader`、`DemoConsoleConfig`）；启动时已经禁止 `prod` 与 `demo` 同时激活，但更干净的做法是搬到独立的包或模块、生产镜像不打进去

## 许可证

[MIT](LICENSE)
