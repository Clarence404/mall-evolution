-- mall_inventory 库：从 mall 拆出的库存表。存量与增量由 binlog 同步迁入，见 6_split「按服务拆库与数据迁移」
CREATE TABLE t_inventory (
    sku_id     BIGINT      NOT NULL,
    stock      INT         NOT NULL COMMENT '可售数量',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存';
