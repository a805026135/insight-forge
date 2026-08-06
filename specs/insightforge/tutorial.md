# InsightForge（智析）从零搭建教学文档 — tutorial.md

> 配套：`plan.md`（方案）、`tasks.md`（任务清单）、`spec.md`（v0.8 设计）、`glossary.md`（大白话术语速查，看不懂词先查这里）、`tutorials/`（**每个 Phase 的逐行详解文档**，如 `phase0.md`）。
> 本文是你的**动手指南**：从空目录开始，按 **最小功能叠加** 一步步真实搭出这个项目。每个 Phase 只加一点功能，并讲清：**用了什么技术、为什么选它（对比其他选型）、解决了什么需求/问题、怎么验证**。

---

## 0. 怎么用这篇文档

- 每个 **Phase = 一个可运行、可验证的小里程碑**。先跑通，再加固，不要跳步。
- 文中 **⚠️ 待确认** 是开放决策点——你可以保留我的默认推荐，也可以改；改完告诉我，我回写 `plan.md` / `spec.md`。
- 标注「故意留坑」的地方，是**教学刻意制造的问题**，下一个 Phase 再解决——这能让你真正理解"为什么要这个技术"。
- Phase 与 `tasks.md` 一一对应，完成时勾选 tasks。

---

## 1. 我们要建什么（一句话 + 核心思想）

一个**智能秒杀/大促平台（Agent 辅助）**：商家填大促目标 → 多智能体离线产出选品/定价/库存方案并生成**护栏策略 DSL** → 活动期间百万用户抢购由 Redis+Lua+分布式锁保证**不超卖** → 一个**确定性规则引擎**按 DSL 实时调价/调拨（走与用户**同一套锁**）→ 活动结束异步复盘。

**最核心的一条设计线（务必记住）**：
> **LLM 永不进秒杀热路径。** Agent 只在离线生成策略、异步复盘；在线全靠确定性规则引擎。这样 AI 的智能以"可控、可验证"的方式落地到高并发核心，而不是把不可靠的大模型塞进扣减链路。

---

## Phase 0 — 环境与骨架（M1）

> 📘 **逐行详解**：`tutorials/phase0.md`（环境安装校验 + 目录结构 + 所有关键文件逐行讲 + mermaid 流程图 + 验证/排错）。本阶段跟着它做即可。

**目标**：有一个能跑的 Maven 多模块工程 + Docker 起的依赖（MySQL/Redis/Kafka）。
**你会做**：手写 parent `pom.xml`；建模块 `api / seckill-core / common / persistence`；写 `docker-compose.yml` 起三个中间件；Spring Boot 启动类 + `/actuator/health` + `/api/ping`。

**技术选型与目的（均已确认，见附录 A）**
- **Maven 多模块**：职责分离（高并发引擎、规则引擎、Agent 各自独立模块），父 POM `dependencyManagement` 统一版本，子模块不写版本号即不冲突。对比 **Gradle**——更快但多一门 DSL，教学优先 Maven。
- **Java 21 + Spring Boot 3.3**：虚拟线程、Record；对比 **Java 17**——21 虚拟线程对高并发 I/O 有帮助，17 也够用更稳，已选 21。
- **Docker Compose**：一条命令拉起全部依赖，环境可复现（你换电脑也能一键起）。

**解决什么**：告别"在我机器能跑"，所有人/面试环境一致。
**验证**：`mvn clean package` 通过；`docker compose up` 后 `/actuator/health` 三组件 UP，`/api/ping` 返回 pong。

---

## Phase 1 — 最小秒杀主流程（故意不安全，M1）

> 📘 **逐行详解**：`tutorials/phase1.md`（4 张表设计 + 主流程/超卖竞态 mermaid 图 + 伪代码 + 核心代码逐文件讲 + 实跑验证与排错）。本阶段跟着它做即可。

**目标**：先跑通"用户点抢购 → 下单"的最简链路，不管并发。
**你会做**：建表 `product / activity / activity_sku / orders`；写 `POST /api/seckill`：`SELECT stock → if stock>0 UPDATE stock-1 → INSERT order`。同步、无锁。

**技术选型与目的**
- **Spring MVC + MyBatis-Plus**：最朴素的 REST + ORM，先把业务跑起来。
- **为什么先不做并发保护**：教学上"先懂业务，再懂并发"。这里**故意留坑**——单线程能下单，但并发会超卖（下一 Phase 解决）。

