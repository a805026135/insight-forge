# InsightForge 规划审查与风险分析（分析说明文档）

> **文档定位**：本文是"提出问题"的分析说明，不是修正方案。职责是**指出 plan / spec / phase 文档与已实现代码之间的落差、真实缺陷、未来暴雷点与可优化项**，供评审与后续决策使用。所有结论均来自对 `specs/insightforge/*` 规划文档与 `insightforge/*` 实际源码的交叉核对（截至 2026-08-07，Phase 0–11 全部标记"已落地"、全项目 79 单测绿）。
> **不在此文档范围**：具体代码改动、修复实现步骤。

---

## 0. 审查范围与项目当前状态

- **审查对象**：`spec.md`(v0.9)、`plan.md`、`tasks.md`、`analysis.md`、`agent-observability-evaluation.md`、`glossary.md`、Phase 0–11 教程，以及 `insightforge` 各模块真实源码。
- **当前状态（文档口径）**：11 个 Phase 全部标记 `[x] 已落地`；`rule-engine` 22 测试、`api` 35 测试、`agent-core` 22 测试，累计 79 单测全绿。
- **核心结论提前说**：规划完整、单测覆盖充分，但**三大支柱（高并发秒杀 / Agent 离线规划 / 在线规则引擎）在运行代码层面从未接线**，目前是三个各自靠桩自测的"概念验证（PoC）"，而非一个端到端集成的系统。这是下文所有问题的总纲。

---

## 1. 核心问题：三大支柱未集成（三个 PoC，不是一套系统）

项目最大的差异化叙事是"Agent 离线生成护栏 DSL → 确定性规则引擎在线执行 → 与用户抢购**争用同一库存 / 同一把锁**"。但运行代码里这条链路是断的。

### 1.1 证据（均经读代码确认）

| 检查项 | 实际情况 | 出处 |
|---|---|---|
| 模块依赖 | `seckill-core` / `api` 的 `pom.xml` **不依赖** `rule-engine` | grep 无 `rule-engine` 引用 |
| 事件流接线 | 全仓**唯一** `@KafkaListener` 是 `OrderCreatedConsumer`（消费 `order.created`）；`sales.tick` 主题仅在 `KafkaTopicConfig` 声明，**无任何生产者、无任何消费者** | `KafkaTopicConfig.java:15`；`OrderCreatedConsumer.java:55` |
| 规则引擎调用方 | `RuleEngineService` / `ControlInstructionExecutor` **仅在单元测试里被调用**，运行期无调用方 | grep（排除 test 与 rule-engine 自身）无命中 |
| 库存底座 | `StockSegmentService` 是 JVM 内存 `ConcurrentHashMap`；`ControlInstructionExecutor` 的价表也是内存 `ConcurrentHashMap` | `StockSegmentService.java:31`；`ControlInstructionExecutor.java:27` |
| 锁底座 | `LocalLockService` 为本地锁桩；其锁 key 命名空间 `a:{aid}:s:{skuId}` 与秒杀路径 Redis key（`stock:a:...` / `lock:a:...`）**完全隔离** | `ControlInstructionExecutor.java:83` |

### 1.2 风险

- **核心卖点在运行代码里不成立**：规则引擎操作的是一份与真实秒杀库存毫无关系的"内存影子库存"。所谓"Agent 控盘与用户抢购争用同一库存 / 同一把锁"，当前只是逻辑原型，没有一条真实链路能演示"AI 控盘如何改写真实库存"。
- **架构图与实现脱节**：spec §3.3 / §12 描述的"用户扣减 ↔ 规则引擎调拨争用同一 Redisson 锁"在代码层面无对应实现——用户路径走 Redis Lua（无锁），规则引擎走本地锁 + 内存库存，两者既不共享锁也不共享库存底座。
- **模块化"完成度幻觉"**：每个模块都能在桩环境下自测通过，给人"系统可用"的印象，但缺少把它们串起来的集成缝（seckill 路径产出 `sales.tick` → rule-engine worker 消费并写回真实 Redis 库存/价格 → 共享 Redisson 锁）。

### 1.3 建议方向（仅指出，不展开实现）

建立集成缝：在秒杀扣减成功后由 `SeckillService` 发 `sales.tick`；在 rule-engine 侧加 `sales.tick` 消费者驱动 `RuleEngineService`，并把 `LocalLockService` / `StockSegmentService` / 内存价表替换为 Redisson + Redis 实现；补一条端到端集成测试。

---

## 2. 验证真空：79 单测 ≠ 系统能跑

