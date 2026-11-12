package io.github.clarence404.mall.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MockPaySignerTest {

    @Test
    void sameAsPaymentServiceMockChannelSigner() {
        // 期望值用 Python 的 hmac.new(secret, b"P1|T1|12.80", sha256).hexdigest() 独立算出
        assertThat(MockPaySigner.sign("dev-only-mock-pay-secret", "P1", "T1", "12.80"))
                .isEqualTo("349da05d11dfd60d9c5e037f2cbd868a37aca1aaff0ddd0430ecef4fbc87c966");
    }
}
