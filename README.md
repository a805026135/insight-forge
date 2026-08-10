# InsightForge（智析）—— 智能秒杀 / 大促平台（Agent 辅助）

> **一句话定位**：一个用 Java 21 写的企业级「秒杀 / 大促」后端，由 LLM 智能体在**离线**侧做选品 / 智能定价 / 库存预热 / 风控，并把决策编译成**确定性护栏 DSL**，由规则引擎在**在线**侧执行——LLM 永不进入高并发热路径。
>
> **项目性质**：面向中高级 Java / AI Agent 岗位的**真实可上线、可量化评估**的面试项目。不是 demo、不是「套壳客服」。

---

## 一、为什么这是「真项目」而不是 demo

| 维度 | demo 常见做法 | 本项目做法 |
|---|---|---|
| 防超卖 | 只有乐观锁，`stock>=0` 靠运气 | 原子条件更新 `UPDATE ... SET stock=stock-? WHERE stock>=?` 作唯一真相；Redisson 锁 + Kafka 削峰 + 对账兜底 |
| AI 接入 | 用 LLM 直接回用户话 / 做推荐外壳 | **控制面 / 数据面分离**：LLM 离线生成策略 DSL，Drools 在线确定性执行，热路径零 LLM |
| 缓存 | 只套一层 Redis | Caffeine + Redis 多级缓存（防击穿单飞 / 防雪崩 TTL 抖动 / 防穿透 NULL 哨兵） |
| 限流 | 没有 / 只靠网关 | Redis+Lua 滑动窗口（按 用户×活动 维度），本地退化内存版 |
| 可证明 | 「我觉得没问题」 | 评估闭环：golden 回归 + 失败样本回归重放 + A/B 双指标 + 正确失败率红线 + CI 门槛失败阻断 |
| 诚实 | 虚报「已部署上线」 | **明确标注**哪些在沙箱实跑、哪些仅写就未实跑（见第八章） |

---

## 二、核心架构思想：控制面 / 数据面分离

