package io.github.clarence404.mall.product.detail;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 仅演示环境：把商品详情命中的缓存层写进响应头 {@code X-Cache: L1|L2|DB}，演示控制台据此展示两级缓存的效果。
 *
 * <p>只在 demo profile 下注册；生产与本地开发的响应里没有这个头。
 */
@Component
@Profile("demo")
class DemoCacheSourceHeader implements DetailSourceListener {

    static final String HEADER = "X-Cache";

    @Override
    public void onLookup(long skuId, DetailSource source) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletResponse response = attrs.getResponse();
            if (response != null && !response.isCommitted()) {
                response.setHeader(HEADER, source.name());
            }
        }
    }
}
