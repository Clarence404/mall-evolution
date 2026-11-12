package io.github.clarence404.mall.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 经网关调商城接口的薄封装：JDK 自带的 HttpClient，发 JSON、收 JSON，每个响应都记进 {@link Stats}。
 *
 * <p>不抛异常：连不上、超时返回状态码 -1，由调用方决定退避；这样虚拟用户的循环里不用到处 try/catch。
 */
public final class MallApi {

    /**
     * 一次响应。
     *
     * @param status HTTP 状态码，-1 表示连不上或超时
     * @param json 响应体解析成的 JSON，不是 JSON 时为 null
     */
    public record Resp(int status, JsonNode json) {

        /**
         * 是否 2xx。
         *
         * @return 2xx 返回 true
         */
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /**
         * 是否需要退避：连不上、被限流、服务端错误。
         *
         * @return 是则 true
         */
        public boolean shouldBackOff() {
            return status < 0 || status == 429 || status >= 500;
        }

        /**
         * 取一个文本字段。
         *
         * @param field 字段名
         * @return 值，没有时返回 null
         */
        public String text(String field) {
            if (json == null || !json.has(field) || json.get(field).isNull()) {
                return null;
            }
            return json.get(field).asString();
        }
    }

    private final String baseUrl;
    private final HttpClient http;
    private final JsonMapper json;
    private final Stats stats;

    /**
     * 创建。
     *
     * @param baseUrl 网关地址，如 http://localhost:9000
     * @param json Jackson
     * @param stats 统计
     */
    public MallApi(String baseUrl, JsonMapper json, Stats stats) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.json = json;
        this.stats = stats;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * 网关地址。
     *
     * @return 去掉末尾斜杠后的地址
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 发一个请求。
     *
     * @param method GET / POST / PUT / DELETE
     * @param path 以 / 开头的路径（可带查询串）
     * @param token 访问令牌，匿名时为 null
     * @param body 请求体（会序列化成 JSON），没有时为 null
     * @param headers 额外的请求头
     * @return 响应；连不上或超时时 status = -1
     */
    public Resp send(String method, String path, String token, Object body, Map<String, String> headers) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "mall-simulator");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        headers.forEach(req::header);
        if (body != null) {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            stats.response(resp.statusCode());
            return new Resp(resp.statusCode(), parse(resp.body()));
        } catch (IOException e) {
            stats.response(-1);
            return new Resp(-1, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 停止时被打断：保留中断标记，让外层循环退出
            return new Resp(-1, null);
        }
    }

    /**
     * GET。
     *
     * @param path 路径
     * @param token 令牌，可为 null
     * @return 响应
     */
    public Resp get(String path, String token) {
        return send("GET", path, token, null, Map.of());
    }

    /**
     * POST JSON。
     *
     * @param path 路径
     * @param token 令牌，可为 null
     * @param body 请求体
     * @return 响应
     */
    public Resp post(String path, String token, Object body) {
        return send("POST", path, token, body, Map.of());
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null; // 比如回调接口返回的纯文本 success
        }
    }
}
