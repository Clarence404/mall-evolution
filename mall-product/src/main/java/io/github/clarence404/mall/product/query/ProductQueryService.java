package io.github.clarence404.mall.product.query;

import io.github.clarence404.mall.product.api.ProductApi;
import io.github.clarence404.mall.product.api.ProductDTO;
import io.github.clarence404.mall.product.domain.Product;
import io.github.clarence404.mall.product.domain.ProductMapper;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 下单计价直接查 t_product，价格以数据库为准。 */
@Service
@RequiredArgsConstructor
public class ProductQueryService implements ProductApi {

    private final ProductMapper productMapper;

    @Override
    public List<ProductDTO> listByIds(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return productMapper.selectByIds(skuIds).stream()
                .map(p -> new ProductDTO(
                        p.getId(), p.getName(), p.getImage(), p.getPrice(), p.getStatus() == Product.ON_SHELF))
                .toList();
    }
}