**解决什么**：建立对"秒杀 = 用户抢有限库存"的直观理解。
**验证**：Postman 手动下一次单，订单表多出一行，库存 -1。
**⚠️ 待确认**：REST 接口风格（JSON 入参即可）。

---

## Phase 2 — DB 层防超卖（M2）

> 📘 **逐行详解**：`tutorials/phase2.md`（竞态对比流程图 + 条件更新伪代码 + 逐文件核心代码 + 乐观锁 `@Version` 对比 + 回归测试 + 验收/排错）。本阶段跟着它做即可。

**目标**：第一道超卖防线——即使并发，数据库也不让库存变负。
**你会做**：把「库存够不够」的判断塞进 `UPDATE` 的 `WHERE`（原子条件更新 `UPDATE ... SET stock=stock-1 WHERE id=? AND stock>=#{qty}`）；业务层用受影响行数判断、扣减与建单包 `@Transactional`。

**技术选型与目的**
- **原子条件更新（采用）**：把「查库存 → 判断 → 扣减」三步合成一条 SQL，DB 行锁内重判 `stock>=qty`，并发绝不可能同时穿过，最简单也最稳，**不依赖 version 即 100% 不超卖**。
- **乐观锁 `@Version`（对比，未采用）**：靠版本号检测冲突、冲突重试。但对「库存扣减」而言，它仍需额外 `stock>=qty` 条件才能真正防超卖，单靠 version 拦不住；既然条件更新已内置该原子性，version 在此为冗余。详见 phase2.md §5。
- `version` 字段仍保留在表里，留给 Phase 6 对账 / 其他乐观并发场景。

**为什么 DB 只是"兜底"**：DB 是最终真相，但高并发下所有请求打 DB 会压垮它。所以 DB 兜底 + 下一步 Redis 主战场（Phase 3）。
**解决什么**：即使后面 Redis 挂了，DB 也不超卖。
**验证**：300 并发抢 100 库存 → 最终 `stock=0`、`sold=100`、订单 100、无负数（`SeckillNoOversellTest` 回归看护）。

---

## Phase 3 — Redis + Lua 原子扣减 + Redisson 分布式锁（并发心脏，M2）

> 📘 **逐行详解**：`tutorials/phase3.md`（从加依赖/新建 redis profile → 写 Lua 脚本 → RedisStockService → RedisConfig → 改 SeckillService 双路 → 加预热接口 → 写 Redis+Lua 回归测试 → 本机端到端验证，每一步含命令+完整代码+逐行注释+📖概念/🧠理解+排错表）。本阶段跟着它做即可。

**目标**：把高并发扣减从 DB 移到 Redis，真正解决性能 + 超卖；同时保留 DB 兜底，一份代码两种跑法。
**你会做**：
1. 预热：`POST /api/seckill/admin/preheat/{skuId}` 把库存 `SET stock:a:{aid}:s:{sid} 100`（秒杀前必调，也是 Agent"库存预热"入口）；
2. 写 Lua 脚本（GET+判断+DECRBY 单 key 原子，不存在返回 -1）；
3. `SeckillService` 改走**双路**：有 Redis → Lua 原子扣减（成功才建单 + Redisson 锁保护 DB 同步）；无 Redis（`local`）→ 自动回退 Phase 2 的 DB 条件更新；
4. 加 **Redisson 分布式锁**：`lock:a:{aid}:s:{sid}` 细粒度，**用户 `tryLock(10ms)` 抢不到即 Fail-Fast**（规则引擎 0ms 让路是 Phase 9 增强）。

**技术选型与目的**
- **Redis + Lua 原子扣减**：对比"应用层先 `GET` 再 `DECR`"——后者在并发下多个线程同时读到同一库存，都判断"有货"再扣，必超卖；Lua 把"判断+扣减"变成**单线程原子操作**，彻底杜绝。这是秒杀的命门。
- **Redisson 分布式锁**：对比裸 `SETNX`——裸锁有"无自动过期→死锁""过期了业务没跑完→误释放""主从切换空窗→重复获锁"三缺陷；Redisson 看门狗自动续期、可重入、防死锁，工业级。
- **细粒度锁 `activity:sku`**：对比"全局一把锁"——全局锁会让所有 SKU 串行，吞吐暴跌。
- **锁超时分级（你的致命隐患 #1）**：规则引擎调拨若持锁 200ms，上千用户会排队、RT 飙升。所以**用户 10ms 优先、规则引擎 0ms 立刻让路**——Agent 流量永远不阻塞用户（本 Phase 落地用户 10ms；规则引擎 0ms 在 Phase 9 控盘时启用）。
- **双路自动降级**：用 `ObjectProvider` 探测 Redis 相关 Bean 是否存在，`local` 下 Redis 缺失就走 DB 兜底，零代码切换——这也是"控制面/数据面分离、DB 是最终真相"的工程落地。

