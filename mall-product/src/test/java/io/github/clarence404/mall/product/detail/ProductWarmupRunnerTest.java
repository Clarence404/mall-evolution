package io.github.clarence404.mall.product.detail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.product.domain.ProductMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductWarmupRunnerTest {

    ProductMapper mapper = mock(ProductMapper.class);
    ProductDetailService detailService = mock(ProductDetailService.class);
    ProductWarmupRunner runner = new ProductWarmupRunner(mapper, detailService);

    @Test
    void loadsTopProductsIntoLocalCacheBeforeReady() {
        ReflectionTestUtils.setField(runner, "topProducts", 2);
        when(mapper.selectHotSkuIds(2)).thenReturn(List.of(20001L, 20002L));

        runner.run(null);

        verify(detailService).get(20001L);
        verify(detailService).get(20002L);
    }

    @Test
    void zeroDisablesWarmup() {
        ReflectionTestUtils.setField(runner, "topProducts", 0);

        runner.run(null);

        verifyNoInteractions(mapper, detailService);
    }
}
