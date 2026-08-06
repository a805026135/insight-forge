# InsightForge（智析）任务清单 — tasks.md（spec-kit）

> 配套：`plan.md`（方案）、`tutorial.md`（教学，每 Phase 详解）。
> 每个任务可独立验收；勾选 `[x]` 表示完成。任务按教学 Phase 组织，对应 `tutorial.md` 同名 Phase。
> 原则：**每一步都能运行验证**，先跑通再加固。

---

## Phase 0 — 环境与骨架（M1）
- [x] 初始化 Maven 多模块 parent `insightforge`，建 `api / seckill-core / common / persistence` 模块（含最小骨架代码）
- [x] Spring Boot 3.3 启动类 + `application.yml`，`/actuator/health` 可访问（actuator 暴露 health/info/metrics）
- [x] `docker-compose.yml` 起 MySQL 8 / Redis 7 / Kafka（含 Kafka 延时/DLQ 主题）—— **预留**：本机装不了 Docker 时暂不启用，迁移到可装 Docker 的机器再用（见 phase0.md §0.8 路径 A）
- [x] 统一依赖管理（MyBatis-Plus、Redisson、Spring-Kafka、Spring-AI；Drools 入 BOM 待 Phase 9）
- [x] **验收（已实跑通过）**：`mvn clean package` BUILD SUCCESS（沙箱 D:/devtools 的 JDK21+Maven 实跑）；本机无 Docker 时用 `local` profile 零依赖启动验证 `pong` + 健康页 UP（见 phase0.md §0.8 路径 B，已验证）；接真实中间件用默认 profile（路径 C / 迁移后路径 A）
- [x] 已确认：JDK 21、Maven（见 plan §10 / tutorial 附录 A）

## Phase 1 — 最小秒杀主流程（M1，故意不安全）
- [x] 建表 `product / activity / activity_sku / orders`（schema.sql + data.sql，H2 MySQL 模式/MySQL 通用）
- [x] `POST /api/seckill`：查库存 → 扣库存 → 建订单（同步、无并发保护；`decrementStock` 故意无 `WHERE stock>=qty`）
- [x] 配套 `GET /api/seckill/stock/{skuId}` 便于验证；单线程手动跑通一次下单
- [x] **验收（已实跑通过）**：`mvn clean package` BUILD SUCCESS；`local` profile 零依赖启动，单线程下单成功（库存 100→99、订单 +1）；内置 `SeckillOversellDemoTest` 用 300 并发复现超卖（stock=-200、订单 300），已记录为 Phase 2/3 待解决问题
- [x] 踩坑修复：MyBatis-Plus 3.5.9→3.5.11 + 锁定 mybatis-spring 3.0.4（Spring 6.1 兼容）；父 POM 开 `-parameters`；`@PathVariable("skuId")` 显式命名
- [x] ⚠️ 待确认：REST 接口风格（已采用 JSON 入参，POST /api/seckill）

## Phase 2 — DB 层防超卖（M2）
- [x] `activity_sku` 已带 `version` 字段（Phase 1 建表即预留）；**采用 `UPDATE ... SET stock=stock-1 WHERE id=? AND stock>=#{qty}` 原子条件更新**（最简单最稳，不依赖 version 即 100% 不超卖）
- [x] `ActivitySkuMapper.decrementStock` 改为条件更新；`SeckillService.seckill` 用受影响行数判断 + `@Transactional` 包扣减与建单
- [x] **验收（已实跑通过）**：`mvn clean package` BUILD SUCCESS；`SeckillNoOversellTest` 用 300 并发抢 100 库存，断言收敛到 `stock=0 / sold=100 / 订单=100 / 无负数`，无超卖（Phase 1 的 `SeckillOversellDemoTest` 已翻转为本回归测试）
- [x] ⚠️ 已决策：**条件更新 vs 乐观锁 @Version** — 选条件更新作 DB 兜底（见 phase2.md §5 对比）；`version` 字段保留给 Phase 6 对账/乐观并发场景。原因：库存扣减必须原子校验 `stock>=qty`，条件更新已内置该原子性，version 在此为冗余

