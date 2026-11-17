package io.github.clarence404.mall.common.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 服务间 HTTP 连接池：先记录默认配置的行为（每个目标只有 5 条连接，第 6 个并发请求在池里排队、服务端根本收不到），
 * 再验证开关生效——起一个本地 HTTP 服务，让第一个请求卡住、占住唯一的一条连接，第二个请求应当在取连接超时（100ms）后失败，
 * 而不是像默认配置那样排队等上 3 分钟——读超时管不到这一段。
 */
class HttpClientPoolConfigTest {

    private HttpServer server;
    private final ExecutorService callers = Executors.newFixedThreadPool(8);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch firstArrived = new CountDownLatch(1);
    private final AtomicInteger arrived = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            arrived.incrementAndGet();
            firstArrived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        callers.shutdownNow();
    }

    /**
     * Spring Boot 用 HttpClient 5 时不改连接池：每个目标（host:port）最多 5 条连接。6 个并发请求只有 5 个到得了服务端，
     * 第 6 个在客户端的池子里等——读超时（这里 10 秒）从拿到连接才开始算。
     */
    @Test
    void defaultPoolAllowsOnlyFiveConnectionsPerRoute() throws Exception {
        RestClient client = client(new HttpClientPoolProperties(null, null, null));
        List<CompletableFuture<String>> calls = IntStream.range(0, 6)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> client.get().uri("/slow").retrieve().body(String.class), callers))
                .toList();

        Thread.sleep(1_000);
        assertThat(arrived).hasValue(5);
        assertThat(calls).filteredOn(CompletableFuture::isDone).isEmpty();

        release.countDown();
        for (CompletableFuture<String> c : calls) {
            assertThat(c.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        }
        assertThat(arrived).hasValue(6);
    }

    @Test
    void poolWaitIsBoundedByConnectionRequestTimeout() throws Exception {
        RestClient client = client(new HttpClientPoolProperties(1, 1, Duration.ofMillis(100)));
        CompletableFuture<String> first = CompletableFuture.supplyAsync(
                () -> client.get().uri("/slow").retrieve().body(String.class));
        assertThat(firstArrived.await(5, TimeUnit.SECONDS)).isTrue(); // 唯一的一条连接已被占住

        long start = System.nanoTime();
        Throwable second =
                catchThrowable(() -> client.get().uri("/slow").retrieve().body(String.class));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(second).isInstanceOf(ResourceAccessException.class);
        assertThat(waitedMs).isBetween(80L, 3_000L); // 等的是取连接超时，不是读超时（10 秒）
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    @Test
    void emptyPropertiesLeaveBuilderUntouched() {
        var builder = ClientHttpRequestFactoryBuilder.httpComponents();

        assertThat(HttpClientPoolConfig.apply(builder, new HttpClientPoolProperties(null, null, null)))
                .isSameAs(builder);
    }

    private RestClient client(HttpClientPoolProperties props) {
        ClientHttpRequestFactory factory = HttpClientPoolConfig.apply(
                        ClientHttpRequestFactoryBuilder.httpComponents(), props)
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(1))
                        .withReadTimeout(Duration.ofSeconds(10)));
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory)
                .build();
    }
}
