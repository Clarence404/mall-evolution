-- 跨服务一致性 · 每日对账：自动修复覆盖不了的差错写入这里，第二天由值班同学处理
CREATE TABLE t_reconcile_diff (
    id         BIGINT       NOT NULL COMMENT '雪花 ID',
    biz_date   DATE         NOT NULL COMMENT '对账日期',
    diff_type  VARCHAR(32)  NOT NULL COMMENT '差错类型，见 ReconcileChecker.DiffType',
    biz_key    VARCHAR(64)  NOT NULL COMMENT 'order_no 或 sku_id',
    detail     VARCHAR(1024) NOT NULL DEFAULT '',
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待处理 1 已处理',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_reconcile_date (biz_date, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账差错';
