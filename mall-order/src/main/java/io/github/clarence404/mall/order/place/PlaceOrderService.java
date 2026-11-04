package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 下单应用服务：计价、幂等处理。不加 {@code @Transactional}，事务在 {@link PlaceOrderTxService}。
 */
@Service
@RequiredArgsConstructor
public class PlaceOrderService {

    private final OrderMapper orderMapper;
    private final PricingService pricingService;
    private final PlaceOrderTxService placeOrderTxService;

    public PlaceOrderResult place(long userId, String bizNo, PlaceOrderRequest req) {
        Order existing = orderMapper.selectByUserAndBizNo(userId, bizNo);
        if (existing != null) {
            return PlaceOrderResult.replayed(existing); // 重复请求，返回第一次的结果
        }
        List<PricedItem> items = pricingService.price(req); // 事务外计价
        try {
            return PlaceOrderResult.created(placeOrderTxService.create(userId, bizNo, req, items));
        } catch (DuplicateKeyException e) {
            // 并发的两个请求都通过了前面的查询，后插入的撞唯一键，事务已整体回滚，库存也已归还
            return PlaceOrderResult.replayed(orderMapper.selectByUserAndBizNo(userId, bizNo));
        }
    }
}
