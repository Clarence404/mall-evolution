users.csv 只有表头：压测前由准备脚本为压测环境的 20 万个账号签发访问令牌并写入（令牌只对压测环境有效，不提交到仓库）。
skus.csv 换成本次活动的 20 个爆款 SKU。
运行：./mvnw -pl perf gatling:test -DbaseUrl=https://<压测环境网关>
