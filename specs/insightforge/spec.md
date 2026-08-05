# InsightForge（智析）多智能体数据分析与报告平台 — 设计文档（v0.1 评审稿）

> 流程说明：本文档由 brainstorming 阶段产出，遵循 **GitHub spec-kit** 流程——先定 spec（本文件），经评审调整后再出 `plan.md` 与 `tasks.md`，最后进入实现。
> 状态：评审稿 v0.1，待用户通读后调整。
> 修订记录：[2026-08-05] 初始评审稿；同日反馈修订——后端须与 Agent 域深度融合（按痛点驱动选型，新增 §3.3 全链路）、加入 `web_search` 工具。

---

## 1. 项目定位与目标

### 1.1 一句话定位
一个**多智能体数据分析与报告平台**：用户上传数据或接库，多个角色 Agent 协作完成「规划 → 取数 → 可视化 → 成文 → 自检」，产出**可溯源**的结构化报告，并配套**自动评估闭环**。

### 1.2 目标（成功标准）
- **面试项目，但要求真实完整**：能本地 Docker 一键起、能部署上线、能跑量化评估，不是玩具 demo。
- **两条主线都要硬**：① 后端工程化（缓存/分布式锁/消息队列/限流/异步/压测/部署）；② Agent 知识（规划/工具调用/记忆/反思/多 Agent 协作）。两条线都有可演示、可量化的产出。
- **有评估闭环**：用硬指标证明系统靠谱（任务成功率、取数正确性、报告保真度、成本/时延、并发吞吐）。

### 1.3 非目标 / YAGNI（明确不做，防膨胀）
- 不做通用「Agent 开发平台」（工具市场 / 多租户 SaaS）。
- 不追求覆盖所有数据格式，先 **CSV / Excel / Parquet + 可选 JDBC**。
- 不内置训练 / 微调，专注推理与编排。
- 不做复杂 RBAC，先做 JWT 单用户 / 简单多用户。

---

## 2. 技术栈总览（企业级，按 Agent/分析域痛点驱动）

| 类别 | 选型 | 作用 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.3 + Maven | 后端主体 |
| 持久层 | MyBatis-Plus | ORM |
| 关系库 | **MySQL 8**（可主从） | 任务/数据集/报告/评估持久化 |
| 缓存/锁/限流 | **Redis 7 + Redisson** | 缓存、分布式锁、限流、任务状态 |
| 消息队列 | **Kafka** | 任务异步解耦、削峰 |
| 多级缓存 | **Caffeine + Redis** | 本地一级 + 分布式二级 |
| 向量库 | **Milvus**（企业级） | 语义缓存、相似分析复用、历史结论检索 |
| Agent 接入 | **Spring AI + DeepSeek** | 模型调用 / Embedding |
| 安全 | JWT + Redis 会话 + Spring Security | 鉴权 |
| 限流 | Redis + Lua 滑动窗口 / Sentinel | 保护模型额度 |
| 压测 | JMeter | 验证并发与限流 |
| 前端 | React 18 + TS + Vite + Ant Design + ECharts | Web 界面 |
| 部署 | Nginx + Docker Compose + GitHub Actions | 负载均衡 / 一键起 / CI |
| 可观测 | Actuator + Prometheus + Grafana | 调用耗时/成功率/限流看板 |

**后端能力 ↔ Agent/分析场景 的驱动关系（杜绝"为用而用"）**

> 设计原则：每个后端组件都必须由 Agent 系统的某个真实痛点驱动，而不是把黑马点评的清单贴上来凑数。下面按「痛点 → 后端解法」重新落地，前后端是同一套设计的两面。

| 本系统的真实痛点（来自 Agent / 分析域） | 后端解法（企业级组件） |
|---|---|
| Agent 跑一次分析要几十秒~几分钟，不能阻塞 HTTP | **Kafka** 异步解耦：提交即返回 `taskId`，Worker 消费执行；**WebSocket** 推进度 |
| 大模型慢、贵、有 RPM/TPM 配额 | **Redis+Lua 滑动窗口 / Sentinel 限流** 护 DeepSeek 额度；按用户计费（Redis 计数器） |
| 同一/相似问题被反复问，重跑太浪费 | **Milvus 语义缓存** + **Redis 精确缓存**：相似问题复用历史报告片段 |
| 热门公开数据集被多人并发查询，缓存失效瞬间打爆 DB/重解析 | **Caffeine+Redis 多级缓存** + 逻辑过期 + 随机 TTL（即"缓存击穿/雪崩"的本项目真身）；布隆过滤器防穿透 |
| 同一数据集/任务被并发触发，重复执行 | **Redisson 分布式锁** 保证解析与执行幂等 |
| 多个 Agent 并发跑子任务，工具执行是重负载且危险 | **线程池 + 信号量 + 容器沙箱** 做资源治理；失败按 DAG 回滚（saga） |
| 多用户多任务并发，需隔离与可追溯 | **JWT** 鉴权 + `traceId` 全链路透传 + Prometheus/Grafana 看板 |
| 上线前要证明"撑得住" | **JMeter** 压测多任务并发与限流生效 |

