package io.github.clarence404.mall.payment;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import io.github.clarence404.mall.order.api.OrderSummaryDTO;
import io.github.clarence404.mall.payment.pay.MockChannelSigner;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 支付服务：建支付单（远程查订单）、渠道回调验签后同事务写 payment-succeeded、关闭支付单，订单服务用替身，H2 跑真实 SQL。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
class PaymentFlowH2Test {

    @Autowired
    MockMvc mvc;

    @Autowired
    MockChannelSigner signer;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OrderQueryApi orderQueryApi;

    @Autowired
    io.github.clarence404.mall.payment.pay.RefundService refundService;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Test
    void createPaymentThenChannelCallbackMarksOrderPaid() throws Exception {
        when(orderQueryApi.getByOrderNo("18492033857208325120001"))
                .thenReturn(new OrderSummaryDTO("18492033857208325120001", 10001L, 10, new BigDecimal("25.60")));

        String body = mvc.perform(post("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(10001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"18492033857208325120001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("25.60"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String paymentNo = JsonPath.read(body, "$.paymentNo");

        String notify =
                "{\"paymentNo\":\"" + paymentNo + "\",\"channelTradeNo\":\"T1\",\"amount\":\"25.60\",\"sign\":\""
                        + signer.sign(paymentNo, "T1", "25.60") + "\"}";
        mvc.perform(post("/api/payments/notify/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notify))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE topic = 'payment-succeeded' AND msg_key = ?",
                        Integer.class,
                        paymentNo))
                .isEqualTo(1); // 与支付单置成功同一事务写入，由中继发出
        mvc.perform(post("/api/payments/notify/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notify))
                .andExpect(status().isOk()); // 渠道重复回调：幂等

        // 已付款的订单关不掉支付单：订单侧不取消，等 payment-succeeded
        mvc.perform(post("/internal/payments/{orderNo}/close", "18492033857208325120001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("PAID"));
        mvc.perform(post("/internal/payments/{orderNo}/close", "NOT-PAID-ORDER"))
                .andExpect(jsonPath("$").value("CLOSED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT status FROM t_payment WHERE payment_no = ?", Integer.class, paymentNo))
                .isEqualTo(1);
    }

    @Test
    void twoPaymentsOfSameOrderBothSucceedWithoutFailingTheCallback() throws Exception {
        String orderNo = "18492033857208325120002";
        when(orderQueryApi.getByOrderNo(orderNo))
                .thenReturn(new OrderSummaryDTO(orderNo, 10001L, 10, new BigDecimal("25.60")));
        String p1 = createPayment(orderNo);
        String p2 = createPayment(orderNo); // 用户换了个渠道又发起一次

        callback(p1, "T21").andExpect(status().isOk());
        callback(p2, "T22").andExpect(status().isOk()); // 原来第二条 payment-succeeded 撞 uk_topic_key，回调永远失败

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                        "SELECT msg_key FROM t_outbox WHERE topic = 'payment-succeeded' AND msg_key IN (?, ?)",
                        String.class,
                        p1,
                        p2))
                .containsExactlyInAnyOrder(p1, p2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_payment WHERE order_no = ? AND status = 1", Integer.class, orderNo))
                .isEqualTo(2);
    }

    @Test
    void lateCallbackOnClosedPaymentStillMarksSuccess() throws Exception {
        String orderNo = "18492033857208325120003";
        when(orderQueryApi.getByOrderNo(orderNo))
                .thenReturn(new OrderSummaryDTO(orderNo, 10001L, 10, new BigDecimal("25.60")));
        String paymentNo = createPayment(orderNo);
        mvc.perform(post("/internal/payments/{orderNo}/close", orderNo))
                .andExpect(jsonPath("$").value("CLOSED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT status FROM t_payment WHERE payment_no = ?", Integer.class, paymentNo))
                .isEqualTo(2);

        callback(paymentNo, "T31").andExpect(status().isOk()); // 钱已到账：status IN (0, 2) 都置成功，订单侧再决定退款

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT status FROM t_payment WHERE payment_no = ?", Integer.class, paymentNo))
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE topic = 'payment-succeeded' AND msg_key = ?",
                        Integer.class,
                        paymentNo))
                .isEqualTo(1);
    }

    @Test
    void refundRequiredRefundsOnlyThatPaymentAndIsIdempotent() throws Exception {
        String orderNo = "18492033857208325120004";
        when(orderQueryApi.getByOrderNo(orderNo))
                .thenReturn(new OrderSummaryDTO(orderNo, 10001L, 10, new BigDecimal("25.60")));
        String p1 = createPayment(orderNo);
        String p2 = createPayment(orderNo);
        callback(p1, "T41").andExpect(status().isOk());
        callback(p2, "T42").andExpect(status().isOk());

        refundService.refund(orderNo, p2);
        refundService.refund(orderNo, p2); // 重复消息

        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject("SELECT status FROM t_payment WHERE payment_no = ?", Integer.class, p1))
                .isEqualTo(1); // 第一笔照常入账
        org.assertj.core.api.Assertions.assertThat(
                        jdbc.queryForObject("SELECT status FROM t_payment WHERE payment_no = ?", Integer.class, p2))
                .isEqualTo(3); // 第二笔待退款
    }

    private String createPayment(String orderNo) throws Exception {
        String body = mvc.perform(post("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(10001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.paymentNo");
    }

    private org.springframework.test.web.servlet.ResultActions callback(String paymentNo, String tradeNo)
            throws Exception {
        return mvc.perform(post("/api/payments/notify/mock")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentNo\":\"" + paymentNo + "\",\"channelTradeNo\":\"" + tradeNo
                        + "\",\"amount\":\"25.60\",\"sign\":\"" + signer.sign(paymentNo, tradeNo, "25.60")
                        + "\"}"));
    }

    @Test
    void callbackMetricOnlyTagsKnownChannels() throws Exception {
        mvc.perform(post("/api/payments/notify/{channel}", "evil-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentNo\":\"P\",\"channelTradeNo\":\"T\",\"amount\":\"1.00\",\"sign\":\"bad\"}"))
                .andExpect(status().isUnauthorized());

        // 路径参数随便填的渠道名不进标签，归到 unknown，时序数量不会被撑爆
        org.assertj.core.api.Assertions.assertThat(
                        meterRegistry.find("pay.callback").counters())
                .allSatisfy(c -> org.assertj.core.api.Assertions.assertThat(
                                c.getId().getTag("channel"))
                        .isIn("mock", "unknown"));
        org.assertj.core.api.Assertions.assertThat(meterRegistry
                        .get("pay.callback")
                        .tags("channel", "unknown", "result", "failure")
                        .counter()
                        .count())
                .isPositive();
    }

    @Test
    void othersOrderIsNotFoundAndBadSignatureIsRejected() throws Exception {
        when(orderQueryApi.getByOrderNo("O2")).thenReturn(new OrderSummaryDTO("O2", 10002L, 10, BigDecimal.TEN));
        mvc.perform(post("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(10001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"O2\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        mvc.perform(post("/api/payments/notify/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentNo\":\"P\",\"channelTradeNo\":\"T\",\"amount\":\"1.00\",\"sign\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAY_SIGNATURE_INVALID"));
    }

    private static String bearer(long userId) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "test-only-jwt-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("mall-evolution")
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .build();
        return "Bearer "
                + encoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
    }
}
