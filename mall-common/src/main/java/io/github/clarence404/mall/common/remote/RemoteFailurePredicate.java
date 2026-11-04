package io.github.clarence404.mall.common.remote;

import java.util.function.Predicate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 熔断器的 record-failure-predicate：只把超时、连接失败和 5xx 记为失败。
 *
 * <p>4xx 是正常的业务结果，STOCK_INSUFFICIENT 不能计入失败，否则爆款卖完时熔断器会误以为库存服务坏了。
 */
public class RemoteFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable t) {
        if (t instanceof RemoteBizException remote) {
            return remote.getStatus().is5xxServerError();
        }
        return t instanceof ResourceAccessException // 连接失败、读超时
                || t instanceof HttpServerErrorException;
    }
}
