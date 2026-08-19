# InsightForge（智析）—— 智能秒杀 / 大促运营平台

> Java + AI Agent 面试项目：**高并发强一致秒杀 × 控制面/数据面分离的 Agent 护栏 × 可观测可评测的闭环**。

## 一句话说清楚

秒杀像「厕所就 100 个坑位，1 万人同时冲」——必须保证不超卖。
AI 像「聪明的店长」，离线算好今天哪些货该降价、哪些该从别的仓调货，把策略写成规则——但**店长不能冲在门口收门票**（太慢会堵死），所以门口检票机按规则自动执行。

**LLM 永不进入秒杀热路径。** 延迟/幻觉/非确定性与强一致性天生冲突。LLM 只出现在离线控制面（规划、生成护栏 DSL），在线数据面由 Drools 确定性执行、零 LLM。

---

## 项目状态

| 维度 | 状态 |
|------|------|
| 后端全链路 | ✅ Phase 0–14.5 全部落地，**109 测试全绿**（api 52 / agent-core 26 / rule-engine 30 / seckill-core 2），`mvn clean package` BUILD SUCCESS |
| 真实 Docker 端到端 | ✅ 阿里云 Docker Compose（mysql/redis/kafka/api/rule-engine-worker/nginx/prometheus/grafana）实跑通过：**85 人并发抢购 85/85 成功**，worker 消费 `sales.tick` 真实 REPRICE（6299→6598.9）+ REALLOCATE（+50），DB 与 Redis 三方一致，结算对账 passed |
| 真 A/B 评估 | ✅ 确定性模拟器逐笔喂规则引擎：人工毛利 36.5% vs AI 毛利 37.4%（**+2.33pct**），真 DeepSeek 产 DSL 门槛 GATE PASSED |
| 评测体系 | ✅ 40 条 RAG 受控集（Recall@4/事实/拒答 100%）+ 50 条 Agent 任务集（Single P95 6.887s / 475 token vs Multi 55.008s / 4237 token） |
| 沙箱验证 | ✅ JDK 21 + Maven 3.9.9 实跑通过 |
| 待办（诚实标注） | ⚠️ JMeter 百万压测未实跑（本机无 JMeter）；Milvus 为桩实现；Canal/Nginx+Lua 限流/Sentinel 未落地 |

---

## 快速开始

### 前提

- JDK 21 + Maven 3.9+（本机：`D:/devtools/jdk21/jdk-21.0.12+8`、`D:/devtools/maven/apache-maven-3.9.9`）

### 构建（零依赖，开箱即跑）

```bash
cd insightforge
mvn clean package
```

### 启动（`local` profile，零依赖）

```bash
cd insightforge-api
java -jar target/insightforge-api-*.jar --spring.profiles.active=local
```

访问 `http://localhost:8080/actuator/health` → `{"status":"UP"}`。

### 集成环境（需要 Docker，跑真实 Redis/Kafka/Redisson）

```bash
docker-compose up -d    # 起 MySQL/Redis/Kafka
cd insightforge-api
java -jar target/insightforge-api-*.jar --spring.profiles.active=integration
```

---

## 模块结构

```
insightforge/
├── insightforge-api/             # REST + WebSocket 接入层（52 测试）
├── insightforge-seckill-core/    # 高并发引擎：Lua/Redisson/Kafka/幂等/状态机（2 测试）
├── insightforge-rule-engine/     # 护栏规则引擎：Drools 在线执行/调拨/调价（30 测试）
├── insightforge-agent-core/      # 自研 Agent 运行时：工具/记忆/反思/评估/评测（26 测试）
├── insightforge-persistence/     # 实体 + MyBatis-Plus 仓储
├── insightforge-common/          # 公共工具/配置/异常
└── pom.xml                       # 父 POM（Spring Boot 3.3 + Drools 9 + MyBatis-Plus）
```

---

## 核心亮点（面试故事线）

### 1. 高并发防超卖（分层纵深 + 最终一致性收敛）

| 层 | 手段 | 大白话 |
|----|------|--------|
| 快速扣减 | Redis Lua 原子 DECRBY（单 key 不可打断） | 门口快速发号 |
| DB 兜底 | `UPDATE ... SET stock=stock-? WHERE stock>=?` 受影响行数 | 仓库真库存，最终真相，**不会负库存** |
| 幂等去重 | 确定性订单号 `OF-活动-SKU-用户` + Redis SETNX + DB UNIQUE 双保险 | 同一个号永远只办一次，重复消费/重复提交无害 |
| 补偿巡检 | 每 30s 扫幂等键，发现「Redis 扣了、DB 没落单」→ 幂等补单 | 打烊点货，漏办的单子自动补办 |
| 对账 | 活动/订单状态机 + SUM(qty) 交叉核对 Redis/DB | 差一分钱都不行 |

