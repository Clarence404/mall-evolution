package io.github.clarence404.mall.product.admin;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.product.api.ProductErrorCode;
import io.github.clarence404.mall.product.detail.ProductChangedEvent;
import io.github.clarence404.mall.product.domain.ProductMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品唯一的写入口（运营后台）。事务里只发布 ProductChangedEvent，删除缓存放在提交之后，
 * 避免「删了缓存、事务却回滚」或「删完缓存、提交前被读回旧值」。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ApplicationEventPublisher events;

    @Transactional
    public void update(long skuId, String name, BigDecimal price, Integer status) {
        if (productMapper.update(skuId, name, price, status) == 0) {
            throw new BizException(ProductErrorCode.PRODUCT_NOT_FOUND, "商品 " + skuId + " 不存在");
        }
        events.publishEvent(new ProductChangedEvent(skuId));
    }
}
