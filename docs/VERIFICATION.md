# 验证总览（测试开发视角）

> 本页把 InsightForge 当作**被测系统**来读：系统只是被测对象，验证方法与结论才是交付物。
> 简历口径「秒杀平台容量与一致性验证」的每一条数字，在本页都能追到仓库内的数据文件与代码入口。

## 1. 被测对象与环境

- 被测对象：秒杀写路径（认证 → 活动准入 → Redis Lua 库存预占 → Kafka 异步结算 → MySQL 终局）。
- 环境：Docker Desktop 单机，API 实例统一限额 **4 vCPU / 1.5 GiB**（heap 1.0 GiB），MySQL 8 / Redis 7 / Kafka 同机部署，计时轮次期间停用无关容器。
- 数据出处：[docs/evaluation/local-seckill-workload-shaping-2026-08-25.json](evaluation/local-seckill-workload-shaping-2026-08-25.json) 的 `environment` / `method` 字段。

## 2. 压测方案与容量基线

- 负载设计：**200 并发，3 个测量轮次 × 每轮 1 万个独立用户**（每轮独立 JWT，不复用用户，避免"一人一单"提前拦截）；每个新进程先做一轮**排除计时**的 1 万请求预热并清空 Reservation 待结算。
- 指标口径：accepted QPS = 成功进入预占的请求 / 秒（不是入口 QPS，也不是结算 TPS）；每组取三轮**中位数**。
- A/B 结果（同镜像同限额）：

| 组 | accepted QPS（中位） | P95 | P99 |
| --- | --- | --- | --- |
| 基线（同步 ACK + 逐单查库） | 1642.45 | 155.26 ms | 198.9 ms |
| 优化（语义合并限流 + sales.tick 合并） | **1931.38（+17.59%）** | **147.89 ms** | 190.92 ms |

- 逐轮原始数据与 `deltaPercent` 见 [local-seckill-workload-shaping-2026-08-25.json](evaluation/local-seckill-workload-shaping-2026-08-25.json)。
- 压测脚本：[deploy/benchmarks/seckill_capacity.py](../deploy/benchmarks/seckill_capacity.py)、[deploy/benchmarks/http_capacity.py](../deploy/benchmarks/http_capacity.py)。

## 3. 对账断言（压完证明数据是对的）

压测结束后执行结算对账，结果记录在同一 JSON 的 `settlementAudit` 字段：

| 断言 | 字段 | 结果 |
| --- | --- | --- |
| ① 库存 − 订单数闭合 | `remainingStockPerActivity` / `soldPerActivity` | 每活动 0 / 10000，闭合 |
| ② 同一用户不重复下单 | `duplicateOrderIds` | **0** |
| ③ 失败预占全部回收 | `reservationPending` | **0** |
| ④ 无超卖 | `oversell` | false（累计 60000 单） |

代码入口：`insightforge-seckill-core` 的 [ReconcileService](../insightforge/insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/reconcile/ReconcileService.java)（产出 `ReconcileReport` / `ReconcileItem`）；单元测试 [ReconcileTest](../insightforge/insightforge-api/src/test/java/com/insightforge/api/ReconcileTest.java) 与 [ReconcileRedisCrossCheckTest](../insightforge/insightforge-api/src/test/java/com/insightforge/api/ReconcileRedisCrossCheckTest.java)。

**压测真正难的不是把 QPS 打上去，是打完能证明数据是对的。**

## 4. 瓶颈归因与优化复测

- 方法：先建基线 → 只改一处 → 同负载复测 → 归因。第一轮 A/B（[local-seckill-qps-2026-08-24.json](evaluation/local-seckill-qps-2026-08-24.json)）记录了"同步 ACK 基线 vs 异步预占 + 活动缓存"的组合结果（1027.99 → 1489.61）。
- 归因过程与每一步为什么这样改：[QPS 与容量设计](QPS-CAPACITY-DESIGN.md)、[可恢复库存预留协议](RECOVERABLE-SECKILL-PROTOCOL.md)。
- 诚实边界：08-25 报告的 `limitations` 明确写了——负载发生器与服务同宿主机，这是**本地工程 A/B，不是生产 SLO**；三轮测量不能完全消除调度与 JVM 方差；组合优化作为整体测量，**17.59% 未在两项优化间单独归因**。

## 5. RAG 与 Agent 受控评测

- RAG：40 条受控样本，Dense 检索基线 Recall@4 74.29% → 混合检索 + 意图路由 + 安全门后三项 100%。报告：[RAG 评测报告](RAG-EVALUATION-REPORT.md)；数据：[cloud-rag-2026-08-13.json](evaluation/cloud-rag-2026-08-13.json)、[cloud-rag-40-pass3-2026-08-13.json](evaluation/cloud-rag-40-pass3-2026-08-13.json)。
- Agent 编排 A/B：50 条任务（30 正向 + 20 安全反向），单 Agent P95 **6887 ms** / 475 token vs 多 Agent P95 **55008 ms** / 4237 token，据此默认走最小角色图。数据：[cloud-single-agent-50-2026-08-13.json](evaluation/cloud-single-agent-50-2026-08-13.json)、[cloud-multi-agent-50-2026-08-13.json](evaluation/cloud-multi-agent-50-2026-08-13.json)（含逐 case trace/span）。

## 6. 未跑与已知边界

- [deploy/jmeter/seckill-load-test.jmx](../deploy/jmeter/seckill-load-test.jmx) 是**已写好但未执行**的 JMeter 计划——不把"脚本存在"当作"压过"的证据。
- 更完整的"哪些数字不能夸大"清单：[README 数据边界](README.md)、[RESUME-PROJECT.md](RESUME-PROJECT.md)。
