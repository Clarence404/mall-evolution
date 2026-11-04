package io.github.clarence404.mall.product.detail;

/** 商品已修改。ProductService 在事务里发布，缓存删除放在提交之后。 */
public record ProductChangedEvent(long skuId) {}
