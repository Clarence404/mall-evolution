package io.github.clarence404.mall.product.detail;

/** 商品详情这次由哪一层给出：L1 本地 Caffeine、L2 Redis、DB 回源（含 Redis 故障时直接查库）。 */
public enum DetailSource {
    L1,
    L2,
    DB
}
