package io.github.clarence404.mall.common.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdConfig {

    /**
     * 机器号没有默认值：两台机器都落到同一个默认值时，同一毫秒内会生成相同的 ID，主键冲突要到上线后才暴露。
     * 生产由环境变量 WORKER_ID 注入，本地与测试 profile 在各自的配置文件里显式写；没配就启动失败。
     */
    @Bean
    IdGenerator idGenerator(@Value("${mall.id.worker-id:}") String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("未配置雪花 ID 机器号 mall.id.worker-id（生产用环境变量 WORKER_ID 注入，每台机器不同）");
        }
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(Long.parseLong(workerId.trim()));
        Ids.use(generator);
        return generator;
    }
}