**解决什么**：百万级并发扣减不超卖、低延迟；Redis 挂了 DB 仍兜得住。
**验证**：`SeckillRedisLuaTest` 用 embedded-redis 证明 300 并发抢 100 → 成功=100、Redis 剩余=0、无超卖（沙箱无 redis 时该测试被 Assumptions 跳过，构建仍绿）；`SeckillNoOversellTest` 持续守护 DB 兜底路。
> 注：分段锁 `seg:{n}` + 读写锁属更精细的增强，本 Phase 先单 key 原子（已 100% 不超卖），留待 Phase 5/9 扩充，不虚报完成度。

---

## Phase 4 — Kafka 异步削峰 + 幂等（M2）

> 📘 **逐行详解**：`tutorials/phase4.md`（从加依赖/yml 生产者可靠性 → 事件 DTO → Redis SETNX 幂等闸门 → Kafka 生产者 → 消费者异步建单 → schema 唯一约束 → 改 SeckillService 双路（异步快路径+同步兜底）→ 幂等回归测试 → 本机端到端验证，每一步含命令+完整代码+逐行注释+📖概念/🧠理解+排错表）。本阶段跟着它做即可。

**目标**：抢购接口秒回，下单异步落库，扛峰值；同一用户重复点抢只生成 1 张单。
**你会做**：
1. 扣减成功后往 Kafka `order.created` 发事件 → **立刻返回用户**（接口 ~毫秒级，P99<10ms）；
2. 消费者异步建单 + 把 DB 库存补齐（Redis 先扣，DB 后追，最终一致）；
3. 幂等双保险：`order:idemp:{orderId}` Redis **SETNX** 前置闸门（拦重复扣减） + `orders.order_id` **唯一约束**最终兜底（Kafka 重投也不重复建单，exactly-once）；
4. 生产者 `acks=all` + `enable.idempotence=true` + 重试，消息 key=orderId（保序+分区幂等）。

**技术选型与目的**
- **Kafka 异步解耦**：对比"请求里同步写 DB"——同步写在高并发下把 DB 变成瓶颈、RT 飙升；Kafka 把"建单落库"挪到后台匀速消费，用户路径只剩"Redis 扣减+发消息"，极速返回、峰值被队列削平。对比 RabbitMQ——Kafka 吞吐更高、分区并行、支持事件重放，契合"销量流驱动控盘"。
- **幂等双保险（SETNX + DB 唯一约束）**：SETNX 在热路径前置拦"同一用户重复抢"造成的重复扣减（不进 DB）；DB 唯一约束作为最终兜底，即便 Kafka at-least-once 重投、生产者重试，也只落一张单。对比"只靠 DB 唯一索引"——SETNX 更快且不占 DB 热路径；对比"只靠 SETNX"——DB 约束防止 Redis 宕/超时导致漏拦。
- **控制面/数据面分离**：Kafka 是"数据面"的异步管道，**不是**把 LLM 塞进链路——Agent 仍只在离线生成策略（Phase 8）。本 Phase 落地"热路径无阻塞写"。

**解决什么**：接口从"等 DB 写完"变成"<10ms 返回"；重复点击/网络重试/消息重投都只生成 1 张单；Redis 与 DB 库存最终一致（Phase 6 对账兜底）。
**验证**：`SeckillIdempotencyTest` 证明同用户两次请求只建 1 单、库存只扣 1 次；`SeckillNoOversellTest` 继续守护 DB 兜底路；`mvn clean package` 全绿（local 零依赖也能跑，Kafka/Redis Bean 用 `@Profile("!local")` 与非 local 互斥）。
> 注：order 消息的异常（如反序列化失败）当前以日志兜底；专用 DLQ 主题 `guardrail.action.failed` 在 Phase 9 用于护栏动作，order 消息的 exactly-once 由"acks=all + 幂等生产者 + DB 唯一约束"三道防线保证，不虚报。

