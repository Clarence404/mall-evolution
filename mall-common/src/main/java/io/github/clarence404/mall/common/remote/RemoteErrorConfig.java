package io.github.clarence404.mall.common.remote;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 所有 HTTP 接口客户端组共用：下游返回 4xx / 5xx 时把响应体解析成 ProblemDetail，抛 {@link RemoteBizException}。
 *
 * <p>写接口（deduct、restore、markPaid 等）不开自动重试，超时按失败处理；超时值按组在配置里显式写。
 */
@Configuration(proxyBeanMethods = false)
public class RemoteErrorConfig {

    @Bean
    RestClientHttpServiceGroupConfigurer remoteErrorConfigurer(JsonMapper jsonMapper) {
        return groups -> groups.forEachClient((group, builder) -> applyTo(builder, jsonMapper));
    }

    static RestClient.Builder applyTo(RestClient.Builder builder, JsonMapper jsonMapper) {
        // ProblemDetail 的扩展字段（code、traceId）靠 @JsonAnySetter 落进 properties，要挂上 Spring 提供的 mixin，
        // 否则反序列化时 code 被丢掉，前端就收不到 STOCK_INSUFFICIENT
        JsonMapper problemMapper = jsonMapper
                .rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
        return builder.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
            ProblemDetail pd = problemMapper.readValue(response.getBody(), ProblemDetail.class);
            throw new RemoteBizException(response.getStatusCode(), pd); // 全局异常处理器按 code 透传
        });
    }
}
