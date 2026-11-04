package io.github.clarence404.mall.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 每日对账：从三个库的只读从库按 order_no 拉取前一天变动的数据。这是全系统唯一允许跨库读的地方。
 */
@ConfigurationProperties(prefix = "mall.reconcile")
public record ReconcileProperties(boolean enabled, Source mall, Source inventory, Source payment) {

    /** 只读从库的连接信息，密码由 Secret 注入。 */
    public record Source(String url, String username, String password) {}
}
