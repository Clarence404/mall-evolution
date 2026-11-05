package io.github.clarence404.mall.product.detail;

/** 商品详情每次查询命中哪一层的回调。生产不注册实现，只有演示环境用它把命中层写进响应头。 */
@FunctionalInterface
public interface DetailSourceListener {

    DetailSourceListener NONE = (skuId, source) -> {};

    void onLookup(long skuId, DetailSource source);
}
