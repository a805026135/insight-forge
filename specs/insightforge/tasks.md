# InsightForge（智析）任务清单 — tasks.md（spec-kit）

> 配套：`plan.md`（方案）、`tutorial.md`（教学，每 Phase 详解）。
> 每个任务可独立验收；勾选 `[x]` 表示完成。任务按教学 Phase 组织，对应 `tutorial.md` 同名 Phase。
> 原则：**每一步都能运行验证**，先跑通再加固。

---

## Phase 0 — 环境与骨架（M1）
- [ ] 初始化 Maven 多模块 parent `insightforge`，建 `api / seckill-core / common / persistence` 等空模块
- [ ] Spring Boot 3.3 启动类 + `application.yml`，`/actuator/health` 可访问
- [ ] `docker-compose.yml` 起 MySQL 8 / Redis 7 / Kafka（含 Kafka 延时/DLQ 主题）
- [ ] 统一依赖管理（MyBatis-Plus、Redisson、Spring-Kafka、Spring-AI）
- [ ] **验收**：`mvn clean package` 通过；`docker compose up` 后应用连上 MySQL/Redis/Kafka
- [ ] ⚠️ 待确认：JDK 21 vs 17、Maven vs Gradle

## Phase 1 — 最小秒杀主流程（M1，故意不安全）
- [ ] 建表 `product / activity / activity_sku / orders`
- [ ] `POST /api/seckill`：查库存 → 扣库存 → 建订单（同步、无并发保护）
- [ ] 前端/Postman 手动跑通一次下单
- [ ] **验收**：单线程下单成功；记录"当前会超卖"作为 Phase 2/3 的待解决问题
- [ ] ⚠️ 待确认：REST 接口风格

## Phase 2 — DB 层防超卖（M2）
- [ ] `activity_sku` 加 `version` 字段；MyBatis-Plus 乐观锁更新（`UPDATE ... SET stock=stock-1, version=version+1 WHERE id=? AND stock>0 AND version=?`）
- [ ] 或小改造为 `UPDATE ... SET stock=stock-1 WHERE id=? AND stock>=1`（条件更新兜底）
- [ ] **验收**：JMeter 中小并发压测后 `stock ≥ 0` 且 `sold = total - stock`，无超卖
- [ ] ⚠️ 待确认：乐观锁 vs 行锁条件更新

## Phase 3 — Redis 预扣 + Lua 原子（M2，并发心脏）
- [ ] 预热脚本：活动 `ARM` 前把 `activity_sku.stock` 加载到 `stock:a:{aid}:s:{sid}`
- [ ] Lua 原子扣减脚本（DECRBY + 库存≥0 校验），`POST /api/seckill` 改调 Redis 扣减
- [ ] Redisson 分布式锁：`lock:a:{aid}:s:{sid}` 细粒度；读写锁；分段锁 `seg:{n}`；**锁超时分级（用户 10ms / 规则引擎 0ms）**
- [ ] 分段扣减：Lua 遍历所有 `seg` 汇总
- [ ] DB 异步落库（先简单同步，Phase 4 改 Kafka）
- [ ] **验收**：高并发压测无超卖；用户抢购 P99 < 阈值；规则引擎持锁不阻塞用户（0ms 放弃）
- [ ] ⚠️ 待确认：分段数 N、锁超时参数

## Phase 4 — Kafka 异步削峰 + 幂等（M2）
- [ ] 扣减成功后发 `order.created`；消费者异步建单
- [ ] 幂等：`order:idemp:{hash(userId,skuId,activityId)}` SETNX 去重（exactly-once）
- [ ] 生产者 `acks=all` + 重试；失败订单进 DLQ
- [ ] **验收**：接口快速返回（<10ms）；异步建单最终一致；重复请求不产生重复订单

