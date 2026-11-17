package io.github.clarence404.mall.common.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.common.degrade.DegradeProperties;
import org.junit.jupiter.api.Test;

/** 直接拨动降级开关：只改传了的参数，订单列表月数限制在 0–120。 */
class DemoDegradeControllerTest {

    @Test
    void onlyChangesGivenSwitches() {
        DegradeProperties degrade = new DegradeProperties();
        DemoDegradeController controller = new DemoDegradeController(degrade);

        var view = controller.set(false, null, 3);

        assertThat(view.recommendEnabled()).isFalse();
        assertThat(view.pointsDisplayEnabled()).isTrue(); // 没传，保持原值
        assertThat(view.orderListMonths()).isEqualTo(3);
        assertThat(degrade.isRecommendEnabled()).isFalse();

        controller.set(true, false, 999);
        assertThat(controller.get()).isEqualTo(new DemoDegradeController.DegradeView(true, false, 120));
    }
}