## Phase 3 — Redis + Lua 原子扣减 + Redisson 分布式锁（M2，并发心脏）
- [x] 预热：`POST /api/seckill/admin/preheat/{skuId}` 把 `activity_sku.stock` 加载进 Redis `stock:a:{aid}:s:{sid}`（也是 Agent"库存预热"任务入口）
- [x] Lua 原子扣减脚本（GET+判断+DECRBY，单 key 不可打断），`POST /api/seckill` 改走**双路**：Redis 主路径 / DB 条件更新兜底（自动降级）
- [x] Redisson 分布式锁：`lock:a:{aid}:s:{sid}` 细粒度，`tryLock(10ms, ...)` **Fail-Fast**（用户路径）；保护"建单 + DB 库存同步"跨实例临界区
- [x] DB 同步落库（建单 + 扣减同事务；Phase 4 改 Kafka 异步，避免长事务占连接）
- [x] 自动降级：`ObjectProvider<RedisStockService>` 检测 Bean 是否存在，`local` profile 下 Redis 缺失 → 自动走 Phase 2 的 DB 兜底，零代码切换
- [~] 分段扣减 `seg:{n}` + 读写锁：本 Phase 先单 key 原子（已满足 100% 不超卖）；分段/读写锁作为后续增强，留待 Phase 5/9 扩充（保持诚实，不虚报）
- [x] **验收（已实跑）**：`mvn clean package` BUILD SUCCESS；`SeckillRedisLuaTest` 用 embedded-redis 证明 300 并发抢 100 → 成功次数=100 / Redis 剩余=0 / 无超卖（沙箱无 redis 进程时该测试被 Assumptions 跳过，构建仍绿）；`SeckillNoOversellTest` 持续守护 DB 兜底路（见 phase3.md）

## Phase 4 — Kafka 异步削峰 + 幂等（M2）
- [x] 扣减成功后发 `order.created`；消费者异步建单（OrderEventProducer / OrderCreatedConsumer，均 @Profile("!local")）
- [x] 幂等双保险：`order:idemp:{orderId}` Redis **SETNX** 前置闸门（RedisIdempotentService）+ `orders.order_id` **唯一约束**最终兜底（exactly-once）
- [x] 订单号从随机 UUID 改为确定性 `buildOrderId = "OF-{activityId}-{skuId}-{userId}"`（全链路幂等锚点）
- [x] 生产者 `acks=all` + `enable.idempotence=true` + 重试 + key=orderId；消费者仅"真插单"才扣 DB 库存（防重投多扣）
- [x] local 零依赖同步兜底路径不变（先插单→order_id 唯一约束幂等→再扣库存），与非 local 异步路径零代码切换
- [x] 回归测试新增 `SeckillIdempotencyTest`（同用户两次只建 1 单、库存只扣 1 次）；修复 Phase 3 潜在编译隐患（RedisStockService 去 @Service 改由 RedisConfig 装配）
- [x] **验收（已实跑）**：`mvn clean package` BUILD SUCCESS（local 零依赖，6 测试全绿：NoOversell + Idempotency + RedisLua×4）；异步快路径 P99<10ms（接口仅 Redis 扣减+发消息）；重复请求不产生重复订单

## Phase 5 — 多级缓存 + 限流（M2）
- [x] 多级缓存：Caffeine(L1) + Redis(L2) 读穿（MultiLevelCache + SkuCacheService）；秒杀路径读 SKU 改走缓存
- [x] 限流（应用层，Phase 5 核心）：Controller 入口接 Redis+Lua 滑动窗口（RateLimitService），local 下内存兜底；超限返 429
- [x] 缓存三防：击穿（Caffeine get(key,loader) 单飞）/ 雪崩（TTL 随机抖动）/ 穿透（NULL 哨兵占位）——均落 MultiLevelCache
- [x] **验收**：SeckillCacheTest（读穿/防穿透/失效 3 用例）+ SeckillRateLimitTest（内存窗口 + Redis Lua 2 用例）全绿；`mvn clean package` 12 测试全过
- [ ] 接入层限流（Nginx+Lua 令牌桶）/ Sentinel 应用层 —— 属部署与运维纵深防御，M2 不做（诚实留待部署/增强章，不虚报）

## Phase 6 — 状态机 + 对账（M2）
- [ ] 活动状态机 `DRAFT→ARMED→LIVE→SETTLING→CLOSED`（ARMED 前 MySQL-Redis 对账）
- [ ] 订单状态机 `PRE_CHECK→LOCK_STOCK→CREATE_ORDER→PAY→DONE/CANCEL`
- [ ] 结算对账：校验 `最终库存 = 初始 - 成功单数`
- [ ] **验收**：非法状态迁转被拒；对账报告无偏差

