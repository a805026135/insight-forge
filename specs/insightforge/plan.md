# InsightForge（智析）实现方案 — plan.md（spec-kit）

> 配套文档：`spec.md`（v0.8 设计文档）、`tasks.md`（任务清单）、`tutorial.md`（从零搭建教学文档）。
> 本文是**技术实现方案**：定义架构、数据模型、API 契约、关键技术决策与构建顺序。每一项的"为什么"详见 `tutorial.md` 对应 Phase。
> 标注 ⚠️ 待确认 的点可在审阅时调整，调整后回写本文与 spec。

---

## 1. 方案摘要

按 **spec-kit** 流程，从 v0.8 spec 落到可真实运行的代码。核心思想贯穿全程：

- **控制面 / 数据面分离**：LLM Agent 只在离线规划期生成护栏策略 DSL、异步复盘；在线由**确定性护栏规则引擎**执行，走与用户抢购**同一套 Redisson 锁 + Lua 原子**。**LLM 永不进入秒杀热路径。**
- **最小功能叠加（教学构建）**：先跑通主流程（故意暴露超卖）→ DB 兜底 → Redis+Lua 真正解决 → Kafka 削峰 → 缓存/限流 → 状态机/对账 → Agent Core → 多智能体规划 → 规则引擎 → 前端/可观测 → 评估。每步可运行、可验证。

---

## 2. 技术栈（企业级，痛点驱动）

| 类别 | 选型 | 用途 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.3 + Maven | 后端主体（已确认：JDK 21、Maven） |
| 持久层 | MyBatis-Plus | ORM |
| 关系库 | MySQL 8 | 商品/活动/订单/用户/策略 |
| 缓存/锁/限流 | Redis 7 + Redisson | 库存缓存、分布式锁（防超卖）、限流、订单去重、价格缓存 |
| 消息队列 | Kafka | 异步下单削峰 + 实时销量事件流（供规则引擎） |
| 多级缓存 | Caffeine + Redis | 商品/活动配置热点 |
| 向量库 | Milvus | Agent 长期记忆（历史策略向量化，相似复用） |
| 规则引擎 | Drools 7.x | 护栏 DSL 在线执行（JSON→Drools 翻译 + Fail-Close，见 spec §5.5） |
| Agent 接入 | Spring AI + DeepSeek | 模型调用 / Embedding |
| 限流 | Nginx+Lua / Redis+Lua / Sentinel | 接入层 + 模型 + 应用层限流 |
| 压测 | JMeter | 百万抢购验证无超卖 |
| 前端 | React 18 + TS + Vite + Ant Design + ECharts | 商户后台 + 实时大屏（已确认：React） |
| 部署/CI | Docker Compose + GitHub Actions | 一键起 + 评估回归 |

---

## 3. 项目结构（Maven 多模块）

```
insightforge
├── insightforge-api            # REST + WebSocket 接入层
├── insightforge-seckill-core   # ★高并发引擎：Lua、Redisson 锁、Kafka 消费、状态机、防超卖
├── insightforge-rule-engine    # ★护栏规则引擎：DSL 编译器 + 事件消费 + 条件匹配 + 调拨/调价（走锁）
├── insightforge-agent-core     # 自研 Agent 运行时（工具/记忆/反思）
├── insightforge-agents        # Planner/选品/定价/预热/风控/GuardrailPolicy/Monitor-Alert/Critic
├── insightforge-tools         # 秒杀域工具（repricer/reallocator/preloader…）+ web_search
├── insightforge-datasource     # 历史销量 + 实时流接入
├── insightforge-sandbox       # Agent 规划计算沙箱（独立进程/容器）
├── insightforge-persistence   # 实体与仓储
├── insightforge-eval          # 评估 harness（双轨）
├── insightforge-common        # 安全/日志/配置/限流/异常
└── insightforge-gateway       # （可选）网关
web/                            # React 前端
docker/                         # docker-compose / Dockerfile
```

