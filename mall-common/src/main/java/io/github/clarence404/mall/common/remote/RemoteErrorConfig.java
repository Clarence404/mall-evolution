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
 * 所有 HTTP 接口客户端组共用：下游返回 4xx 时把响应体解析成 ProblemDetail，抛 {@link RemoteBizException}，表示下游明确拒绝
 * （如 STOCK_INSUFFICIENT），调用方可以放心按失败处理。
 *
 * <p>5xx 不转：下游可能已经执行了一半（比如预占写进去了、响应时才出错），结果未知，交给默认处理器抛
 * {@code HttpServerErrorException}，调用方按「结果未知」处理（下单时立即释放预占），熔断器把它记为失败。
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
        return builder.defaultStatusHandler(HttpStatusCode::is4xxClientError, (request, response) -> {
            ProblemDetail pd = problemMapper.readValue(response.getBody(), ProblemDetail.class);
            throw new RemoteBizException(response.getStatusCode(), pd); // 全局异常处理器按 code 透传
        });
    }
}
