-- 拆分过渡期：关单后远程归还库存的重试表，归 mall-order。与订单状态更新同一个本地事务写入，归还成功后删除；
-- 失败的由 StockRestorer 定时按退避重试，库存服务按 order_no 去重，重复归还不会多加库存
CREATE TABLE t_stock_restore_task (
    order_no      VARCHAR(32) NOT NULL,
    stock_lines   TEXT        NOT NULL COMMENT 'JSON：[{skuId, quantity}]',
    retry_count   INT         NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_no),
    KEY idx_next_retry (next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '关单后待归还库存（拆分过渡期）';