---

## 4. 数据模型

### 4.1 MySQL 表（源真相）
- `product`(id, name, sku, cost_price, …)：商品主数据。
- `activity`(id, name, status, target_gmv, target_roi, start_at, end_at)：大促活动，状态机见 §4.4。
- `activity_sku`(activity_id, sku_id, seckill_price, total_stock, sold, version)：某活动下某 SKU 的 DB 库存真相（与 Redis 经 Canal 对齐）。
- `orders`(order_id, user_id, activity_id, sku_id, qty, status, created_at)：订单（order_id = hash(userId,skuId,activityId) 保证幂等）。
- `guardrail_policy`(id, activity_id, version, dsl_json, status, created_at)：护栏 DSL **版本化存储**（支持灰度/回滚）。
- `audit_log`(id, activity_id, action_type, params, result, by_rule, operator, ts)：控盘动作审计。
- `user`(id, name)：最小用户表（已确认：先做单用户 JWT，鉴权骨架见 tutorial 对应 Phase）。

### 4.2 Redis Key（见 spec §13.1）
`stock:a:{aid}:s:{sid}` / `stock:a:{aid}:s:{sid}:seg:{n}`（分段，调拨只建新段）/ `pending_stock:a:{aid}:s:{sid}`（两阶段预留）/ `price:a:{aid}:s:{sid}` / `lock:a:{aid}:s:{sid}`（细粒度）/ `order:idemp:{hash}`（SETNX）/ `guardrail:dsl:{aid}`（当前生效 DSL）/ `seckill:quota:{aid}:{uid}`（限购）。

### 4.3 Kafka Topic
- `order.created`（下单，acks=all + DLQ）
- `sales.tick`（销量事件，供规则引擎）
- `guardrail.action`（控盘动作）
- `guardrail.action.failed`（控盘失败，DLQ + 人工告警）

### 4.4 状态机
- 活动：`DRAFT → ARMED → LIVE → SETTLING → CLOSED`（ARMED 前做 MySQL-Redis 对账）。
- 订单：`PRE_CHECK → LOCK_STOCK → CREATE_ORDER → PAY → DONE / CANCEL`。

### 4.5 护栏 DSL Schema（见 spec §13.2）
JSON：`{activity_id, version, rules:[{id, trigger{metric,op,threshold}, condition, action{type,params}, bounds{max,circuit_breaker}, human_gate{when, approve_flow}}]}`。

---

## 5. API 契约（REST + WS）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/activities` | 创建活动（运营输入 GMV/ROI 目标） |
| POST | `/api/activities/{id}/plan` | 触发多智能体规划，产出选品/定价/预热/风控 + DSL |
| POST | `/api/activities/{id}/arm` | 预热库存到 Redis + 加载 DSL（**ARMED 前对账**） |
| POST | `/api/activities/{id}/live` | 活动上线 `LIVE` |
| POST | `/api/seckill` | 用户抢购 `{activityId, skuId, userId}`（10ms tryLock，失败即"拥挤请重试"） |
| POST | `/api/activities/{id}/settle` | 结算 `SETTLING` → 对账 → `CLOSED` |
| WS | `/ws/dashboard/{activityId}` | 实时大屏（含策略可解释性文案） |

---

## 6. 关键技术决策（摘要，详见 tutorial）

| 决策 | 结论 | 解决的问题 |
|---|---|---|
| 控制面/数据面分离 | LLM 离线生成 DSL，规则引擎在线执行 | LLM 延迟/非确定/幻觉不进热路径，AI 智能仍以确定性落地 |
| 锁超时分级 Fail-Fast | 用户 10ms / 规则引擎 0ms+延迟重试 | Agent 策略流量绝不阻塞用户抢购 |
| 分段只建新段 | 调拨 `SET segment_{N+1}` O(1) | 调拨不阻塞正在热卖的旧段 |
| DSL 静态编译器 | JSON Schema 校验 + JSON→Drools 翻译（DroolsRuleCompiler），Fail-Close | 防 LLM 幻觉 DSL 导致运行时裸奔（Drools 已选，翻译层隔离可回退） |
| 两阶段预留 | 先加 `pending_stock`，补偿仅删标记 | 杜绝补偿时恢复成负数超卖 |
| 预测性调拨 | 滑动窗口线性回归算 TTL<5min 预调拨 | 被动救火→主动调度（仍无 LLM） |

