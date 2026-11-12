package io.github.clarence404.mall.simulator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 模拟渠道回调的签名，和 payment-service 的 MockChannelSigner 一致：HMAC-SHA256(paymentNo|channelTradeNo|amount)，小写十六进制。
 *
 * <p>真实渠道的回调由渠道服务器发起；本地没有渠道，模拟器就扮演渠道，用 dev profile 的占位密钥签名后调回调接口（和演示控制台的「模拟支付」一样）。
 */
final class MockPaySigner {

    private MockPaySigner() {}

    static String sign(String secret, String paymentNo, String channelTradeNo, String amount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw =
                    mac.doFinal((paymentNo + "|" + channelTradeNo + "|" + amount).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
