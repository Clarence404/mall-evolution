-- 稳定性 · 热点库存分桶：只拆活动白名单 SKU，其余 SKU 继续走单行条件更新
ALTER TABLE t_inventory ADD COLUMN bucket_count TINYINT NOT NULL DEFAULT 0 COMMENT '0 不分桶';
ALTER TABLE t_inventory_reservation ADD COLUMN bucket_no TINYINT NULL COMMENT 'confirm / release 回到同一个桶';

CREATE TABLE t_inventory_bucket (
    sku_id     BIGINT      NOT NULL,
    bucket_no  TINYINT     NOT NULL,
    stock      INT         NOT NULL COMMENT '本桶可售',
    reserved   INT         NOT NULL DEFAULT 0 COMMENT '本桶已预占未确认',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id, bucket_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动 SKU 分桶库存';
