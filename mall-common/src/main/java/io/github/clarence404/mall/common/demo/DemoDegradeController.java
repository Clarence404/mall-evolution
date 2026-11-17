package io.github.clarence404.mall.common.demo;

import io.github.clarence404.mall.common.degrade.DegradeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅演示环境：不经 Nacos 直接拨动本进程的降级开关（{@link DegradeProperties}），过载实验（experiments/d-overload）用它在压测中途
 * 「打开降级」，看前后对比。
 *
 * <p>线上的做法是改 Nacos 的 mall-app.yaml，推送后 Spring Cloud 重新绑定、实时生效；本地实验不想动共用的 Nacos，才有这个接口。
 * 注意：改动只在内存里，Nacos 一旦推送任何配置变更，开关会被重新绑定回 Nacos / 配置文件里的值。只在 demo profile 下注册。
 */
@Slf4j
@RestController
@Profile("demo")
@RequestMapping("/internal/demo/degrade")
class DemoDegradeController {

    private final DegradeProperties degrade;

    DemoDegradeController(DegradeProperties degrade) {
        this.degrade = degrade;
    }

    @GetMapping
    DegradeView get() {
        return DegradeView.of(degrade);
    }

    /** 只改传了的参数，没传的保持原值。 */
    @PutMapping
    DegradeView set(
            @RequestParam(required = false) Boolean recommendEnabled,
            @RequestParam(required = false) Boolean pointsDisplayEnabled,
            @RequestParam(required = false) Integer orderListMonths) {
        if (recommendEnabled != null) {
            degrade.setRecommendEnabled(recommendEnabled);
        }
        if (pointsDisplayEnabled != null) {
            degrade.setPointsDisplayEnabled(pointsDisplayEnabled);
        }
        if (orderListMonths != null) {
            degrade.setOrderListMonths(Math.clamp(orderListMonths, 0, 120));
        }
        DegradeView view = DegradeView.of(degrade);
        log.warn("演示：降级开关已改为 {}", view);
        return view;
    }

    record DegradeView(boolean recommendEnabled, boolean pointsDisplayEnabled, int orderListMonths) {
        static DegradeView of(DegradeProperties p) {
            return new DegradeView(p.isRecommendEnabled(), p.isPointsDisplayEnabled(), p.getOrderListMonths());
        }
    }
}