```
┌─────────────────────────── 控制面（离线 / 允许慢、允许 LLM）──────────────────────────┐
│  Multi-Agent 编排：Planner → 选品/定价/预热/风控 Agent → GuardrailPolicyAgent 汇编 DSL   │
│  → DslCompiler 两道 Fail-Close 校验 → 发布版本化护栏策略（guardrail:dsl:{aid}）          │
└───────────────────────────────────┬──────────────────────────────────────────────────┘
                                      │  DSL（纯数据，确定性）
                                      ▼
┌─────────────────────────── 数据面（在线 / 强一致、零 LLM）──────────────────────────┐
│  规则引擎（Drools）吞「库存水位 tick」→ 吐控盘指令（REPRICE/REALLOCATE/REFUSE）           │
│  → ControlInstructionExecutor 走与用户抢购【同一把锁】LocalLockService（0ms tryLock）     │
│  → StockSegmentService 只建新段 + pending 两阶段预留（防补偿超卖）                        │
│  用户抢购热路径：Redis+Lua 原子扣减 → 条件更新 → Kafka 异步建单 → 幂等                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**为什么这样设计**：秒杀的命脉是「高并发下对共享可变状态（库存/额度）的强一致扣减」，LLM 有延迟、有非确定性、有知识截止、会幻觉——它**不能**进这个热路径。但 LLM 擅长「离线规划复杂策略」。本项目让两者各司其职：LLM 算「该怎么调」，Drools 算「现在执行不执行」。

---

## 三、技术栈

**后端（Java 21 + Spring Boot 3.3.5 + Maven 多模块）**
- 并发与一致性：`Redis` + `Redisson`（分布式锁本地桩，生产换真 Redisson）、`Redis+Lua` 原子扣减、`Kafka` 异步削峰、MySQL 8（`activity_sku` / `orders` / `agent_trace`）
- 缓存：`Caffeine`（L1）+ `Redis`（L2）多级缓存
- 规则引擎：`Drools` 9.x（纯 Java 库，DSL→DRL 编译，Fail-Close 门禁）
- 向量 / 记忆：`Milvus`（长期记忆显式桩，生产接入）
- Agent 运行时：自研（工具注册+JsonSchema 校验 / ReAct / 三层记忆 / Critic 反思重试）+ `Spring AI 1.0.0-M6` 接 `DeepSeek`（条件启用，默认离线确定性桩）

**前端（React + TypeScript + Vite + Ant Design + ECharts）**
- 大屏 Dashboard / TraceViewer / HITL 三页，`useDashboardSnapshot`（WS 优先、轮询兜底）

**运维 / 验证**
- `Docker Compose`（Seckill / Agent / Rule-Engine Worker 资源隔离）+ `Nginx`（令牌桶限流）+ `Prometheus` / `Grafana`
- `JMeter 5.x` 压测计划 + `GitHub Actions` 评估闭环 CI

---

## 四、模块划分（Maven 多模块）

| 模块 | 职责 |
|---|---|
| `insightforge-common` | 公共工具 / 常量 / 异常 |
| `insightforge-persistence` | MyBatis Mapper + 实体 + 种子 SQL（H2 / MySQL） |
| `insightforge-seckill-core` | 秒杀核心：原子扣减、多级缓存、限流、状态机、对账 |
| `insightforge-api` | Spring Boot 启动 + Controller + 可观测（Dashboard/Explain/Prometheus/WS） |
| `insightforge-agent-core` | Agent 运行时 + 多智能体离线规划 + 评估闭环（evaluation 包） |
| `insightforge-rule-engine` | Drools 在线执行 + 影子 / 回测（对 agent-core `optional`，不向下游传染 spring-ai/webflux） |

---

## 五、Phase 0–11 ↔ 里程碑 M1–M6 映射

| 里程碑 | Phase | 主题 | 关键交付 |
|---|---|---|---|
| **M1** | 0–1 | 环境与骨架 / 最小秒杀主流程（故意不安全） | 工程脚手架、秒杀主链路跑通、NoOversell 回归测试雏形 |
| **M2** | 2–6 | 并发心脏 | DB 原子条件更新防超卖 → Redis+Lua+Redisson → Kafka 异步+幂等 → 多级缓存+限流 → 状态机+对账 |
| **M3** | 7 | Agent Core 自研运行时 | 工具注册 / ReAct / 三层记忆 / Critic / 监测内建（Trace+正确失败率） |
| **M4** | 8–9 | 多智能体离线规划 + 护栏规则引擎 | DSL 编译 Fail-Close → Drools 在线确定性执行（走同一把锁） |
| **M5** | 10 | 前端 + 可观测 + 部署 | React 大屏 / Prometheus 指标 / Docker Compose + Nginx |
| **M6** | 11 | 评估闭环 | 双轨评估 harness + A/B + 系统指标（正确失败率红线）+ CI 门槛失败阻断 |

---

## 六、致命隐患 → 解法（面试高光）

| 隐患（秒杀经典坑） | 本项目解法 | 所在 Phase |
|---|---|---|
| 超卖（并发扣成负数） | `stock>=#{qty}` 原子条件更新，受影响行数作唯一真相 | 2 |
| 锁双标（规则引擎阻塞用户抢购） | 锁超时分级 0ms / 10ms，规则引擎走独立 0ms Fail-Fast | 3 |
| 分段调拨阻塞热段 | 调拨只建新段 `seg_{N+1}`，O(1) 不碰热段 | 3 |
| DSL 幻觉 / 冷启动裸奔 | DSL 静态编译器两道校验（结构 + 业务护栏）Fail-Close | 9 |
| 补偿超卖 | 两阶段预留 `pending_stock` | 9 |
| 缓存击穿 / 雪崩 / 穿透 | 单飞 + TTL 随机抖动 + NULL 哨兵 | 5 |
| 重复点击建重单 | Kafka 幂等 + 去重表 | 4 |
| Agent 该拒绝时硬上 | 正确失败率红线：ASK/REFUSE/ESCALATE 计入正确失败率，绝不编造成功 | 7/11 |
| 退化无人知 | 失败样本回归重放 + golden 回归 + CI 门槛失败阻断 | 11 |