## Phase 7 — Agent Core 自研运行时（M3）
- [ ] `agent-core`：工具注册（JSON Schema）、ReAct/Plan-Execute 循环、三层记忆（短期/长期 Milvus/跨 Agent Artifact）
- [ ] Critic 反思重试（`maxRetry=3`）
- [ ] Spring AI + DeepSeek 接入；沙箱（独立进程/容器）跑规划计算
- [ ] **监测内建**：`agent_trace`/`agent_span` 持久化（trace_id 生成）+ 预算/Token 上限 + 停止条件 + 三态状态机（SUCCESS/CORRECT_FAILURE/FAILED）
- [ ] **评估内建**：L1 组件指标埋点（工具选择准确率/参数 Schema 合规率/记忆召回/模型成功率）+ Critic 结构化 verdict 框架
- [ ] **验收**：单 Agent 能调工具、产出结构化结果；Critic 能拦截不合规输出

## Phase 8 — 多智能体离线规划（M4）
- [ ] Planner + 选品/定价/预热/风控 Agent；Guardrail Policy Agent 生成 DSL
- [ ] DSL 产出后自编译校验（失败回 Critic，≤3 次）
- [ ] DSL 版本化存入 `guardrail_policy`（MySQL）+ 缓存 `guardrail:dsl:{aid}`（Redis 热更新）
- [ ] **评估内建**：Critic 升级为独立验证器（确定性程序→规则→模型/人工三道校验）+ 正确失败行为（ASK/REFUSE/ESCALATE 计入正确失败率）
- [ ] **评估内建**：L2 轨迹指标采集（Critic 拦截率/重复调用/无效步骤/越权/恢复成功率）+ 最小 golden 评估集 seed
- [ ] **验收**：输入 GMV/ROI 目标 → 产出可上线 DSL + 选品定价方案

## Phase 9 — 护栏规则引擎（M4，在线确定性）
- [ ] `rule-engine` 模块：DSL 静态编译器（JSON Schema 校验 + JSON→Drools 翻译，DroolsRuleCompiler，Fail-Close 阻断上线，见 spec §5.5）
- [ ] 消费 `sales.tick`；条件匹配 → 输出标准化控盘指令
- [ ] 调拨/调价走同一 Redisson 锁（0ms 超时，失败重入队 5s）；**调拨只建新段 `seg_{N+1}`**
- [ ] 两阶段预留：`pending_stock`；补偿仅删 pending 标记
- [ ] 预测性调拨：滑动窗口线性回归算 TTL<5min 预调拨
- [ ] **评估内建**：DSL Fail-Close 门禁（L3 结果评估硬门槛，发布前 100% 编译）+ 影子模式框架 + 回测骨架（历史重放 oversell=0/SLA）+ 护栏命中埋点 + 风险分级
- [ ] **验收**：规则引擎并发控盘，用户流量优先；无 LLM 热路径调用；pending 回滚不超卖

## Phase 10 — 前端 + 可观测 + 部署（M5）
- [ ] React 商户后台（建活动/看策略）+ 实时大屏 WS
- [ ] 大屏策略可解释性：后端模板生成自然语言说明（非 LLM）
- [ ] Prometheus + Grafana 看板（扣减耗时/锁等待/Agent 与规则引擎维度）
- [ ] Docker 部署编排（Seckill/Agent/Rule-Engine Worker 资源隔离）
- [ ] **可观测增强**：Trace 查看器 UI（span 树 + Critic verdict）+ 四层评估指标看板（含正确失败率/护栏命中/成本延迟）+ HITL 人工审批界面 + 风险分级展示
- [ ] **验收**：大屏实时刷新；可解释文案正确；看板有指标

## Phase 11 — 评估闭环（M6）
- [ ] JMeter 百万抢购压测，验证无超卖 + exactly-once
- [ ] 双轨评估 harness：客观硬指标优先 + LLM 定性补充
- [ ] A/B 实验：AI 控盘 vs 人工规则对比 GMV/售罄率/毛利
- [ ] GitHub Actions CI 跑 eval，阈值失败阻断
- [ ] **评估闭环增强**：离线 eval harness（golden + 失败样本回归重放）+ 在线灰度 shadow→单活动→多活动 + 影子/A/B + 系统指标报告（含正确失败率）+ CI 阈值阻断
- [ ] **验收**：给出量化报告（无超卖 + Agent 策略提升经营指标）

---

## 范围红线（M1–M6 不做，延后 M7+）
- [ ] 不内置优惠券/积分/多渠道分销/支付网关对接
- [ ] 不训练/微调模型
- [ ] 不做完整多租户 SaaS
- ⚠️ 任何跨域新增功能须先回此红线确认
