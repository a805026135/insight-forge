# 选做2 · Agent 运行时深入

> 进阶选做章节。跳过不影响主线理解。前置：读过 Phase 7。

## 📖 是什么

Phase 7 的 **Agent 运行时基建**——让 Agent「跑得安全、跑得可观测」的企业级实践：

- **ReAct 循环**（`AgentRuntime`，243 行，最重单文件）：Plan-Execute 同构的工具调用循环。
- **Critic 反思重试**（`Critic` / `CriticVerdict`，`maxRetry=3`）：确定性校验（售价≥成本 / 再分配≥0 / 必填字段），不通过回反思重试。
- **预算/Token 上限**（`BudgetGate` / `BudgetExceededException`）：超预算 → `ESCALATE`，防 Agent 失控烧钱。
- **三层记忆**（`memory/`）：短期 `ShortTermMemory` / 长期 `LocalMemoryStore`（关键词召回）/ 跨 Agent `ArtifactStore`；`MilvusMemoryStore` 为向量库桩。
- **trace/span 持久化 + 指标**（`Trace` / `Span` / `AgentMetrics` / `TraceRecorder`）：`agent_trace`/`agent_span` 表 + 三态状态机（SUCCESS/CORRECT_FAILURE/FAILED）+ 正确失败率。

## 🧠 为什么是选做

主线只需知道一句"Agent 有**预算闸门**和 **Critic 拦截**、离线产出 DSL"。全量 trace/span 观测、三层记忆、Critic verdict 框架是「Agent 工程化」的加分深度，对讲清卖点非必需。如果你面试方向不是「Agent 平台/可观测」，读完主线那段就够了。

## 关键文件（`insightforge-agent-core/`）

```
runtime/AgentRuntime.java        243  ReAct 主循环（核心）
runtime/BudgetGate.java          78   预算/Token 上限
runtime/Critic.java              68   反思校验
runtime/Trace.java / Span.java        链路追踪
runtime/AgentMetrics.java        119  指标（含正确失败率）
memory/MemoryStore.java / LocalMemoryStore.java / MilvusMemoryStore.java(桩)
monitor/TraceRecorder.java       70   落库
```

## 核心入口

```java
AgentRunStatus status = agentRuntime.run(task);   // 内部 ReAct 循环 + Critic + BudgetGate
```

## 如何跑

- `AgentRuntimeTest` / `AgentRuntimeBudgetTest` / `AgentRuntimeRejectTest` 覆盖：正常产出 / 预算超限→ESCALATE / Critic 拒绝→REFUSE。
- 默认 `LocalDeterministicModelClient` 桩。

## ⚠️ 诚实边界

- **`MilvusMemoryStore` 是显式桩**：启用即 `throw UnsupportedOperationException`（需部署 Milvus + EmbeddingClient）。默认 `LocalMemoryStore` 关键词召回。
- 沙箱未跑真 DeepSeek（`DeepSeekModelClient` 代码就绪、条件启用）。
- trace/span 落 `agent_trace`/`agent_span` 表（MyBatis-Plus），需 MySQL。

## 与主线的关系

主线 Phase 7 只讲"最小 Planner + DslCompiler"。本选做把运行时展开：记忆、反思、预算、可观测。**`GuardrailPolicy` 产出与 `DslCompiler` 校验不变**——运行时是"怎么跑 Agent"的深度，不改变产出契约。