> 黑马点评教程里的 分布式锁 / 消息队列 / 多级缓存 / 缓存三大问题 / 限流 / 压测，在这里分别对应：任务幂等 / 异步执行 / 热点数据集 / 缓存击穿 / 模型配额 / 容量验证——全是本系统自己的问题，不是借来的装饰。

---

## 3. 高并发与部署架构

### 3.1 并发模型
- **提交流程**：API 写库 + 发 Kafka → 立即返回 `taskId`（不阻塞）；Worker 消费者拉取，线程池 + `CompletableFuture` 并发跑 DAG 子任务。
- **实时进度**：WebSocket 推送；Redisson 锁保幂等；对模型调用做令牌桶 / 滑动窗口限流。
- **三级加速**：① Caffeine 本地热点；② Redis 分布式；③ Milvus **语义缓存**（相似问题复用历史报告片段，省一次昂贵管线）。

### 3.2 部署架构
- `docker-compose` 编排 7 服务：`MySQL / Redis / Kafka / Milvus / API / Worker / 前端(Nginx)`。
- Nginx：API 负载均衡 + 前端静态托管 + WebSocket 反代；Worker 多实例、Kafka 分区并行 → 水平扩展。
- 可观测：Actuator + Prometheus + Grafana 看板（Agent 调用耗时 / 成功率 / 限流触发次数 / 队列堆积）。

### 3.3 一次分析请求的全链路（看后端如何被 Agent 真正用到）
以「分析 sales.csv，给出各区域上半年销售额 Top3 并出图」为例，后端与 Agent 是同一条链路的两端：

1. **API 层**：JWT 校验 → Redis 滑动窗口**限流**（超配额直接 429）→ 写任务表(PENDING) → 发 Kafka(QUEUED) → 返回 `taskId`（不阻塞）。
2. **Worker** 消费：Redisson 锁检查 dataset+query 是否已跑（**幂等**）；否则解析 CSV → DuckDB 内存表 → 写**数据画像**到 Redis（逻辑过期）。
3. **Orchestrator** 调度 Planner 产出 DAG；无依赖节点 `CompletableFuture` 并行。
4. **Data Agent** 调 `sql_executor`：先查 **Milvus/Redis 语义缓存**（相似历史分析直接复用）→ 未命中则进**沙箱**执行，结果经校验器。
5. **Viz / Writer Agent**：出 ECharts + 报告；Writer 结论必须引用 Data 产出的具体数字。
6. **Critic Agent** 自检；不通过带 issues 回到对应 Agent **反思重试**（≤3 轮）。
7. 全程：每步状态变更发 Kafka + 更新 Redis + **WS 推前端**；`traceId` 贯穿，Grafana 可观测。
8. 完成：报告落 MySQL；用户点赞/点踩 → 写反馈表，进**评估飞轮**。

> 这条链路里没有"纯后端"或"纯 Agent"的部件：Kafka/Redis/Redisson/Milvus/WS 都是 Agent 跑起来的必要支撑，Agent 才是这些组件被调用的原因。

---

## 4. 多智能体编排设计

核心是一条**可观测、可回滚**的编排管线，不把活全塞给一个大模型：

- **Planner（规划）**：把用户问题拆成带依赖的**子任务 DAG**（如「先算各区域销售额 → 再算同比 → 最后挑 Top3 出图」）。
- **Data Agent（取数）**：调度 SQL / Python 工具真实执行，带**结果校验**（空值、量级异常、列一致性）。
- **Viz Agent（可视化）**：根据数据特征选图表类型，产出 ECharts 配置（柱状/折线/热力/漏斗）。
- **Writer Agent（成文）**：生成结构化中文报告，结论**必须引用具体数据**，杜绝空话。
- **Critic Agent（自检）**：三道关卡——① 数据是否支撑结论 ② 图表与文字是否匹配 ③ 有无幻觉/编造。不通过触发**反思重试**（≤3 轮）。
- **Orchestrator（总控）**：消息总线、并发控制、超时、失败回滚、全链路 `traceId`。

