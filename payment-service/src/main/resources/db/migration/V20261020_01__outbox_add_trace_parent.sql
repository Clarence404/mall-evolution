-- 可观测与发布：t_outbox 存 W3C traceparent，让 Trace 穿过本地消息表。纯扩展，旧版本不认识这一列，照常插入，值为空
ALTER TABLE t_outbox ADD COLUMN trace_parent VARCHAR(55) NULL COMMENT 'W3C traceparent，可为空' AFTER payload;
