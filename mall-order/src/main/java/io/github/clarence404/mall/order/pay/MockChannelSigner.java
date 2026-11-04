package io.github.clarence404.mall.order.pay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 演示用的「模拟渠道」签名：HMAC-SHA256(paymentNo|channelTradeNo|amount)。真实渠道换成对方 SDK 的验签。
 */
@Component
public class MockChannelSigner {

    private final byte[] secret;

    public MockChannelSigner(@Value("${mall.pay.mock.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String paymentNo, String channelTradeNo, String amount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw =
                    mac.doFinal((paymentNo + "|" + channelTradeNo + "|" + amount).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String paymentNo, String channelTradeNo, String amount, String sign) {
        byte[] expected = sign(paymentNo, channelTradeNo, amount).getBytes(StandardCharsets.UTF_8);
        return sign != null && MessageDigest.isEqual(expected, sign.getBytes(StandardCharsets.UTF_8));
    }
}
