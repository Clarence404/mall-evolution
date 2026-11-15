-- 本地消息表失败记录的重新投递：新增 retry_since，记「这一轮重试从什么时候开始算」。
-- 中继判断「重试满 mall.outbox.give-up-after（默认 24 小时）仍没发出去就置失败」时，用 COALESCE(retry_since, created_at)：
--   没重投过的记录 retry_since 为空，仍从 created_at 起算，行为不变；
--   运营调重投接口（POST /api/admin/outbox/failed/**）时把它写成当前时间，放弃计时从重投时刻重新算，created_at 保持「消息写入时间」的原意不动。
-- 纯扩展：可为空、没有默认值，旧版本不认识这一列，照常插入与重试；滚动发布期间新旧版本可以并存
ALTER TABLE t_outbox ADD COLUMN retry_since DATETIME(3) NULL COMMENT '最近一次人工重投的时间，为空表示没重投过；放弃计时从它（为空则从 created_at）起算' AFTER next_retry_at;
