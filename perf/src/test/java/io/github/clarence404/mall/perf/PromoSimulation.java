package io.github.clarence404.mall.perf;

import static io.gatling.javaapi.core.CoreDsl.ElFileBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.UUID;

/**
 * 大促下单压测：目标 7500 QPS（峰值 5000 QPS 的 1.5 倍），开放模型按到达率注入，后端变慢时压力不会自动减小，才能暴露排队问题。
 *
 * <p>压测环境预置 20 万个账号（每人每秒限流 1 次，账号要足够分散），令牌由压测准备脚本生成到 users.csv；SKU 按预估分布集中在
 * 20 个爆款（skus.csv）。只在与生产同构的压测环境执行，生产不写压测数据。
 */
public class PromoSimulation extends Simulation {

    HttpProtocolBuilder protocol = http.baseUrl(System.getProperty("baseUrl", "https://perf-api.mall.example"))
            .contentTypeHeader("application/json")
            .acceptHeader("application/json");

    FeederBuilder<String> users = csv("users.csv").circular();
    FeederBuilder<String> skus = csv("skus.csv").random();

    ScenarioBuilder placeOrder = scenario("place-order")
            .feed(users)
            .feed(skus)
            .exec(http("POST /api/orders")
                    .post("/api/orders")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .body(ElFileBody("place-order.json"))
                    .check(status().in(201, 409))); // 409 库存不足属于正常结果

    {
        setUp(placeOrder.injectOpen(
                        rampUsersPerSec(500).to(7500).during(Duration.ofMinutes(10)),
                        constantUsersPerSec(7500).during(Duration.ofMinutes(15))))
                .protocols(protocol)
                .assertions(
                        details("POST /api/orders")
                                .responseTime()
                                .percentile(99.0)
                                .lt(800),
                        details("POST /api/orders").failedRequests().percent().lt(0.1));
    }
}
