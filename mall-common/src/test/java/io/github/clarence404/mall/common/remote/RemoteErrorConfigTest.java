package io.github.clarence404.mall.common.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 下游的 ProblemDetail 被解析出来，code 保留下来，STOCK_INSUFFICIENT 才能原样回到前端。 */
class RemoteErrorConfigTest {

    @Test
    void problemDetailFromDownstreamKeepsCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteErrorConfig.applyTo(builder, JsonMapper.builder().build());
        RestClient client = builder.build();
        server.expect(requestTo("/internal/inventory/deduct"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"https://api.mall.example/problems/stock-insufficient","title":"库存不足",
                                 "status":409,"detail":"SKU 20001 库存不足","code":"STOCK_INSUFFICIENT","traceId":"abc"}
                                """));

        RemoteBizException ex = catchThrowableOfType(
                RemoteBizException.class,
                () -> client.post().uri("/internal/inventory/deduct").retrieve().toBodilessEntity());

        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.code()).isEqualTo("STOCK_INSUFFICIENT");
        assertThat(ex.getProblem().getTitle()).isEqualTo("库存不足");
    }

    @Test
    void serverErrorIsNotTreatedAsDefiniteFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteErrorConfig.applyTo(builder, JsonMapper.builder().build());
        RestClient client = builder.build();
        server.expect(requestTo("/internal/inventory/reserve"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":500,\"code\":\"SYSTEM_ERROR\"}"));

        Throwable ex = catchThrowable(() ->
                client.post().uri("/internal/inventory/reserve").retrieve().toBodilessEntity());

        // 5xx：结果未知，不是 RemoteBizException（明确失败），调用方要按未知处理并立即释放预占
        assertThat(ex).isInstanceOf(HttpServerErrorException.class).isNotInstanceOf(RemoteBizException.class);
    }
}