---

## 七、监测与评估体系（含正确失败率红线）

依据生产级 Agent 评估四层框架（组件 / 轨迹 / 结果 / 系统）内建进 Phase 7–11：

- **L1 组件指标**：工具调用、参数校验、记忆读写、模型调用（含预算 / Token 上限 `BudgetGate`）
- **L2 轨迹指标**：Critic 拦截数、越权拦截、反思重试恢复
- **L3 结果指标**：DSL Fail-Close 通过率、影子比对、回测 `oversell=0` & SLA
- **L4 系统指标**：运行成功率、**正确失败率（红线）**、成本、延迟、人工接管
- **三条铁律**：① Trace 只服务于定位 / 重放 / 回归，不为漂亮大屏；② 独立 Critic 验证器，可验证结果优先程序校验；③ 正确失败（该拒时拒）计入正确失败率，**绝不编造成功**。
- **CI 闸门**：`EvaluationThresholds`（golden 验收率 100% / 失败样本回归 100% / AI 售罄率提升≥5% / AI 毛利率≥20% / 正确失败率≤100%）由 `EvaluationHarnessTest` 在 `mvn test` 断言；任一不达标 → 构建红 → 合并被阻断。

---

## 八、诚实边界清单（本项目最看重的一条）

> 原则：**能在沙箱实跑的，实跑验证；跑不了的，写就并明确标注，绝不虚报数字。**

| 部分 | 状态 | 说明 |
|---|---|---|
| 秒杀核心（扣减 / 缓存 / 限流 / 状态机 / 对账） | ✅ 实跑通过 | `mvn clean package` 全绿（101 测试） |
| Agent 运行时 / 多智能体规划 / 规则引擎 | ✅ 实跑通过 | Drools 纯 Java 库，JDK21 沙箱可行 |
| 评估闭环（golden / 回归 / A/B / 门槛） | ✅ 实跑通过 | 101 测试全绿，门槛断言可信 |
| **真实闭环（Docker 端到端）** | ✅ **2026-08-10 实跑通过** | mysql/redis/kafka + api + rule-engine-worker：85 人抢购 → sales.tick → REPRICE/REALLOCATE 真实执行 → 取消回补 → 结算对账三方一致 → 大屏 GMV 真实统计 |
| **真实 A/B 评估** | ✅ 规则引擎驱动模拟市场 | 人工 82.4%/36.5% vs AI 82.4%/37.4%（毛利 +2.33%），指标由 Drools 逐心跳真实执行产出，非种子自证 |
| **鉴权** | ✅ BCrypt + JWT | 密码哈希存储、登录签发 JWT、角色隔离（买家/商家）、秒杀以登录身份为准 |
| **上线级安全收尾** | ✅ | JWT 密钥环境化、WS 握手鉴权（商家专属）、actuator 只暴露 health+prometheus |
| **Flyway 统一 Schema** | ✅ | 单一迁移源（H2/MySQL 共用），删除三份手同步 SQL；已有库 baseline 不丢数据 |
| **评估接真实 trace 指标** | ✅ | 正确失败率/延迟/成本聚合 agent_trace 真实表，种子源改为显式开关 |
| **浏览器验收** | ✅ 真实 Chromium 实测 | 登录→大屏→秒杀→下单→订单全流程，控制台 0 报错（10 张截图留存） |
| Prometheus + Grafana | ✅ 容器实跑 | Prometheus 抓取 `health="up"`，Grafana 面板预置（宿主 3001） |
| 真 DeepSeek 模型 / Milvus / Redisson 真集群 | ⚠️ 桩（写就未实跑） | 沙箱无 API Key / 外网 / 集群，用确定性离线桩代替；生产换真 |
| React 前端大屏 | ✅ 真实浏览器（Chromium）实测 | 全页面走通 + 控制台 0 报错；截图见 `.tools/browser-check/shots/` |
| Docker / Nginx / Grafana 部署 | ✅ 已实跑 | docker compose 一键起全套（端口映射见交接文档 §4.5） |
| JMeter 百万压测 | ⚠️ 写就未实跑 | 沙箱无 JMeter / 无运行中服务；`.jmx` 含无超卖对账 SQL |
| GitHub Actions CI | ⚠️ 写就未实跑 | 沙箱无 CI runner；推到 GitHub/Gitee 后自动跑 |

