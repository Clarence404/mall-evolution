# mall-evolution

Java Doc「项目演进」模块的配套代码：示例商城订单服务，从模块化单体一步步演进到分布式。

[![verify](https://github.com/Clarence404/mall-evolution/actions/workflows/verify.yml/badge.svg)](https://github.com/Clarence404/mall-evolution/actions/workflows/verify.yml) [测试报告](https://clarence404.github.io/mall-evolution/)

## 阶段

| 标签 | 对应文章 |
|------|---------|
| `stage-1-monolith` | [工程搭建](https://clarence404.github.io/java-doc/project/1_project_setup.html)、[数据建模](https://clarence404.github.io/java-doc/project/2_data_model.html)、[接口设计](https://clarence404.github.io/java-doc/project/3_api_design.html) |

## 本地运行

需要 JDK 21、Docker（只用于起依赖和跑集成测试）。

```bash
docker compose up -d mysql          # MySQL 8.4，占位密码 mall_dev
./mvnw -B verify                    # 格式、Checkstyle、ArchUnit、单元测试、H2 主流程测试；有 Docker 时同时跑 Testcontainers 集成测试
./mvnw -B -DskipTests package
java -jar mall-app/target/mall-app.jar   # 默认 dev profile，Flyway 建表并加载演示数据
```

演示账号：`13800000000` / `mall123`（用户 10001）。

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"mall123"}' | jq -r .accessToken)
curl -i localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"lines":[{"skuId":"20001","quantity":2}],"address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}'
```

测试分三层：纯单元测试（Mockito）；`*H2Test` / `ApplicationContextSmokeTest` 用 H2 的 MySQL 兼容模式跑 Flyway 和真实 SQL，不需要 Docker；`*IT` / `*ApiTests` 用 Testcontainers 起 MySQL 8.4，没有 Docker 时自动跳过，并发、锁相关的结论以它们为准。

接口文档：<http://localhost:8080/swagger-ui.html>（仅 dev / test 环境开启）。

## 测试报告

每次推送 `main` 或章节 / 阶段 tag，GitHub Actions（`.github/workflows/verify.yml`）都会在带 Docker 的 runner 上跑完整的 `./mvnw -B verify`（集成测试不跳过），并把报告发布到 GitHub Pages：

- 地址规律：`https://clarence404.github.io/mall-evolution/reports/<ref>/`，`<ref>` 是分支名或 tag 名，例如 [`reports/main/`](https://clarence404.github.io/mall-evolution/reports/main/)、[`reports/ch03-api-design/`](https://clarence404.github.io/mall-evolution/reports/ch03-api-design/)、[`reports/stage-1-monolith/`](https://clarence404.github.io/mall-evolution/reports/stage-1-monolith/)
- 内容：单元测试（surefire）与集成测试（failsafe）的 HTML 报告、各模块 JaCoCo 覆盖率；[报告首页](https://clarence404.github.io/mall-evolution/)列出所有已发布的 ref
- 测试失败时报告照样发布，workflow 记为失败

## 目录结构

```text
mall-evolution/
├── mall-common/      错误码、ProblemDetail 全局异常处理、雪花 ID、JWT 鉴权
├── mall-user/        用户、登录与刷新令牌
├── mall-product/     商品
├── mall-inventory/   库存扣减
├── mall-order/       下单、查询、取消与超时关单、支付单
├── mall-app/         启动模块：配置、Flyway 迁移、ArchUnit 与集成测试
├── api/openapi.json  接口契约基线，CI 做兼容性 diff
├── config/checkstyle/
├── deploy/           systemd 单元与逐台发布脚本
└── .github/workflows/
```

## 许可证

[MIT](LICENSE)