- **零端到端验证**：没有任何一条测试把"建活动 → arm 对账 → live → 用户抢购(Redis) → 规则引擎消费 tick → 调拨 → 大屏"串起来。各模块均在桩环境下自测（rule-engine 用 `InMemoryPolicySource` + 本地锁 + 内存库存；eval 用内存指标源）。
- **大量"⚠️ 沙箱未实跑"**：Phase 10/11 明确标注前端（无浏览器验证）、Grafana（无容器验证）、Docker（无守护进程）、JMeter（无运行中服务）、CI（无 runner）均为"写就未实跑"。
- **真实 Redis 并发测试被跳过**：`SeckillRedisLuaTest` 在沙箱靠 `Assumptions` 跳过，真实高并发防超卖无 CI 守护（仅在读者本机跑）。
- **风险**：文档口径的"已落地"在严格意义下是"代码写了 + 单测绿"，不是"端到端验证过"。面试现场演示或大促上线前，未经集成的系统存在未知集成故障。

---

## 3. 真实代码缺陷（已实现部分，非规划问题）

以下问题与 Phase 9–11 无关，是 Phase 0–8 已落地代码中的真实缺陷，且后续 Phase 未触碰。

### 3.1 异步订单状态与状态机枚举不一致（建议优先处理）
- **现象**：`OrderCreatedConsumer.java:94` 将订单状态写死为字面量 `"CREATED"`，而 Phase 6 状态机枚举首态为 `CREATE_ORDER`。
- **风险**：`OrderStatus.from("CREATED")` 会抛"未知订单状态"异常，导致**经异步快路径创建的订单，走 `OrderController` 的 cancel / pay 直接 500**。Phase 4（消费者）早于 Phase 6（状态机），二者从未对齐。

### 3.2 结算对账只查 DB、不交叉核对 Redis，且为恒等式
- **现象**：`ReconcileService.reconcileFinalStock` 以 `总库存 − 成功单数` 与 DB 的 `stock` 比较；而 `stock = 总库存 − sold` 在每次扣减/回补时恒成立，该公式**永远通过**。且该方法完全不读 Redis。
- **风险**：LIVE 期间 Redis 才是权威库存；一旦 Redis↔DB 漂移（设计上 DB 同步本就可能落后于 Redis），结算对账**看不见**。这与 spec"结算对账证明没超卖"的强叙事不符，对账实际只能发现手动篡改类脏数据，不能发现双写漂移。

### 3.3 订单幂等键隐含"每用户每 SKU 限一单"，未声明
- **现象**：`SeckillService.buildOrderId`（`:172`）为 `OF-{activityId}-{skuId}-{userId}`，**不含 qty**。
- **风险**：用户改数量重试、或同一 SKU 二次合法购买，都会被判定为重复单返回旧 orderId。这可能是有意的业务规则（秒杀常限 1 单），但**规划文档从未说明**，易在评审/面试中被当作缺陷或造成误用。

---

## 4. 评估"自证"问题（Phase 11 A/B）

- **现象**：`InMemoryBusinessMetricsSource` 用**硬编码种子**（人工 82%/28% vs AI 95%/36%）让 A/B "证明 AI 控盘胜"（售罄率 +13pct、毛利 +8pct）。`BusinessMetricsSource` 注释明确"生产环境换成读真实表"。
- **风险**：该结论是**构造出来**的，并非规则引擎在真实或模拟秒杀中跑出的结果。eval harness 不驱动规则引擎，只对比两条预先填好的指标集。在"评估闭环"这一项目重点模块上，存在"用种子数据自证有效"的观感风险。

---

## 5. 文档与叙事失真（规划文档需与代码对齐）

| 失真项 | 文档口径 | 代码实际 | 出处 |
|---|---|---|---|
| DSL Schema | spec §13.2 / plan §4.5 为**嵌套结构**（`trigger`/`condition`/`bounds`/`human_gate`），样例字段 `from_pool`/`max_units_pct`/`delta_pct` | Phase 8/9 实际为**扁平 record** `(id, metric, operator, threshold, action, params)`，字段 `repriceTo`/`costFloor`/`amount`；`DslToDroolsTranslator` 翻译的是扁平版 | spec §13.2；`GuardrailRule.java`；`DslToDroolsTranslator.java` |
| Drools 版本 | spec §5.5 / plan 决策 #5 写 **Drools 7.x** | 代码（探针与编译）使用 **Drools 9** API | `DroolsProbeTest.java`；spec §5.5 |
| analysis.md 时效 | 头部"已对齐 v0.6"，§6 仍将**已定的 Drools** 列为开放问题 | Drools 早已在决策 #5 选定 | `analysis.md` 头部与 §6 |
| "走同一把锁"叙事 | spec/glossary 反复强调用户扣减与规则引擎控盘"争用同一 Redisson 锁" | 用户路径 Redis Lua（无锁）；规则引擎本地锁 + 内存库存；二者锁/库存底座均隔离 | `SeckillService.java`；`ControlInstructionExecutor.java` |
| "JSON Schema 校验 DSL" | spec §5.5 强调"纯 Java（Jackson 反序列化 + Schema 校验）" | `DslCompiler` 直接校验 Java record 对象，无 Jackson 反序列化 / JSON Schema 校验路径 | `DslCompiler.java` |

