# InsightForge（智析）面向电商/零售商家的智能经营分析助手 — 设计文档（v0.3 评审稿）

> 流程说明：本文档由 brainstorming 阶段产出，遵循 **GitHub spec-kit** 流程——先定 spec（本文件），经评审调整后再出 `plan.md` 与 `tasks.md`，最后进入实现。
> 状态：评审稿 v0.1，待用户通读后调整。
> 修订记录：[2026-08-05] v0.1 初始评审稿；v0.2 后端与 Agent 深度融合（痛点驱动 + §3.3 全链路）+ `web_search`；v0.3 补全业务场景（商家经营分析），让技术栈贴着场景走（缓存击穿=热门行业基准并发、语义缓存=千商家相似问题、web_search=行业大盘对比、Redisson=商家数据幂等）。

---

## 1. 项目定位与目标

### 1.1 一句话定位
一个**面向中小电商 / 零售商家的智能经营分析助手**：商家上传订单数据或接库，多个角色 Agent 协作完成「规划 → 取数 → 可视化 → 成文 → 自检」，把"GMV 为何跌、哪类商品在拖后腿、和同行比是不是只有自己差"这类经营问题翻译成**可溯源**的结论与图表，并配套**自动评估闭环**。

**业务背景**：InsightForge 定位为面向中小电商 / 零售商家的「智能经营分析助手」（类 SaaS 形态）。商家日常最核心的痛点不是"画个图"，而是"看懂经营、找到原因"——GMV 为何跌、哪类商品 / 地区在拖后腿、大促效果如何、和同行比是不是只有自己差。回答这些问题需要：跨多张业务表取数 + 与外部行业基准 / 节假日 / 汇率对比 + 把数字翻译成人话结论。传统 BI 门槛高、SQL 写不动；纯大模型又容易瞎编数字。我们的多智能体系统正好补齐：Agent 做规划与取数、工具拿内外部数据、Critic 杜绝幻觉。

**典型场景（User Stories）**
- **US-1 单店分析**：商家上传订单 CSV，问"上月各区域销售额 Top3" → Planner 拆解 → Data 取数 → Viz 出图 → Writer 出报告。
- **US-2 归因诊断**：商家问"上个月 GMV 为什么环比降了 12%" → 系统拆解品类 / 地区 / 复购 → 调 `web_search` 拉行业大盘做对比 → 给出"行业整体下行还是个例"的结论。
- **US-3 并发复用**：上千商家问相似经营问题 → Milvus 语义缓存命中历史分析片段，省一次昂贵管线。
- **US-4 热点公共数据**：大促期间"行业基准 / 节假日维度表"被万级 QPS 并发查 → Caffeine+Redis 多级缓存 + 逻辑过期防击穿（黑马点评缓存击穿的本项目真身）。
- **US-5 质量保障**：平台运营用评测集跑 eval，量化助手靠不靠谱再决定是否对商家开放。

### 1.2 目标（成功标准）
- **面试项目，但要求真实完整**：能本地 Docker 一键起、能部署上线、能跑量化评估，不是玩具 demo。
- **两条主线都要硬**：① 后端工程化（缓存/分布式锁/消息队列/限流/异步/压测/部署）；② Agent 知识（规划/工具调用/记忆/反思/多 Agent 协作）。两条线都有可演示、可量化的产出。
- **有评估闭环**：用硬指标证明系统靠谱（任务成功率、取数正确性、报告保真度、成本/时延、并发吞吐）。

### 1.3 非目标 / YAGNI（明确不做，防膨胀）
- 不做完整多租户 SaaS 基础设施（租户管理后台 / 计费中心），先做简单多用户 + 按用户数据隔离 + 限流。
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

**后端能力 ↔ 「商家经营分析」场景 的驱动关系（杜绝"为用而用"）**

> 设计原则：每个后端组件都必须由本场景的某个真实痛点驱动，而不是把黑马点评的清单贴上来凑数。下面按「痛点 → 后端解法」落地，前后端是同一套设计的两面。

