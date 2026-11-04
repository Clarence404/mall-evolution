package io.github.clarence404.mall.product.api;

import java.util.Collection;
import java.util.List;

/**
 * 商品模块对外接口。其他模块只能通过它读取商品，不能直接查 t_product。
 */
public interface ProductApi {

    /** 按 sku_id 批量查询，查不到的 id 不出现在结果里。 */
    List<ProductDTO> listByIds(Collection<Long> skuIds);
}
