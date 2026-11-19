package io.github.clarence404.mall.order.demo;

import io.github.clarence404.mall.order.demo.DemoPerfService.BatchResult;
import io.github.clarence404.mall.order.demo.DemoPerfService.OrderRow;
import io.github.clarence404.mall.order.demo.DemoPerfService.OrderWithItems;
import io.github.clarence404.mall.order.demo.DemoPerfService.SeedResult;
import io.github.clarence404.mall.order.demo.DemoPerfService.SeedStatus;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅演示环境：高性能实验「数据访问」的接口，实现见 {@link DemoPerfService}。路径在 /internal/** 下，网关不路由、不要登录，
 * 压测直接打 mall-app 的端口——这组实验比的是 SQL 的写法，不经过网关和鉴权，免得它们的耗时混进来。
 */
@Hidden
@RestController
@Profile("demo")
@RequiredArgsConstructor
@RequestMapping("/internal/demo/perf")
class DemoPerfController {

    private final DemoPerfService service;

    /** 灌第 [fromRound, toRound) 轮种子订单；一次别灌太多轮，一轮 5000 单约 1 秒。 */
    @PostMapping("/seed")
    SeedResult seed(
            @RequestParam int fromRound,
            @RequestParam int toRound,
            @RequestParam(defaultValue = "4000") int users,
            @RequestParam(defaultValue = "1000") int hotPerRound) {
        return service.seed(fromRound, toRound, users, hotPerRound);
    }

    @GetMapping("/seed-status")
    SeedStatus seedStatus(@RequestParam(defaultValue = "4000") int users) {
        return service.seedStatus(users);
    }

    @PostMapping("/batch-insert")
    BatchResult batchInsert(
            @RequestParam String mode, @RequestParam int rows, @RequestParam(defaultValue = "1000") int batchSize) {
        return service.batchInsert(mode, rows, batchSize);
    }

    @GetMapping("/orders/page")
    List<OrderRow> page(
            @RequestParam String mode,
            @RequestParam long userId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long cursorId) {
        return service.page(mode, userId, offset, size, cursorId);
    }

    /** 游标分页翻到第 offset 行时该带的游标（字符串，免得 JS 丢精度）；offset 为 0 时 cursorId 为空串。 */
    @GetMapping("/orders/cursor-at")
    Map<String, String> cursorAt(@RequestParam long userId, @RequestParam int offset) {
        Long id = service.cursorAt(userId, offset);
        return Map.of("cursorId", id == null ? "" : String.valueOf(id));
    }

    @GetMapping("/orders/with-items")
    List<OrderWithItems> listWithItems(
            @RequestParam String mode, @RequestParam long userId, @RequestParam(defaultValue = "20") int size) {
        return service.listWithItems(mode, userId, size);
    }
}
