package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderNoGeneratorTest {

    OrderNoGenerator generator = new OrderNoGenerator();

    @Test
    void embedsLow10BitsOfUserId() {
        String orderNo = generator.next(10001L);

        assertThat(orderNo).hasSize(23).containsOnlyDigits();
        assertThat(OrderNoGenerator.userGene(orderNo)).isEqualTo((int) (10001L & 1023));
        assertThat(orderNo).endsWith(String.format("%04d", 10001L & 1023));
    }

    @Test
    void sameUserAlwaysMapsToSameGene() {
        assertThat(OrderNoGenerator.userGene(generator.next(123_456_789L)))
                .isEqualTo(OrderNoGenerator.userGene(generator.next(123_456_789L)));
    }

    @Test
    void legacy19DigitOrderNoHasNoGene() {
        assertThat(OrderNoGenerator.userGene("1849203385720832512")).isEqualTo(-1);
    }
}