「Agent 知识」覆盖：规划、工具调用、记忆、反思/自检、多 Agent 协作、人在环（关键节点可介入）。

---

## 5. Agent Core 自研运行时

### 5.1 工具系统（Tool Registry）
- 每个工具用 JSON Schema 声明：`{name, description, parameters(JSON Schema), executor}`；启动时注册到 `ToolRegistry`。
- 模型输出 `tool_call`（DeepSeek function calling）→ 运行时**先校验参数 schema** → 进沙箱执行 → `observation` 回灌上下文。
- 内置工具：`sql_executor`、`python_executor`、`chart_generator`、`file_parser`、`semantic_cache_lookup`、`web_search`。
- **`web_search` 为何不是装饰**：数据分析常需外部基准（行业均值、汇率、实时统计）做对比/下钻；它同时驱动两个真实后端需求——① 外部 API 有配额，必须接入同一套 **Redis+Lua 限流**；② 搜索结果进 **Redis 缓存**（TTL），避免重复调用。换言之，`web_search` 是限流与缓存的天然压力源之一，而不是可选项。

### 5.2 记忆机制（三层）
- **短期**：当前任务消息窗口（system + 各 Agent 对话 + 工具结果）；超长触发**摘要压缩**（小模型/规则 `compress`，保留结论与关键数字）。
- **长期**：用户级偏好存 Redis Hash；历史任务摘要与结论向量化入 **Milvus**，供「相似分析复用」与用户追问上下文。
- **跨 Agent 共享**：经 Orchestrator 消息总线传递结构化 `Artifact`（数据片段/图表 config/中间结论），**不把一切塞进 prompt**，控成本也控噪声。

### 5.3 规划与执行（Plan-and-Execute）
- Planner 产出**子任务 DAG**（节点含依赖边 + 预期产出 schema）。
- Orchestrator 按拓扑序调度；无依赖节点 `CompletableFuture` 并行；节点失败按依赖图**回滚下游**。

### 5.4 反思/自检循环
- Critic 输出结构化 JSON：`{passed, issues:[{type, severity, detail, fix_suggestion}]}`。
- 不通过 → 携带 issues 回到对应 Agent 修订，最多 `maxRetry=3` 轮；超限则**降级交付**并标注「未通过自检」，绝不静默放行。

---

## 6. 数据接入 + 代码沙箱 + 异步任务状态机

### 6.1 数据接入
- **上传**：CSV/Excel/Parquet → DuckDB/Arrow 读成统一内存表；自动推断 schema（类型、缺失率、枚举、数值范围），生成「数据画像」供 Planner 参考。
- **可选 JDBC**：只读连接 + schema 自省（表/列/注释/外键）；SQL 生成受**白名单约束**（仅 SELECT、行数上限、禁系统表）；schema 用逻辑过期缓存。

### 6.2 代码沙箱（安全关键）
- **SQL**：DuckDB 内存实例（文件场景）或目标库只读事务（JDBC 场景），结果行数封顶 + 超时 kill。
- **Python**：`matplotlib`/`pandas` 跑在**隔离进程/容器**，`seccomp`+capability 限制，CPU/内存/墙钟配额（如 30s / 1GB），**禁网络与外写**；出图转 ECharts option 或图片。
- **结果校验器**：空表 / 量级异常 / 列缺失 → 反馈 Data Agent 重试。

### 6.3 异步任务状态机
- 状态：`PENDING→QUEUED→PLANNING→EXECUTING→VIZ→WRITING→REVIEWING→DONE` / `FAILED` / `CANCELLED`。
- 每次状态变更：发 **Kafka 事件** + 更新 **Redis 状态** + **WS 推前端**；Worker 崩溃可由事件重放恢复（至少一次语义）。
- 超时/重试：单节点超时回滚下游；整体超时告警；用户可 `CANCEL`。
- 幂等：Redisson 锁 + 任务去重（相同 dataset+query 在 TTL 内复用结果）。

---

## 7. 评估闭环

### 7.1 目标
量化「这个 Agent 系统到底靠不靠谱」，给面试/上线提供硬指标。

### 7.2 评测集（offline eval set）
- 构造 N 个带「黄金标准」的样例，覆盖典型分析意图：趋势、对比、占比、TopN、相关性、异常检测、多维下钻。
- 每个样例含：数据集(小型) + 用户问题 + 期望子任务 DAG(可选) + **黄金答案(关键数字/结论)** + 黄金图表类型。
- 数据来源：内置合成数据集 + 公开数据集（Kaggle 小样）裁剪；**黄金数字由确定性脚本算出**，避免人标偏差。