---

## Phase 5 — 多级缓存 + 限流（M2）

**目标**：保护热点读、挡住恶意/超额流量。
**你会做**：Caffeine（本地）+ Redis（分布式）两级缓存商品/活动配置（详见 tutorials/phase5.md）；应用层接 Redis+Lua 滑动窗口限流（local 下内存兜底），超限返 429；缓存击穿（Caffeine 单飞）/ 雪崩（TTL 抖动）/ 穿透（NULL 哨兵）三防。

**技术选型与目的**
- **Caffeine + Redis 多级**：对比单级 Redis——本地缓存 μs 级、挡掉绝大多数读，Redis 扛共享；对比单级本地——本地多实例不一致、重启丢。
- **滑动窗口限流（应用层）**：按 (用户,活动) 维度在 Controller 入口拦截羊毛党；Redis Lua 集群共享计数，local 退化内存版。
- **三防**：击穿用 Caffeine `get(key,loader)` 单飞（无需额外互斥锁）；雪崩用 TTL 随机抖动；穿透用 NULL 哨兵占位（不引入布隆过滤器这座更重的设施，诚实说明取舍）。

**解决什么**：热点 key 失效不击穿、Redis 挂了不雪崩（local 无 Redis 时 L1 单独顶）、恶意遍历被穿透拦截、超额流量被限流。
**验证**：SeckillCacheTest（读穿/防穿透/失效）+ SeckillRateLimitTest（内存窗口 + Redis Lua）全绿；`mvn clean package` 12 测试通过。
> 注：Nginx+Lua 令牌桶（接入层）与 Sentinel 属部署/运维纵深防御，M2 不做，留待部署增强章（不虚报）。

---

## Phase 6 — 状态机 + 对账（M2）

**目标**：活动/订单生命周期可控、可证明正确。
**你会做**：活动状态机 `DRAFT→ARMED→LIVE→SETTLING→CLOSED`（ARMED 前做 MySQL-Redis 库存对账）；订单状态机 `PRE_CHECK→LOCK_STOCK→CREATE_ORDER→PAY→DONE/CANCEL`；结算时校验 `最终库存 = 初始 - 成功单数`。

**技术选型与目的**
- **状态机（枚举 + 流转校验）**：对比"到处写 if 改状态"——状态机集中约束合法迁转，非法流转（如 `CLOSED→LIVE`）直接拒绝，避免脏状态。
- **对账**：用确定性等式证明"无超卖"，是给面试/上线的最硬证据。

**解决什么**：生命周期混乱、状态错乱、超卖无法自证。
**验证**：非法状态迁转被拒；结算报告 `stock = init - sold` 成立。

✅ **本 Phase 已落地**：活动/订单状态机枚举 + 迁转矩阵、`ReconcileService`（上线前 MySQL↔Redis 对账 + 结算公式对账）、`ActivityController`/`OrderController` 均已实现；`ActivityStateMachineTest`(9) + `ReconcileTest`(4) 全绿，`mvn clean package` 共 25 测试全过。诚实边界：Redisson 锁未单独引入（Lua 原子 + 条件更新承载防超卖）；支付链路延后 M7+；local 对账降级 DB 内部校验。详见 `tutorials/phase6.md`。

---

## Phase 7 — Agent Core 自研运行时（M3）

**目标**：给"AI 辅助"打底座——一个能调工具、会记忆、能反思的自研 Agent。
**你会做**：`agent-core` 实现工具注册（JSON Schema 声明）、ReAct/Plan-Execute 循环、三层记忆（短期窗口 / 长期 Milvus / 跨 Agent Artifact）、Critic 反思重试（`maxRetry=3`）；Spring AI + DeepSeek 接模型；沙箱（独立进程/容器）跑规划计算；**监测内建**：从第一行就落 Trace（trace_id + span 持久化 MySQL）+ 预算/Token 上限 + 三态状态机（SUCCESS/CORRECT_FAILURE/FAILED）+ L1 组件指标埋点，详见 `agent-observability-evaluation.md`。

**技术选型与目的**
- **自研 Agent 运行时**：对比直接用 **LangChain / AutoGPT**——成熟但黑盒、与本项目高并发主题脱节、面试难讲清；自研能把"工具注册/记忆/反思"逐个讲透，且**不绑死模型**（Spring AI 抽象层）。教学与面试价值更高。
- **Spring AI + DeepSeek**：DeepSeek 中文/成本/推理均衡；Spring AI 标准化接入，换模型成本低。
- **沙箱隔离**：Agent 的 SQL/Python 计算在独立进程跑，崩了不拖核心服务（呼应 Phase 0 资源隔离）。

