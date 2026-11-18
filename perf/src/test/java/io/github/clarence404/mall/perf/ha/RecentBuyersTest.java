package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 最近下过单的用户：攒够人数才开始给，写满后覆盖最老的。 */
class RecentBuyersTest {

    @Test
    void returnsNothingUntilEnoughBuyers() {
        RecentBuyers buyers = new RecentBuyers(4, 2);

        assertThat(buyers.pick(n -> 0)).isNull();
        buyers.add("t1");
        assertThat(buyers.pick(n -> 0)).isNull(); // 只有 1 个，不到 minSize
        buyers.add("t2");

        assertThat(buyers.size()).isEqualTo(2);
        assertThat(buyers.pick(n -> 0)).isEqualTo("t1");
        assertThat(buyers.pick(n -> n - 1)).isEqualTo("t2"); // 随机数的上界是当前人数，不会挑到空位
    }

    @Test
    void overwritesOldestWhenFull() {
        RecentBuyers buyers = new RecentBuyers(3, 1);
        for (int i = 1; i <= 5; i++) {
            buyers.add("t" + i);
        }

        assertThat(buyers.size()).isEqualTo(3);
        assertThat(new String[] {buyers.pick(n -> 0), buyers.pick(n -> 1), buyers.pick(n -> 2)})
                .containsExactlyInAnyOrder("t3", "t4", "t5");
    }

    @Test
    void invalidSizesAreRejected() {
        assertThatThrownBy(() -> new RecentBuyers(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecentBuyers(4, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecentBuyers(4, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
