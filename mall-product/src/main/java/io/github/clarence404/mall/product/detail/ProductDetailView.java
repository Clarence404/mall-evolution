package io.github.clarence404.mall.product.detail;

/**
 * 商品详情：名称、主图、价格（字符串输出）。record 不可变，放进 L1 后不会被调用方改掉；详情富文本和图片走 CDN，不进缓存。
 */
public record ProductDetailView(String skuId, String name, String image, String price, boolean onShelf) {}