**解决什么**：从"写死规则"升级到"AI 生成策略"，且过程可解释、可调试。
**验证**：单 Agent 能调 `historical_sales_analyzer` 工具、产出结构化结果；Critic 能拦截"价 < 成本"的不合规输出并触发重试。

---

## Phase 8 — 多智能体离线规划（M4）

**目标**：把运营目标变成可上线的选品/定价/库存方案 + 护栏 DSL。
**你会做**：Planner 拆 DAG → 选品/定价/预热/风控 Agent 并行产出 → Guardrail Policy Agent 把控盘预案编译成 **DSL JSON** → DSL 自编译校验（失败回 Critic ≤3 次）→ 版本化存 `guardrail_policy`（MySQL）+ 缓存 `guardrail:dsl:{aid}`（Redis 热更新）；**评估内建**：Critic 升级为独立验证器（确定性程序→规则→模型/人工三道校验），Agent 主动 ASK/REFUSE/ESCALATE 计入正确失败率，并采集 L2 轨迹指标、建立最小 golden 评估集。

**技术选型与目的**
- **多智能体分工 vs 单个大提示词**：单 Agent 一把梭，提示词爆炸、难维护、易遗漏；多 Agent 各管一摊（选品/定价/风控），结果经 Artifact 传递，清晰可审计。
- **DSL 而非"让 LLM 直接调接口"**：LLM 输出不稳定，直接调库存接口会出事；DSL 是"结构化、可编译、可版本化"的中间表示，把 AI 的"想法"和"执行"解耦。

**解决什么**：运营不用懂技术也能产出策略；AI 产出可被编译/灰度/回滚。
**验证**：输入「双11 美妆场 GMV 500万」→ 产出选品清单 + 定价 + 一份可上线 DSL。

---

## Phase 9 — 护栏规则引擎（M4，在线确定性，核心亮点）

**目标**：把 DSL 以**确定性**方式落到热路径，LLM 不进热路径。
**你会做**：独立 `rule-engine` 模块：
1. **DSL 静态编译器**（Antlr 或纯 Java）：启动前 + 每版发布时校验语法/类型（`metric` 是否在枚举、`AND/OR` 嵌套是否合法）。**编译不过 Fail-Close 直接阻断上线**。
2. 消费 `sales.tick` 事件 → 条件匹配 → 输出标准化控盘指令（`REPRICE`/`REALLOCATE`）。
3. 调拨/调价**走同一 Redisson 锁**（0ms 超时，失败重入队 5s）；**调拨只建新段 `seg_{N+1}`**。
4. **两阶段预留**：调拨先加 `pending_stock`（缓冲池），补偿时**只删 pending 标记，绝不碰已售硬库存**。
5. **预测性调拨**：滑动窗口线性回归算 `TTL = 剩余库存 / 流速`，`TTL < 5min` 提前预调拨。

**结果评估内建**：DSL Fail-Close 作为 L3 结果评估硬门槛（发布前 100% 编译通过）；新增影子模式（`sales.tick` 副本评估、不执行）+ 历史回测骨架（重放断言 oversell=0 / SLA 达标）+ 护栏命中埋点 + 风险分级（查询低 / 写入中 / 跨活动预算再分配高）。详见 `agent-observability-evaluation.md`。

**技术选型与目的**
- **独立模块**：AI 策略逻辑与并发原语解耦，扩展规则（阶梯调价、多池联动）不污染秒杀核心。
- **DSL 静态编译器（致命隐患 #3）**：LLM 可能抽风生成 `threshold:0.999` 或错误嵌套 → 运行时裸奔。编译器把"能不能上线"在启动前挡住。
- **两阶段预留 pending_stock（加分项 #2）**：补偿时若硬库存已被抢光，直接恢复会成负数超卖；pending 只删标记，永不碰硬库存。
- **预测性调拨（加分项 #1）**：从"售罄 2 倍才救火"变"TTL<5min 就调度"，AI 控盘智能感拉满，且是确定性算法、**不进 LLM**。

