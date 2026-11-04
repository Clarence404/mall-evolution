package io.github.clarence404.mall.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.clarence404.mall.order.api.MarkPaidResult;
import io.github.clarence404.mall.order.api.OrderPayApi;
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

/** 支付服务：建支付单（远程查订单）、渠道回调验签后同步 markPaid，订单服务用替身，H2 跑真实 SQL。 */
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

    @MockitoBean
    OrderPayApi orderPayApi;

    @Test
    void createPaymentThenChannelCallbackMarksOrderPaid() throws Exception {
        when(orderQueryApi.getByOrderNo("18492033857208325120001"))
                .thenReturn(new OrderSummaryDTO("18492033857208325120001", 10001L, 10, new BigDecimal("25.60")));
        when(orderPayApi.markPaid(eq("18492033857208325120001"), any())).thenReturn(MarkPaidResult.PAID);

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

        verify(orderPayApi).markPaid(eq("18492033857208325120001"), any());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT status FROM t_payment WHERE payment_no = ?", Integer.class, paymentNo))
                .isEqualTo(1);
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