**量化验收（实跑可证）**：累计 **106 测试全绿**（api 49 + agent-core 26 + rule-engine 30 + seckill-core 2）；A/B 由规则引擎驱动的模拟市场产出（人工 82.4%/36.5% vs AI 82.4%/37.4%）；正确失败率 ∈ [0,1]；真实闭环端到端（Docker）已实跑验证。

---

## 九、如何运行

> 📘 **想一步步在本机把整套跑起来（含前端大屏 / Docker 编排 / JMeter 压测 / CI 闸门）？** 看详细指南：[`specs/insightforge/本机实跑指南.md`](specs/insightforge/本机实跑指南.md)（分 4 档递进，附验证清单与排错表）。

```bash
# 1) 后端构建 + 全量测试（沙箱已实跑通过）
cd insightforge
mvn clean package
# 期望：BUILD SUCCESS，累计 106 测试全绿

# 2) 可选：单独打印评估闭环报告（门槛不过退出码 1）
mvn -pl insightforge-agent-core -am test-compile exec:java \
  -Dexec.mainClass=com.insightforge.agent.core.evaluation.EvaluationGateMain

# 3) 前端（需本机 Node ≥ 18，沙箱未验证）
cd insightforge-frontend && npm install && npm run dev   # http://localhost:5173

# 4) 部署（需本机 Docker，沙箱未验证）
docker compose -f deploy/docker-compose.yml up -d         # nginx:80 / grafana:3000 / prometheus:9090

# 5) 压测（需本机 JMeter 5.x + 先启动 seckill 服务，沙箱未验证）
jmeter -n -t deploy/jmeter/seckill-load-test.jmx -l seckill-result.jtl
# 压完做无超卖对账：MIN(stock)>=0 且 初始库存 - SUM(sold) = SUM(stock)
```

---

## 十、目录结构速览

```
D:/Java
├── insightforge/                 # Maven 多模块后端
│   ├── insightforge-common / -persistence / -seckill-core / -api
│   ├── insightforge-agent-core   # Agent 运行时 + planning + evaluation
│   └── insightforge-rule-engine  # Drools 在线执行 + 影子/回测
├── insightforge-frontend/        # React + TS + Vite + Antd + ECharts
├── deploy/                       # docker-compose / Dockerfile / nginx / prometheus / grafana / jmeter
├── .github/workflows/eval.yml    # 评估闭环 CI（门槛失败阻断）
└── specs/insightforge/           # spec-kit 设计文档 + tutorials/phaseN.md 食谱式教程
```

---

## 十一、延伸阅读（设计文档）

- `specs/insightforge/spec.md` —— 总体规格（v0.9，含监测与评估设计 §1.5）
- `specs/insightforge/analysis.md` —— 竞品 / 技术选型 / 重后端理由
- `specs/insightforge/agent-observability-evaluation.md` —— Agent 监测与评估四层框架设计
- `specs/insightforge/tutorials/phaseN.md` —— 每个 Phase 一份手把手食谱式教程（建目录→完整代码→验证→排错表）
- `specs/insightforge/面试讲解稿.md` —— **面试常见问答与讲解稿**

---

© InsightForge 教学项目 · 真实可跑、可量化、诚实标注。