**解决什么**：AI 策略真实驱动高并发写路径，但彻底规避大模型延迟/幻觉/非确定风险。
**验证**：规则引擎并发控盘时用户流量优先；注入错误 DSL → 编译器拒绝、不上线；模拟补偿时硬库存=0 → 仅删 pending、无超卖。

---

## Phase 10 — 前端 + 可观测 + 部署（M5）

**目标**：能演示、能监控、能一键起。
**你会做**：React 商户后台（建活动/看策略）+ 实时大屏（WS 推送）；大屏**策略可解释性**——规则引擎每次调价/调拨用**后端模板**生成自然语言（如「因 SKU-A 流速超预期 210%，触发 Rule#r1，自动从池B调入 50 件」），**非 LLM 实时生成**；Prometheus+Grafana 看板（扣减耗时/锁等待/Agent 与规则引擎维度）；Docker 编排（Seckill/Agent/Rule-Engine Worker 资源隔离）；**可观测增强**：在 Grafana 之外新增 Trace 查看器 UI（span 树 + Critic verdict）、四层评估指标看板（含正确失败率/护栏命中/成本延迟）与 HITL 人工审批界面（何时找人 / 人看什么证据 / 人从哪继续）。

**技术选型与目的**
- **React + Ant Design + ECharts**：生态成熟、类型安全、图表强。⚠️ 待确认：React vs Vue。
- **后端模板生成解释文案（加分项 #3）**：对比"LLM 实时生成"——实时生成有延迟/不稳定，且违背"LLM 不进热路径"；模板生成确定性、秒出、零成本。
- **可观测**：没有指标就无法证明"无超卖/控盘有效"，面试 demo 也缺说服力。

**解决什么**：把后台智能"可视化"，演示冲击力；监控保障线上可控。
**验证**：大屏实时刷新、解释文案正确；Grafana 有扣减耗时与锁等待曲线。

---

## Phase 11 — 评估闭环（M6）

**目标**：用数据证明"两件事"——① 秒杀绝对无超卖；② AI Agent 策略真提升经营指标。
**你会做**：JMeter 百万抢购压测（验证无超卖+exactly-once）；双轨评估 harness（**客观硬指标优先**：毛利率/售罄率/库存周转，+ **LLM 定性补充**）；A/B 实验（一部分商家 AI 控盘、一部分人工规则，对比 GMV/售罄率/毛利）；GitHub Actions CI 跑 eval，阈值失败阻断；**评估闭环增强**：新增离线 eval harness（golden + 失败样本回归重放）、影子模式灰度（shadow→单活动→多活动）、系统指标报告（含正确失败率这一核心红线）。

**技术选型与目的**
- **双轨评估**：纯 LLM 打分有主观偏差；客观数值（毛利率等）才是硬证据，LLM 仅做补充定性。
- **A/B 实验**：没有对照就不知道 AI 到底有没有用；A/B 形成数据飞轮，策略持续迭代。

**解决什么**：项目从"功能有但无法验证"升级为"可量化、可上线"。
**验证**：输出量化报告——无超卖 + AI 控盘组售罄率/毛利优于人工组。

---

## 附录 A — 待确认决策汇总（⚠️）

| # | 决策 | 我的默认推荐 |
|---|---|---|
| 1 | JDK 版本 | 21 |
| 2 | 构建工具 | Maven |
| 3 | 前端框架 | React |
| 4 | 用户模型 | 单用户 JWT（后扩多用户） |
| 5 | 规则引擎 | 自研轻量 Java（vs Drools） |
| 6 | 预测性调拨 / A/B | 纳入（M4 / M6） |
| 7 | 报告导出 PDF/Word | 延后 M7+ |
| 8 | 范围红线 | 优惠券/积分/分销等延后 M7+ |

## 附录 B — 致命隐患 ↔ 落地位置速查

| 隐患 | 在本文位置 |
|---|---|
| 锁双标（规则引擎阻塞用户） | Phase 3 锁超时分级 0ms/10ms |
| 分段调拨阻塞热段 | Phase 3 调拨只建新段 |
| DSL 幻觉/冷启动裸奔 | Phase 9 DSL 静态编译器 Fail-Close |
| 补偿超卖 | Phase 9 两阶段预留 pending_stock |
| 被动救火 | Phase 9 预测性调拨 |
| 控盘不可见 | Phase 10 大屏可解释性 |

> 下一步：你确认附录 A 的决策（或保留默认）→ 从 Phase 0 开始，配合 `tasks.md` 逐任务勾选推进。