---

## 6. 次要遗留：规划超前于实现的部分

以下问题 spec/架构图有描述，但在任何 Phase 均未落地（或仅为内存原型），属于"架构叙事 > 实现"：

- **Redisson 在热路径实际零使用**：全仓 grep `getLock/tryLock/RLock` 无命中；防超卖由 Redis Lua + DB 条件更新承担。Phase 3 教程展示的 Redisson 锁代码已被 Phase 4 替换，但 spec/glossary 仍把 Redisson 列为核心"互斥心脏"。
- **分段锁 / pending_stock / 预测性调拨**：仅在 `RedisKeys` 键名常量、桩参数与注释中出现；`StockSegmentService` 是内存原型，生产换 Redis 的接线未做。
- **spec 架构图里的 Canal / Nginx+Lua / Sentinel / Milvus / WebSocket 大屏**：全部未在任何 Phase 落地。Milvus 仍是抛 `UnsupportedOperationException` 的桩；WebSocket 大屏在 Phase 10，但无浏览器验证。

---

## 7. 问题清单汇总（按严重度）

| 严重度 | 问题 | 类别 |
|---|---|---|
| 🔴 高 | 三大支柱未集成：规则引擎未消费真实 `sales.tick`、未写回真实库存、与秒杀数据面完全隔离 | 架构集成缺失 |
| 🔴 高 | 零端到端验证 + 大量"未实跑" ⚠️，79 单测不能证明系统集成可用 | 验证真空 |
| 🟠 中 | 异步订单 `"CREATED"` 与状态机 `CREATE_ORDER` 不一致 → 异步单 cancel/pay 500 | 真实 bug |
| 🟠 中 | 结算对账只查 DB 且为恒等式，不交叉核对 Redis，发现不了双写漂移 | 正确性/诚实 |
| 🟠 中 | A/B 评估用硬编码种子"自证"AI 胜，未真实驱动规则引擎 | 评估 rigor |
| 🟡 低 | DSL Schema（spec 嵌套 vs 实现扁平）、Drools 版本（7.x vs 9）、analysis.md 过期、"同一把锁"叙事失真 | 文档失真 |
| 🟡 低 | 幂等键隐含限一单未声明；Redisson 热路径未用；分段/pending/预测性仅内存原型；Canal/Nginx/Sentinel/Milvus/WS 未落地 | 规划超前实现 |

---

## 8. 核对依据（可复现）

本文结论来自以下确定性核对，非推测：
- 源码全文 grep：`getLock/tryLock/RLock`、`@KafkaListener/sales.tick`、`RuleEngineService` 调用方、`rule-engine` 模块依赖引用。
- 关键文件逐行阅读：`SeckillService.java`、`OrderCreatedConsumer.java`、`StockSegmentService.java`、`ControlInstructionExecutor.java`、`KafkaTopicConfig.java`、`ReconcileService.java`、`InMemoryBusinessMetricsSource.java`、`OrderCreatedConsumer.java:94`、`SeckillService.java:172`。
- 规划文档与 `tasks.md` 勾选状态、Phase 9–11 教程交叉比对。

> 注：本文只提出问题与风险，不涉及任何代码修正。

---

## 9. 修复状态（2026-08-07，集成缝 M7）

🔴 **高 | 三大支柱未集成（规则引擎未消费真实 `sales.tick`、未写回真实库存、与秒杀数据面完全隔离）** —— **已修复**，见 `tasks.md` Phase 12。

具体落地（代码实证，非口头）：
- `sales.tick` 现在有生产者（`SeckillService` 扣减成功后发，仅非 local）+ 消费者（`SalesTickConsumer` `@KafkaListener("sales.tick")`，集成环境生效）。
- 规则引擎 `RedisStockSegmentService.confirmPending` 用 `INCRBY` 写回 **`stock:a:{aid}:s:{skid}`**——与秒杀 `RedisKeys.stock` 逐字符一致（已在 `RedisStockSegmentServiceTest` 断言该键）。
- `ControlInstructionExecutor` 锁键已从错误的 `a:{aid}:s:{skid}` 修正为 **`lock:a:{aid}:s:{skid}`**（`RuleEngineRedisKeys.lock`），与 `RedisKeys.lock` 逐字符一致；集成环境由 `RedissonLockService`（同名 Redisson 锁，0ms Fail-Fast）替换本地锁桩。
- 新增 `total_stock:a:{aid}:s:{skid}` 键（预热时由 seckill 写入），供消费者算库存水位比例。

