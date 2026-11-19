package io.github.clarence404.mall.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * 每个带登录态的请求都要做一次的事：校验访问令牌（HS256 验签 + 校验签发者与有效期），再把它转成登录用户。
 * 解码器就是 {@link SecurityConfig#jwtDecoder} 建出来的那一个，令牌的格式和压测脚本签发的一样。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class JwtDecodeBenchmark {

    private static final String SECRET = "benchmark-only-secret-0123456789abcdef";

    private JwtDecoder decoder;
    private String token;

    @Setup
    public void setUp() throws Exception {
        JwtProperties props = new JwtProperties(SECRET, "mall-evolution", null, null);
        decoder = new SecurityConfig().jwtDecoder(props);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload =
                b64.encodeToString(("{\"iss\":\"mall-evolution\",\"sub\":\"800000001\",\"roles\":[\"USER\"],\"iat\":"
                                + now + ",\"exp\":" + (now + 7200) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(props.secretKey());
        token = header + "." + payload + "."
                + b64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }

    /** 只校验令牌。 */
    @Benchmark
    public Jwt decode() {
        return decoder.decode(token);
    }

    /** 校验令牌并转成登录用户（过滤器里实际做的两步）。 */
    @Benchmark
    public Object decodeAndConvert() {
        return LoginUserAuthentication.converter().convert(decoder.decode(token));
    }
}
