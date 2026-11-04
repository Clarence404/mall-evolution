-- 归还去重：同一订单只归还一次。mall-app 关单后的归还会重试（超时不知道成没成），按 order_no 挡掉重复
CREATE TABLE t_stock_restore_log (
    order_no   VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '已归还的订单（拆分过渡期）';