### 7.3 指标
- **任务成功率**：端到端 DONE 且通过 Critic 的比例。
- **取数正确性**：Data Agent 关键指标值与黄金值比对（允许容差），精确率/召回。
- **报告保真度（faithfulness）**：LLM-as-judge 按 rubric 打分（结论是否有数据支撑、有无幻觉），0–5 分。
- **图表适配度**：生成图表类型与黄金类型一致性 + 数据绑定正确性。
- **效率/成本**：端到端时延（P50/P95）、token 消耗、￥成本、反思重试次数。
- **并发**：JMeter 多任务压测下吞吐与限流触发率。

### 7.4 评估 harness
- `eval` 模块：跑评测集 → 调系统 API（或直调 Orchestrator）→ 收集产出 → 对照黄金标准算指标 → 输出报告（JSON + Markdown）。
- **LLM-as-judge**：用 DeepSeek 做裁判（独立于生成 prompt 的 rubric），输出结构化评分。
- **CI 集成**：GitHub Actions 在 PR 跑 `./mvnw eval:run`，关键指标低于阈值则失败，保证「改代码不退化」。

### 7.5 在线评估（数据飞轮）
- 报告页用户对结论点赞/点踩/标注错误 → 写入反馈表 → 定期复盘进评测集。

---

## 8. 项目结构与模块划分

Maven 多模块（parent：`insightforge`）：

```
insightforge
├── insightforge-api          # REST + WebSocket 接入层
├── insightforge-agent-core   # 自研 Agent 运行时（循环/工具/记忆/反思）
├── insightforge-agents       # Planner/Data/Viz/Writer/Critic 实现
├── insightforge-tools        # sql/python/chart/file/semantic-cache 工具
├── insightforge-datasource   # 文件接入 + JDBC
├── insightforge-sandbox      # 代码沙箱（隔离执行）
├── insightforge-persistence  # 实体与仓储
├── insightforge-eval         # 评估 harness
├── insightforge-common       # 安全/日志/配置/限流/异常
└── insightforge-gateway      # （可选）网关/聚合
web/                          # React + Vite 前端
docker/                       # docker-compose / Dockerfile
specs/insightforge/           # spec.md / plan.md / tasks.md
```

---

## 9. 里程碑与路线图

- **M1 脚手架**：Maven 多模块 + Spring Boot 跑通 + MySQL/Redis/Kafka/Milvus docker-compose + CI 骨架。
- **M2 数据接入与沙箱**：文件解析 + 数据画像、JDBC 自省、SQL/Python 沙箱 + 结果校验。
- **M3 Agent Core**：工具注册、ReAct/Plan-Execute 循环、记忆、反思重试、状态机。
- **M4 多智能体编排**：五个 Agent + Orchestrator + WS 进度 + 多级缓存 + 限流。
- **M5 前端与部署**：React 界面（上传/进度/报告/图表）、Nginx、Docker 部署、监控看板。
- **M6 评估闭环**：评测集 + harness + LLM-as-judge + CI 回归 + 压测报告。

每个 M 都有可演示产出，避免「最后才看到一个能跑的东西」。

---

## 10. 风险与缓解
- **沙箱安全** → 容器隔离 + 资源配额 + 禁网 + 超时 kill。
- **模型成本/不稳定** → 语义缓存 + 限流 + 重试 + 降级交付。
- **评估主观** → 黄金数字用确定性脚本、judge 用结构化 rubric。
- **范围过大** → 每个 M 可演示，非目标列 YAGNI（见 §1.3）。

---

## 11. 开放问题（待用户调整）
1. 向量库：**Milvus** vs **pgvector**（pgvector 更简单但企业级感弱）。
2. 消息队列：**Kafka** vs **RabbitMQ**（RabbitMQ 更轻，Kafka 更「企业级」）。
3. ~~是否要 `web_search` 工具~~ → **已决定：要**（它驱动限流与缓存的真实需求，见 §5.1）。
4. 前端 **React**（默认） vs **Vue**。
5. 报告导出：是否要 **PDF / Word** 导出。
6. 多用户：先做 JWT 单用户，还是上简单多用户。

---

> 下一步：用户审阅本文档并反馈调整 → 我据此修订 → 产出 `plan.md`（实现方案）+ `tasks.md`（任务拆解）→ 进入实现（spec-kit 工作流）。
