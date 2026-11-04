package io.github.clarence404.mall.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.support.H2TestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 没有 Redis 时商品详情降级为直接回源：接口照常可用（匿名可访问）。 */
class ProductDetailH2Test extends H2TestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void detailFallsBackToDbWhenRedisIsDown() throws Exception {
        mvc.perform(get("/api/products/20001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.skuId").value("20001"))
                .andExpect(jsonPath("$.product.price").value("39.90"))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    void missingSkuReturns404AndIllegalSkuReturns400() throws Exception {
        mvc.perform(get("/api/products/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/products/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