---

## 7. 构建顺序（Phase ↔ 里程碑）

| 里程碑 | 对应教学 Phase | 交付 |
|---|---|---|
| M1 脚手架 | Phase 0–1 | Maven 多模块 + Spring Boot + Docker 起依赖 + 最小秒杀主流程（可运行） |
| M2 秒杀核心 | Phase 2–6 | DB 乐观锁兜底 → Redis+Lua 原子 → Kafka 削峰+幂等 → 多级缓存/限流 → 状态机/对账 |
| M3 Agent Core | Phase 7 | 自研 Agent 运行时（工具/记忆/反思/Critic） |
| M4 多智能体+规则引擎 | Phase 8–9 | 规划 Agent 生成 DSL → 独立规则引擎（编译器/事件消费/走锁/两阶段/预测性） |
| M5 前端/部署 | Phase 10 | 商户后台 + 大屏（可解释性）+ Docker + 监控 |
| M6 评估闭环 | Phase 11 | JMeter 百万压测 + 双轨评估 + A/B + CI 回归 |

---

## 8. 测试与评估

- **单元/集成**：每模块 JUnit；秒杀核心用嵌入式 Redis + 测试容器 MySQL/Kafka。
- **压测**：JMeter 百万抢购，验证 `最终库存 = 初始 - 成功单数`、无重复订单（无超卖硬指标）。
- **双轨评估**（spec §7）：客观硬指标（毛利率/售罄率/无超卖…）优先 + LLM 定性补充；A/B 对比 AI 控盘 vs 人工规则。
- **CI**：GitHub Actions 跑 `./mvnw eval:run`，客观指标低于阈值则失败。

---

## 9. 风险与缓解（详见 spec §10 / §14）

Redis 宕机（Caffeine 兜底+DB 行锁兜底）、Milvus 不可用（降级历史表）、LLM 熔断（复用上次 DSL）、规则引擎异常（pending 回滚+fail-open）、Kafka 积压（分区扩容+优先级）。

---

## 10. 已确认决策汇总（来自 tutorial 附录 A，2026-08-05 确认）

| # | 决策 | 结论 | 备注 |
|---|---|---|---|
| 1 | JDK 版本 | **Java 21** | 虚拟线程支撑高并发 I/O；17 为保守备选 |
| 2 | 构建工具 | **Maven** | 父 POM 多模块标准范式，资料最多 |
| 3 | 前端框架 | **React 18 + TS** | Ant Design 原生、ECharts 大屏最顺 |
| 4 | 用户模型 | **单用户 JWT** | 鉴权骨架一次到位，多用户 M7+ 扩 |
| 5 | 护栏规则引擎 | **Drools 7.x** | 替代初稿自研；JSON DSL + Fail-Close 不变，翻译层 `DroolsRuleCompiler` 隔离可回退（见 spec §5.5） |
| 6 | 预测性调拨 / A/B | **纳入（M4 / M6）** | 确定性算法不进 LLM 热路径；A/B 给护栏有效性客观背书 |
| 7 | 报告导出 PDF/Word | **延后 M7+** | 先在网页大屏看评估结果 |
| 8 | 范围红线 | **优惠券/积分/分销延后 M7+** | 守住库存互斥核心，规避资金强一致风险 |

> 下一步：按 `tasks.md` 逐 Phase 推进，配合 `tutorial.md` 一步步实现。Phase 0 起。
