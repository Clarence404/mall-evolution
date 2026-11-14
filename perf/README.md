# perf：Gatling 压测

大促前在**同构压测环境**跑的下单压测脚本（`PromoSimulation`），不在本地和 CI 里执行：`./mvnw verify` 只编译它，不运行。
想让本地监控面板一直有数据，用的是另一个模块 `simulator`（速率低、长期运行、行为像真人），见根目录 README「流量模拟器」。

## 怎么跑

```bash
./mvnw -pl perf gatling:test -DbaseUrl=https://<压测环境网关>
```

## 压测前要准备的数据（`src/test/resources/`）

| 文件 | 说明 |
|------|------|
| `users.csv` | 仓库里只有表头。压测前由准备脚本为压测环境的 20 万个账号签发访问令牌并写入；令牌只对压测环境有效，不提交到仓库 |
| `skus.csv` | 换成本次活动的 20 个爆款 SKU |
| `place-order.json` | 下单请求体模板 |

压测结论（连接池、超时、分桶数等参数的来历）写在对应文章「稳定性」里，代码里的相关配置旁边有注释。
