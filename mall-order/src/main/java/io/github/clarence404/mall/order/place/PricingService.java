package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.product.api.ProductApi;
import io.github.clarence404.mall.product.api.ProductDTO;
import io.github.clarence404.mall.product.api.ProductErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 计价：请求里不传价格，按当前价格在服务端计算；通过 ProductApi 读商品，不 JOIN t_product。
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final ProductApi productApi;

    public List<PricedItem> price(PlaceOrderRequest req) {
        // 同一 SKU 出现在多行时合并数量
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        req.lines().forEach(l -> quantities.merge(l.skuId(), l.quantity(), Integer::sum));

        Map<Long, ProductDTO> products = productApi.listByIds(quantities.keySet()).stream()
                .collect(Collectors.toMap(ProductDTO::id, Function.identity()));
        return quantities.entrySet().stream()
                .map(e -> {
                    ProductDTO p = products.get(e.getKey());
                    if (p == null) {
                        throw new BizException(ProductErrorCode.PRODUCT_NOT_FOUND, "商品 " + e.getKey() + " 不存在");
                    }
                    if (!p.onShelf()) {
                        throw new BizException(ProductErrorCode.PRODUCT_NOT_FOUND, "商品 " + p.name() + " 已下架");
                    }
                    return new PricedItem(p.id(), p.name(), p.image(), p.price(), e.getValue());
                })
                .toList();
    }
}