**拷打回应**：Redis 和 DB 不在同一本地事务，不装强一致——但**每一种不一致都预演过并有收敛路径**：Redis 挂了 → DB 条件更新仍不超卖（顶多少卖）；窗口期没落单 → 补偿巡检 30s 内幂等补单（同一订单号，唯一约束保证不重复）。**Redis 是窗口拿号，DB 是柜台办业务，巡检员兜底。**

实测：300 并发争抢 100 库存，100 成功、200 售罄、库存归零无超卖；Redis Lua 热路径 P95 35.33ms、吞吐 3114.82 RPS（隔离组件基线）。

### 2. 控制面/数据面分离（Agent 护栏）

- **离线（控制面）**：LLM 分析经营数据 → 生成护栏 DSL（定价区间/调拨量/告警阈值）
- **在线（数据面）**：Drools 确定性执行 DSL，**零 LLM 进热路径**
- **Fail-Close**：DSL 编译不过（REPRICE < 成本 / REALLOCATE < 0）→ 拒绝发布，绝不带病上线
- **同一份库存**：Agent 调拨用 `INCRBY` 写回秒杀同一个 `stock:a:{aid}:s:{skid}`，用户可抢到 AI 调过来的库存——控制面与数据面在真实库存上相遇
- **三道闸**：产物无论走哪条路径都过 `DslCompiler → Independent Critic → HITL 审批`

### 3. 成本感知调度（任务复杂度路由策略）

LLM 调用是成本与延迟的唯一主导项（见第 5 点观测）。据此确立**「简单任务默认单 Agent、复杂规划升级多 Agent」的路由策略**：

- **O(1) 启发式三信号**：结构（Planner 产出的编排 DAG：角色数/最长路径/并行分支/独立审查开关）+ 语义（goal 槽位解析：跨域数/约束冲突，复用 DslCompiler/GuardrailPolicy 同一份约束定义）+ 历史（模糊区懒加载查询 AgentTrace 同类任务真实 token/重试率）
- **模糊区**：轻量二分类 head（embedding + 浅层网络，**不引入 LLM 调用**）
- **权重**：用带成本标注的历史样本回归拟合（与实测 token 对齐），阈值/权重配置化可热更新
- **兜底三保险**：只许 fail-expensive（默认多 Agent、高置信才降级单 Agent，错判只多花钱不降安全）+ 产物必过三道闸 + 决策/特征/实际成本回写可观测链路，错判样本回流迭代

**证据**：50 条 Agent 任务集（30 正向 + 20 安全），Single 与 Multi 质量持平（全部通过），但 Single 平均 **475.22 token** vs Multi **4237.02 token**（约 1/9）；云端 15 任务集 386.87 vs 4071.73（约 1/10.5）。`SingleAgentEvaluationService` 提供可复跑的离线基线评测。

> 口径说明：这是**逐任务配对证据**（同集同跑，流量构成天然抵消），不是"上线路由后的总节省"；路由贡献需反事实对照重测，不夸大。

### 4. 多租户 RAG（意图路由 + 安全门 + Hybrid RRF）

- MySQL 存文档主数据与不可变版本（SHA-256），Milvus 存可重建向量索引，`merchantId` 元数据过滤实现商家隔离
- **意图路由**：按问题类别路由到不同检索源（售后政策/秒杀规则/FAQ/商家商品/历史决策）
- **安全门**：fail-close 拒答（root 密码、跨商家泄露、无事实实时查询、绕过审批），与线上 Agent 同口径
- **Hybrid RRF**：Dense + Sparse 融合排序

**证据**：40 条受控集（35 可答 + 5 拒答）同环境 A/B/C/D 消融——纯 Dense 基线 Recall@4 **74.29%** / 事实正确率 **65.71%** / 拒答 0%，当前方案 **100% / 100% / 100%**（绝对提升 +25.71 / +34.29 / +100 个百分点），检索 P95 611ms。

> 口径说明：40 条为**人工标注受控回归集**（`rag-eval-dataset.json`，版本化，关键词/文档键确定性打分，非 LLM-as-a-Judge），用于验证链路功能而非声称线上准确率；评测 JSON 与 Milvus 物理隔离，不存在数据泄露。

### 5. 可观测性（Langfuse + traceId）

- `TraceIdFilter` 生成/透传 X-Trace-Id 进 MDC，Kafka 消息带 traceId，消费者续上；模型 usage 同时写 MySQL 与 Langfuse，可按 traceId 核对模型/版本/延迟/成本
- **Langfuse 观测结论**：LLM 推理是绝对主导瓶颈（span 时长几乎吃掉整条 trace），Embedding 检索（P95 611ms）与工具 IO 为毫秒级零头；token 量与延迟强线性相关（Single 475 token/P95 6.9s vs Multi 4237 token/55s，约 8 倍）——**这份观测直接催生了第 3 点的成本感知调度**

