package io.github.clarence404.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.support.H2TestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 冒烟测试：整个 Spring 上下文能装配起来、Flyway 能执行；并把 springdoc 生成的 OpenAPI 导出到 target/openapi.json，
 * 供 CI 与仓库里的 api/openapi.json 做兼容性 diff。
 */
class ApplicationContextSmokeTest extends H2TestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    io.github.clarence404.mall.common.mq.RocketMqProperties rocketMqProperties;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler;

    @Test
    void contextLoadsAndExportsOpenApi() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"operationId\":\"placeOrder\"", "\"operationId\":\"cancelOrder\"");
        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    /**
     * 定时任务有自己的线程池（spring.task.scheduling.pool.size），不再全挤在默认的 1 个线程上：
     * 每秒一次的 outbox 中继、15 秒一次的指标刷新不会被几分钟一轮、还带远程调用的兜底任务（超时扫描、预占回查、对账、清理）卡住。
     */
    @Test
    void scheduledTasksDoNotShareASingleThread() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isEqualTo(4);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("sched-");
    }

    /**
     * 消费线程预算：每条消息的处理占一个数据库连接，所以「消费组数 × 每组线程数」不能超过连接池的一半，另一半留给接口请求。
     * 新增消费组时把组名加进这个列表；超了就调小 mall.rocketmq.consumption-threads 或调大连接池。
     */
    @Test
    void consumerThreadsStayWithinHalfOfTheConnectionPool() {
        java.util.List<String> groups = java.util.List.of(
                "notify-consumer",
                "shipment-consumer",
                "order-payment-consumer",
                "order-timeout-consumer",
                "points-consumer");
        int threads = groups.stream()
                .mapToInt(rocketMqProperties::consumptionThreadsFor)
                .sum();
        int pool = ((com.zaxxer.hikari.HikariDataSource) dataSource).getMaximumPoolSize();

        assertThat(threads).isLessThanOrEqualTo(pool / 2);
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
