package io.github.clarence404.mall.common.outbox;

import io.github.clarence404.mall.common.security.LoginUser;
import io.github.clarence404.mall.common.web.CursorResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台：本地消息表失败记录的查看与重新投递。
 *
 * <p><b>谁能调</b>：路径在 /api/admin/** 下，访问令牌要带 ADMIN 角色（见 SecurityConfig），普通用户 403、未登录 401，
 * 和后台改价接口 /api/admin/products/** 同一套规则。
 *
 * <p><b>放在 mall-common 的原因</b>：t_outbox 与中继都在 mall-common，mall-app、inventory-service、payment-service
 * 各有一张自己的 t_outbox。这个 Controller 跟着 mall-common 被三个服务扫描到，三个服务都有这组接口，
 * 各管各的表——调哪个服务，处理的就是哪个服务的失败记录（OutboxFailedRecords 告警的 application 标签说明是哪个）。
 *
 * <p><b>怎么到达</b>：没有新增网关路由。
 *
 * <ul>
 *   <li>mall-app：网关兜底路由 /api/** 本来就转给它，所以经网关 {@code /api/admin/outbox/**} 可达，沿用那条路由的每用户限流
 *   <li>inventory-service、payment-service：网关不路由（库存服务本来就不对外，支付只开了 /api/payments/**），
 *       在集群内直接调服务地址，同样要带 ADMIN 的访问令牌（三个服务用同一个 JWT 密钥，从网关登录拿到的令牌通用）。
 *       不为低频的运维操作多开两条对外路由；和「大促拆桶在集群内直接调库存服务」是同一个做法，只是这里多一层角色校验
 * </ul>
 */
@RestController
@RequiredArgsConstructor
class OutboxAdminController {

    private final OutboxAdminService outboxAdminService;

    /** 列出失败记录（新的在前，游标分页）。只返回定位问题需要的字段，不返回消息体。 */
    @Operation(operationId = "adminListFailedOutbox")
    @GetMapping("/api/admin/outbox/failed")
    CursorResult<FailedOutboxView> listFailed(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return outboxAdminService.listFailed(topic, cursor, size);
    }

    /** 重投单条：id 取自列表。记录不存在 404；存在但已不是失败状态返回 redriven = 0（幂等，不报错）。 */
    @Operation(operationId = "adminRedriveFailedOutbox")
    @PostMapping("/api/admin/outbox/failed/{id}/redrive")
    OutboxRedriveResult redriveOne(@AuthenticationPrincipal LoginUser operator, @PathVariable long id) {
        return outboxAdminService.redriveOne(id, operator.id());
    }

    /**
     * 批量重投：请求体可以不传（等于 {@code {}}），即「全部失败记录、最多 100 条」；
     * 响应里 remaining 大于 0 时再调一次，直到为 0。
     */
    @Operation(operationId = "adminRedriveFailedOutboxBatch")
    @PostMapping("/api/admin/outbox/failed/redrive")
    OutboxRedriveResult redriveBatch(
            @AuthenticationPrincipal LoginUser operator, @RequestBody(required = false) @Valid RedriveRequest req) {
        return req == null
                ? outboxAdminService.redriveBatch(null, null, operator.id())
                : outboxAdminService.redriveBatch(req.topic(), req.limit(), operator.id());
    }

    /**
     * 批量重投的条件。
     *
     * @param topic 只重投这个 Topic 的失败记录；不传表示全部
     * @param limit 最多重投多少条，不传取 100；超过 500 直接 400，而不是悄悄按 500 处理——调用方以为处理了更多会漏掉剩下的
     */
    record RedriveRequest(
            @Size(max = 64) String topic,

            @Min(1) @Max(OutboxAdminService.MAX_REDRIVE_LIMIT)
            Integer limit) {}
}