**叙事修正（诚实边界，重要）**：原 spec/glossary「用户扣减与规则引擎控盘争用**同一把 Redisson 锁**」表述在字面层不完全准确——用户热路径走 **Redis Lua 原子扣减**（无显式锁、但原子不可打断），并不持有 `lock:a:...`；规则引擎控盘写在 `lock:a:...` 同名 Redisson 锁下串行化。二者真正"同一"的是 **同一份 Redis 库存键**（`stock:a:{aid}:s:{skid}`）：AI 调拨经 `INCRBY` 写回、用户经 Lua 扣减，在同一份库存上互相可见、互不丢失更新。因此「同一把锁」的精确含义是：**同一份库存键（真实相遇）+ 控制面写在同一锁键命名空间下串行化**；用户路径靠 Lua 原子性而非显式 Redisson 锁保证不冲突。热路径零 LLM、零显式锁、延迟红线不动摇。

**Phase 13（真实缺陷修复，2026-08-07）—— 🔴/🟠 已修复（代码实证，全 reactor 构建全绿）**：
- 🔴 **§3.1 异步订单状态不一致**：`OrderCreatedConsumer` 原写死 `"CREATED"` → 经异步快路径创建的订单在 `OrderController` cancel/pay 时 `OrderStatus.from("CREATED")` 抛异常直接 500。已改为 `OrderStatus.CREATE_ORDER.name()`（与同步建单路径一致）；新增 `OrderCreatedConsumerStatusTest` 断言异步单状态可被状态机解析。
- 🔴 **§3.2 结算对账恒等式 / 不读 Redis**：`reconcileFinalStock` 原用「总库存 − 成功单(计数)」比 DB `stock`——而 DB 内 `stock=total−sold` 与 sold 同源更新恒成立（恒等式），且不读 Redis、且计数不支持 qty>1。已改为以「订单台账 `SUM(qty)`（status≠CANCEL）」为独立事实源，交叉核对 ① Redis 权威库存（抓 Redis↔DB 漂移）② DB 库存；新增 `ReconcileRedisCrossCheckTest` 证明 Redis 漂移（丢/多）被抓出、且支持 qty>1。
- 🟠 **§3.3 幂等键限一单未声明**：`buildOrderId = OF-{activityId}-{skuId}-{userId}` 不含 qty，隐含每用户每 SKU 限一单（秒杀限购 1 单）。已在 `SeckillService.buildOrderId` 注释 + spec.md 幂等行 + glossary **显式声明为有意业务规则**（改量重试 / 同 SKU 二次购买判重复单），避免评审误判；不改代码行为。

**🟠 §4 A/B 评估「自证」诚实披露（2026-08-07）**：`InMemoryBusinessMetricsSource` 的对比数字（人工 82%/28% vs AI 95%/36%）是<b>手工设定的演示占位值，并非规则引擎实跑产出</b>。已在源码注释诚实声明「占位演示数据、非实测结论、不可对外宣称实测 AI 胜出」；真正驱动规则引擎在模拟秒杀流上跑出指标的真实端到端评估仍为 **Phase 14 待办**，未虚报已做。

**仍属规划超前实现（未做，非本次范围）**：Redisson 在用户热路径不持有（防超卖由 Redis Lua + DB 条件更新承担，Phase 3 教程的 Redisson 锁代码已被 Phase 4 替换）；Canal/Nginx/Sentinel/Milvus/WS 未落地；§2 零端到端验证（需 docker-compose 起 Redis/Kafka/Redisson 跑 `integration` profile 串完整链路）仍待 Phase 13 收尾。这些降级/后续项已在 `tasks.md` 与 Phase 文档显式标注，未虚报。

**补充（2026-08-07，规划文档对齐）**：§5 列出的五处**文档失真**已就地修正——`plan.md`（技术栈/决策 #5 改 Drools 9、§4.5 改扁平 record、§6 决策表改 record 字段校验）、`spec.md`（§1.1/US-3/§4/§4.4 引述改"同一份 Redis 库存键"、§5.5 改 Drools 9 + 无 JSON Schema 路径、§6 选型改 Drools 9、§12.1/§12.2 架构图改精确机制、§13.2 DSL 样例改扁平结构）、`analysis.md`（头部版本 v0.6→v0.9、§2/§1.2/§4.4 叙事、§2 选型表、§6 开放问题）。规划文档与代码现已对齐，修复项与状态见 `tasks.md` M7+ 修复路线图。