| 本系统的真实痛点（来自「商家经营分析」场景） | 后端解法（企业级组件） |
|---|---|
| 商家问一个经营问题要几十秒~几分钟（跨多表取数 + 对比外部基准 + 出图），不能阻塞 HTTP | **Kafka** 异步解耦：提交即返回 `taskId`，Worker 消费执行；**WebSocket** 推进度 |
| 大促/月初大量商家同时来问，DeepSeek 有 RPM/TPM 配额 | **Redis+Lua 滑动窗口 / Sentinel 限流**（按商家配额）+ 按商家计费（Redis 计数器） |
| 上千商家问相似经营问题（"上月 Top 品类"），重跑太浪费 | **Milvus 语义缓存** + **Redis 精确缓存**：相似问题复用历史分析片段 |
| 大促期间「行业基准 / 节假日维度表」被全平台商家万级 QPS 并发查，缓存失效瞬间打爆 | **Caffeine+Redis 多级缓存** + 逻辑过期 + 随机 TTL（即"缓存击穿/雪崩"的本项目真身）；布隆过滤器防穿透 |
| 同一商家同一问题被并发触发 / 同一份上传被重复解析 | **Redisson 分布式锁** 保证解析与执行幂等 |
| 取数（Python/pandas、SQL）是重负载且危险，多 Agent 并发跑 | **线程池 + 信号量 + 容器沙箱** 做资源治理；失败按 DAG 回滚（saga） |
| 多商家多任务并发，需数据隔离与可追溯 | **JWT** 鉴权 + `traceId` 全链路透传 + Prometheus/Grafana 看板 |
| 上线前证明"撑得住、限流生效" | **JMeter** 压测多商家并发与限流生效 |

> 黑马点评教程里的 分布式锁 / 消息队列 / 多级缓存 / 缓存三大问题 / 限流 / 压测，在这里分别对应：商家数据幂等 / 异步执行 / 热点行业基准并发 / 缓存击穿 / 模型配额 / 容量验证——全是本场景自己的问题，不是借来的装饰。

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
以 **US-2「某母婴店商家问：上个月 GMV 为什么环比降了 12%？是只有我家还是行业都在跌？」** 为例，后端与 Agent 是同一条链路的两端：

1. **API 层**：JWT 校验商家 → Redis 滑动窗口**限流**（按商家配额，超了直接 429）→ 写任务表(PENDING) → 发 Kafka(QUEUED) → 返回 `taskId`（不阻塞）。
2. **Worker** 消费：Redisson 锁保证该商家该问题**幂等**；解析订单 CSV → DuckDB 内存表 → 写**数据画像**到 Redis（逻辑过期）。
3. **Orchestrator** 调度 Planner 拆 DAG——① 本店 GMV 月度趋势 ② 同比 ③ 按品类/地区拆解 ④ 拉外部行业大盘对比。
4. **Data Agent**：先查 **Milvus/Redis 语义缓存**（相似历史分析复用）→ 未命中则调 `sql_executor` 取数；对"行业大盘"步骤调 **`web_search`**（实时行业均值/节假日效应），结果进 Redis 缓存 + 受同一套限流。
5. **Viz / Writer Agent**：出 GMV 趋势图 + 拆解图；Writer 结论必须引用具体数字，且明确"对比行业基准后判断为个例 / 行业性"。
6. **Critic Agent**：四关自检（含"是否真调了 `web_search` 对比外部基准才下结论"）；不通过带 issues 回到对应 Agent **反思重试**（≤3 轮）。
7. 全程：每步状态变更发 Kafka + 更新 Redis + **WS 推商家**；`traceId` 贯穿，Grafana 可观测。
8. 完成：报告落 MySQL；商家点踩 → 写反馈表，进**评估飞轮**。

> 这条链路里每一项技术都对应一个具体的"商家经营分析"痛点：Redis 限流护的是商家并发下的模型额度；Milvus 缓存省的是上千商家相似问题；`web_search` 拉的是行业大盘（反过来又驱动限流+缓存）；Redisson 锁护的是商家数据幂等——不是为用而用。

---

## 4. 多智能体编排设计

核心是一条**可观测、可回滚**的编排管线，不把活全塞给一个大模型：

- **Planner（规划）**：把用户问题拆成带依赖的**子任务 DAG**（如「先算各区域销售额 → 再算同比 → 最后挑 Top3 出图」）。
- **Data Agent（取数 + 外部对比）**：调度 `sql_executor` / `python_executor` 真实执行，必要时调 `web_search` 拉行业基准 / 汇率 / 节假日等外部数据；带**结果校验**（空值、量级异常、列一致性）。
- **Viz Agent（可视化）**：根据数据特征选图表类型，产出 ECharts 配置（柱状/折线/热力/漏斗）。
- **Writer Agent（成文）**：生成结构化中文报告，结论**必须引用具体数据**，杜绝空话。
- **Critic Agent（自检）**：四道关卡——① 数据是否支撑结论 ② 图表与文字是否匹配 ③ 涉及"行业/外部对比"的结论是否真调了 `web_search` 而非编造 ④ 有无幻觉。不通过触发**反思重试**（≤3 轮）。
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