### 6. 安全与治理

- JWT 密钥环境化（`INSIGHTFORGE_AUTH_JWT_SECRET`）+ BCrypt；WebSocket 握手指纹 + merchant 角色校验
- 退款客服闭环：Agent 仅查询 JWT 用户真实订单生成草稿，用户确认后才进事务接口，金额/归属由 DB 校验，RAG 只解释政策——**不用 Prompt 充当权限边界**
- actuator 仅暴露 health + prometheus；Flyway 统一 schema 管理

---

## 评测体系

| 评测 | 规模 | 结果 |
|------|------|------|
| RAG 受控消融 | 40 条（12 类别，A/B/C/D 四变体） | 100% / 100% / 100%，P95 611ms |
| Agent 任务集 | 50 条（30 正向 + 20 安全） | Single/Multi 质量持平；Single P95 6.887s、475 token；Multi P95 55.008s、4237 token |
| 秒杀防超卖 | 300 并发抢 100 | 无超卖、库存归零、P95 35.33ms |
| 引擎驱动 A/B | 确定性模拟器逐笔喂规则引擎 | AI 毛利 +2.33pct，门槛 GATE PASSED |
| Docker 端到端 | 阿里云 85 人并发 | 85/85 成功，REPRICE/REALLOCATE 真实生效，对账 passed |

---

## 文档导航

| 你想…… | 看这个 |
|---------|--------|
| 从零搭建，手把手学 | [`specs/insightforge/tutorials/00-学习路线.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/tutorials/00-%E5%AD%A6%E4%B9%A0%E8%B7%AF%E7%BA%BF.md) |
| 面试前突击 Q&A | [`specs/insightforge/面试讲解稿.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/%E9%9D%A2%E8%AF%95%E8%AE%B2%E8%A7%A3%E7%A8%BF.md) |
| 技术方案/架构决策 | [`specs/insightforge/plan.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/plan.md) |
| 详细设计规格 | [`specs/insightforge/spec.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/spec.md) |
| 任务清单（含完成状态） | [`specs/insightforge/tasks.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/tasks.md) |
| 术语速查 | [`specs/insightforge/glossary.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/glossary.md) |
| 本机怎么跑 | [`specs/insightforge/本机实跑指南.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/%E6%9C%AC%E6%9C%BA%E5%AE%9E%E8%B7%91%E6%8C%87%E5%8D%97.md) |
| 项目体检报告 | [`specs/insightforge/全项目体检报告.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/%E5%85%A8%E9%A1%B9%E7%9B%AE%E4%BD%93%E6%A3%80%E6%8A%A5%E5%91%8A.md) |

---

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言/框架 | Java 21 + Spring Boot 3.3.5 + Maven 多模块 |
| 持久层 | MyBatis-Plus + MySQL 8 + Flyway |
| 缓存/锁/限流 | Redis 7 + Redisson + Caffeine(L1) |
| 消息队列 | Kafka（削峰 + `sales.tick` 销量事件流） |
| 规则引擎 | Drools 9.44.0.Final（确定性执行，零 LLM） |
| Agent 接入 | Spring AI 1.0.0-M6 + DeepSeek（条件启用，默认离线桩） |
| 向量库 | Milvus（桩实现，待迁移） |
| 可观测 | Langfuse（trace/token/成本）+ Prometheus + Grafana + traceId 贯穿 |
| 前端 | React 18 + TypeScript + Vite + Ant Design + ECharts |
| 测试 | JUnit 5 + AssertJ + Embedded Redis |
| 部署 | Docker Compose + Nginx |

---

## 诚实边界（不虚报，每个数字都能解释分母）

- ✅ 后端 109 测试全绿；阿里云 Docker 端到端实跑通过；真 A/B 与评测均已实跑
- ⚠️ JMeter 百万压测未实跑（本机无 JMeter）；Aliyun 公网只读阶梯测试 16.67%~43.33% 错误率（公网/Nginx 瓶颈），不包装成高可用成果
- ⚠️ Milvus 仍为桩实现（`MilvusMemoryStore` 抛 UnsupportedOperationException，记忆默认 LocalMemoryStore 关键词召回）；Canal/Nginx+Lua 限流/Sentinel 未落地
- ⚠️ 跨 Redis/Kafka/MySQL 异常窗口靠幂等 + 补偿 + 对账收敛，Outbox/CDC 为明确增强方向（未上线）