## Phase 5 — 多级缓存 + 限流（M2）
- [ ] Caffeine + Redis 多级缓存商品/活动配置；本地缓存预热
- [ ] 限流：Nginx+Lua 令牌桶（接入层）+ Redis+Lua 滑动窗口 + Sentinel（应用层）
- [ ] 缓存防护：击穿（互斥锁重建+逻辑过期）、雪崩（分片+TTL 打散+Caffeine 兜底）、穿透（布隆过滤器）
- [ ] **验收**：热点 key 失效不击穿 DB；限流触发时超额请求被拒

## Phase 6 — 状态机 + 对账（M2）
- [ ] 活动状态机 `DRAFT→ARMED→LIVE→SETTLING→CLOSED`（ARMED 前 MySQL-Redis 对账）
- [ ] 订单状态机 `PRE_CHECK→LOCK_STOCK→CREATE_ORDER→PAY→DONE/CANCEL`
- [ ] 结算对账：校验 `最终库存 = 初始 - 成功单数`
- [ ] **验收**：非法状态迁转被拒；对账报告无偏差

## Phase 7 — Agent Core 自研运行时（M3）
- [ ] `agent-core`：工具注册（JSON Schema）、ReAct/Plan-Execute 循环、三层记忆（短期/长期 Milvus/跨 Agent Artifact）
- [ ] Critic 反思重试（`maxRetry=3`）
- [ ] Spring AI + DeepSeek 接入；沙箱（独立进程/容器）跑规划计算
- [ ] **验收**：单 Agent 能调工具、产出结构化结果；Critic 能拦截不合规输出

## Phase 8 — 多智能体离线规划（M4）
- [ ] Planner + 选品/定价/预热/风控 Agent；Guardrail Policy Agent 生成 DSL
- [ ] DSL 产出后自编译校验（失败回 Critic，≤3 次）
- [ ] DSL 版本化存入 `guardrail_policy`（MySQL）+ 缓存 `guardrail:dsl:{aid}`（Redis 热更新）
- [ ] **验收**：输入 GMV/ROI 目标 → 产出可上线 DSL + 选品定价方案

## Phase 9 — 护栏规则引擎（M4，在线确定性）
- [ ] `rule-engine` 模块：DSL 静态编译器（Antlr/纯 Java，Fail-Close 阻断上线）
- [ ] 消费 `sales.tick`；条件匹配 → 输出标准化控盘指令
- [ ] 调拨/调价走同一 Redisson 锁（0ms 超时，失败重入队 5s）；**调拨只建新段 `seg_{N+1}`**
- [ ] 两阶段预留：`pending_stock`；补偿仅删 pending 标记
- [ ] 预测性调拨：滑动窗口线性回归算 TTL<5min 预调拨
- [ ] **验收**：规则引擎并发控盘，用户流量优先；无 LLM 热路径调用；pending 回滚不超卖

## Phase 10 — 前端 + 可观测 + 部署（M5）
- [ ] React 商户后台（建活动/看策略）+ 实时大屏 WS
- [ ] 大屏策略可解释性：后端模板生成自然语言说明（非 LLM）
- [ ] Prometheus + Grafana 看板（扣减耗时/锁等待/Agent 与规则引擎维度）
- [ ] Docker 部署编排（Seckill/Agent/Rule-Engine Worker 资源隔离）
- [ ] **验收**：大屏实时刷新；可解释文案正确；看板有指标

## Phase 11 — 评估闭环（M6）
- [ ] JMeter 百万抢购压测，验证无超卖 + exactly-once
- [ ] 双轨评估 harness：客观硬指标优先 + LLM 定性补充
- [ ] A/B 实验：AI 控盘 vs 人工规则对比 GMV/售罄率/毛利
- [ ] GitHub Actions CI 跑 eval，阈值失败阻断
- [ ] **验收**：给出量化报告（无超卖 + Agent 策略提升经营指标）

---

## 范围红线（M1–M6 不做，延后 M7+）
- [ ] 不内置优惠券/积分/多渠道分销/支付网关对接
- [ ] 不训练/微调模型
- [ ] 不做完整多租户 SaaS
- ⚠️ 任何跨域新增功能须先回此红线确认
