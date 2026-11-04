package io.github.clarence404.mall.inventory.api;

/** 库存展示：只用于商品页「有货 / 无货」，不能拿来判断能否扣减。 */
public record StockView(long skuId, int stock) {

    public boolean inStock() {
        return stock > 0;
    }
}
