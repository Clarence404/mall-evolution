package io.github.clarence404.mall.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.inventory.api.StockView;
import io.github.clarence404.mall.support.H2TestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** demo profile 下商品详情带 X-Cache 头：测试里没有 Redis，第一次回源（DB），第二次命中本地缓存（L1）。 */
@ActiveProfiles("demo")
@TestPropertySource(properties = "mall.order.timeout-scan-interval=1h") // 不让 demo 的 20 秒兜底扫描影响同一个 H2 库里的其他测试
class DemoCacheHeaderH2Test extends H2TestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void detailCarriesCacheSourceHeader() throws Exception {
        Mockito.when(inventoryApi.listStocks(List.of(20002L))).thenReturn(List.of(new StockView(20002L, 1000)));
        mvc.perform(get("/api/products/20002")).andExpect(status().isOk()).andExpect(header().string("X-Cache", "DB"));
        mvc.perform(get("/api/products/20002")).andExpect(status().isOk()).andExpect(header().string("X-Cache", "L1"));
    }
}
