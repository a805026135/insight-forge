# InsightForge Agent 监测与评估设计方案（v1.0）

> **依据**：参考文章《生产级 Agent 全景面试详解》"评估与可观测"四层框架 + 正确失败率（Correct Failure Rate）+ 独立验证器 + Trace 回归闭环 + 硬编码护栏；结合本项目**「控制面 / 数据面分离、LLM 不进热路径」**的硬约束落地。
> **定位**：本方案**不是**给秒杀热路径加监控（那部分已由 Phase 10 的 Grafana 覆盖：扣减耗时 / 锁等待 / 超卖告警），而是给 **Agent 控制面**（离线规划 + 在线规则引擎）建立监测与评估体系，并把它**内建**进 Phase 7–11，而不是事后补。
> **一句话**：Agent 的"聪明"必须可观测、可评估、可追责；失败的"对"，比假装成功更有价值。

---

## 0. 设计原则（从参考框架提炼，落地为本项目约束）

1. **Agent = 控制面，LLM 只在离线规划期推理**；在线热路径 100% 确定性。因此 Agent 监测聚焦「离线规划运行质量 + 在线规则引擎业务结果」，不监控毫秒级热路径（秒杀 P99 由系统指标负责）。
2. **Trace 不为漂亮大屏**。Trace 只服务三件事：① 快速定位问题在第几层；② 固定环境重放失败任务；③ 把失败样本加入回归评估集。
3. **评估四层**：组件 / 轨迹 / 结果 / 系统（见 §3）。
4. **正确失败率（Correct Failure Rate）是核心质量红线**：信息不足 → 问（ASK）；权限不够 → 拒（REFUSE）；工具故障 → 停并转人工（ESCALATE）。失败的"对"，比编造成功更可靠。
5. **独立验证器**：执行后由独立 Critic 验证，**不让原 Agent 自评"我做对了吗"**。可验证结果优先确定性程序校验（跑测试 / 查库 / 比对产物 / 检查状态机），内容任务才引入规则 / 模型评审 / 人工抽检。
6. **硬编码护栏**：参数 → Schema；重复 → 幂等键（DSL 版本号）；高风险 → 审批流；预算 → Harness 上限。**外层确定性 Workflow（规则引擎）+ 内层有限自治 Agent**。
7. **状态外化五类区分**（参考框架强调）：`Session`（会话发生了什么）/ `TaskState`（执行到哪）/ `Memory`（跨任务复用经验）/ `Artifact`（生成的计划 / DSL / 报告）/ **`Trace`（系统实际执行了什么）**。本项目已有 `Memory`（Milvus 长期记忆）、`Artifact`（DSL / 报告）；本方案**新增独立 Trace 存储**，并明确 `TaskState`（run 状态机）与 `Trace`（实际执行记录）的区别——前者是"该到哪了"，后者是"实际做了什么"，二者不可混。

---

## 1. 运行状态机与失败分类（Phase 7 落地）

Agent 一次规划运行（Orchestrator 发起的 trace）结束于三态之一，写入 `agent_trace.status`：

| 终态 | 含义 | 是否计入"正确失败率" | 典型触发 |
|---|---|---|---|
| `SUCCESS` | 产出有效 DSL + 报告，Critic 通过 | 否 | 正常完成 |
| `CORRECT_FAILURE` | **主动、正确地终止** | **是（这是"好的失败"）** | ASK（数据不足请求补充）/ REFUSE（权限或范围不足）/ ESCALATE（转人工） |
| `FAILED` | 崩溃 / 超时 / 预算耗尽且未优雅降级 | 否（这是"坏的失败"，必须修） | 未捕获异常、死循环、成本击穿 |

**停止条件（Harness 控制预算）**：连续失败 N 次、Token / 成本超上限、置信度 < 阈值、触发高风险动作 → 暂停交人（ESCALATE）。预算与停止条件在 Phase 7 的 Agent Core 里硬编码，是护栏的第一道闸门。

---

## 2. Trace 数据模型（Phase 7 内建，MySQL 持久化）

源真相存 **MySQL**（离线可跑、无外部依赖）；Phase 10 提供 Trace 查看器 UI；可选 OpenTelemetry / Langfuse 导出作为扩展（不阻塞主流程）。

```sql
-- 一次规划运行（= 一个 trace）
agent_trace (
  trace_id         VARCHAR(64)  PRIMARY KEY,  -- = Orchestrator run id；也用于在线规则引擎运行的归因（trace_id 透传）
  activity_id      BIGINT,
  started_at       DATETIME(3),
  finished_at      DATETIME(3),
  status           ENUM('SUCCESS','CORRECT_FAILURE','FAILED'),
  failure_kind     VARCHAR(32),               -- ASK / REFUSE / ESCALATE / null
  model_version    VARCHAR(64),
  prompt_version   VARCHAR(64),
  budget_tokens    INT,
  budget_cost_usd  DECIMAL(10,4),
  total_latency_ms BIGINT,
  correct_failure  BOOLEAN                    -- 冗余字段，便于聚合
);

-- 每个 Agent 步骤（span）
agent_span (
  span_id        VARCHAR(64) PRIMARY KEY,
  trace_id       VARCHAR(64) NOT NULL,        -- FK -> agent_trace
  parent_span_id VARCHAR(64),
  role           VARCHAR(32),                 -- Planner / Data / Viz / Writer / Critic / Orchestrator
  step_name      VARCHAR(64),
  tool_name      VARCHAR(64),
  tool_input     JSON,                        -- 入参（含 JSON Schema 校验结果）
  tool_output    JSON,                        -- 出参（截断存储，避免撑爆）
  token_in       INT,
  token_out      INT,
  latency_ms     BIGINT,
  retry_count    INT,                         -- 反思重试次数（≤3）
  status         VARCHAR(16),                 -- OK / RETRY / FAIL
  confidence     DECIMAL(3,2),                -- 该步骤置信度
  artifact_ref   VARCHAR(128)                 -- 关联 DSL id / report id
);
```

> **为什么是 MySQL 而不是 Langfuse**：教学项目要零外部依赖可跑；MySQL 已在本项目里，Trace 落库即"状态外化"，Phase 10 再做一个只读查看器即可。OTel/Langfuse 作为"想上生产再接"的扩展注记。

---

## 3. 四层评估指标（落到可采集项）

参考框架的四层，逐层落到本项目的具体指标与采集点：

### L1 组件评估（单个模型 / 工具 / 记忆是否工作正常）
| 指标 | 定义 | 采集点 | 健康基线 |
|---|---|---|---|
| 工具选择准确率 | 正确工具调用 / 总工具调用 | Phase 8 | ≥ 95% |
| 参数 Schema 合规率 | JSON Schema 校验通过 / 总参数 | Phase 7 | 100% |
| 记忆检索召回 | Milvus top-k 相关度 / 命中率 | Phase 7 | ≥ 80% |
| 模型调用成功率 | 非超时 / 非 5xx / 总调用 | Phase 7 | ≥ 99% |

### L2 轨迹评估（中间步骤是否合理、安全、高效）
| 指标 | 定义 | 采集点 | 健康基线 |
|---|---|---|---|
| Critic 拦截率 | 被拦截不合规输出 / 总输出 | Phase 8 | 监控趋势（非越低越好） |
| 重复工具调用 | 同工具同参 ≥ 2 次数 | Phase 8 | 趋近 0 |
| 无效步骤率 | 产出未被下游消费 / 总步骤 | Phase 8 | < 5% |
| 越权尝试 | 未授权工具 / 资源调用 | Phase 8 | 0 |
| 恢复成功率 | 工具失败后 retry / 降级成功 / 总失败 | Phase 8 | ≥ 90% |
| 反思重试分布 | maxRetry=3 利用率 | Phase 7 | 监控 |

### L3 结果评估（外部环境中的任务是否真实完成）
| 指标 | 定义 | 采集点 | 健康基线 |
|---|---|---|---|
| DSL 编译通过率 | Fail-Close 通过 / 提交发布 | Phase 9 | **100%** |
| 影子模式一致率 | 新 DSL vs 当前规则决策一致 / 总 tick | Phase 9 | 监控 |
| 回测超卖数 | 历史负载重放超卖条数 | Phase 9 | **0** |
| 回测 SLA 达标 | 热路径 P99 < 10ms 保持 | Phase 9 | **100%** |
| 业务态正确性 | 库存 / 价格终态 vs 预期 | Phase 9 | **100%** |

### L4 系统评估（整体是否值得长期运行）
| 指标 | 定义 | 采集点 | 健康基线 |
|---|---|---|---|
| Agent 运行成功率 | 有效 DSL 产出 / 总运行 | Phase 10 | ≥ 90% |
| **正确失败率** | CORRECT_FAILURE / 总结束运行 | Phase 10 | **可观测、有数值**（核心红线） |
| 离线规划延迟 P95 | 受预算约束 | Phase 7 | < 30s |
| 单 run 成本 | Token / 成本上限 | Phase 7 | 预算内 |
| 人工接管率 | HITL 触发 / 总运行 | Phase 10 | 监控 |
| 护栏命中率 | 规则触发次数 | Phase 9 | 0 违规 / 0 超卖（健康底线） |
| 热路径无损证明 | 秒杀 P99 不因 Agent 退化 | Phase 10 | < 10ms |

---

## 4. 独立验证器 / Critic 设计（Phase 7 / 8）

Critic 角色 = **独立验证器**，绝不是让原 Agent 自评。三道校验**顺序执行**，能程序校验的绝不先问模型：

1. **确定性程序校验（优先）**：DSL Schema 编译、数值约束（售价 ≥ 成本、库存 ≥ 0、规则无死循环 / 无负向雪崩）、SQL / 状态机可重放。
2. **规则校验**：硬性业务规则（如"调价不得低于成本价""再分配不得跨活动透支"）。
3. **模型评审 + 人工抽检（仅内容任务）**：报告文案通顺度、图文匹配度等无法用程序判定的，才引入模型评审与人工抽检。

Critic 输出**结构化 verdict**：
```json
{ "pass": false, "reasons": ["售价 9.9 < 成本 12.0"], "confidence": 0.97, "severity": "HIGH" }
```
失败回 Planner / Critic **≤ 3 次**（maxRetry）；超限 → `CORRECT_FAILURE`（标记的正确失败，记入正确失败率），**绝不假装成功**。

---

## 5. 在线规则引擎的结果评估（Phase 9）

规则引擎是 Agent 策略的"执行面"，其评估是 L3 结果评估的硬门槛：

- **DSL Fail-Close 门禁**：发布前 100% 编译通过，否则**阻断上线**（L3 硬门槛，不可绕过）。
- **影子模式（Shadow）**：新 DSL 对实时 `sales.tick` 副本做评估，决策与当前规则对比，**不执行**任何写动作，用于上线前安全验证。
- **回测骨架（Backtest）**：用历史负载（JMeter 录制 or 真实日志回放）跑新 DSL，断言 **oversell = 0、SLA 达标、库存周转改善**。
- **护栏命中埋点**：每次规则触发记 metric；**0 超卖 / 0 违规是健康底线**（命中≠坏，但越界必须为 0）。
- **风险分级**：查询类（只读，低）/ 写入类（调价调拨，中）/ 跨活动预算再分配（高，须人工审批）。

---

## 6. 离线评估 Harness（Phase 8 起建，Phase 11 闭环）

- **最小评估集（MVP）**：收集 ~20 条真实规划任务（不同品类 / 目标 / 约束），每条含：输入目标 + 上下文快照 + 期望 DSL 骨架 / 验收准则 + 人工基线结果。看清失败分布。
- **回归评估集（工程复利）**：每次 `CORRECT_FAILURE` 或结果不达标 → 沉淀一条样本，记录 `model_version / prompt_version / context / tool_params / tool_returns / state_change`，在**隔离环境重放**。
- **修复闭环**：每次修复进入 **规则 / 工具 / 测试 / 评估集 / 运行策略** 之一（参考框架的"工程复利"）。
- **边界追问**：是否缺某类边界样本（冷启动无历史、极端 GMV、互相冲突的目标）—— 主动补评估集，而非等线上爆。

---

## 7. 在线评估与灰度（Phase 11 + Phase 10）

- **灰度上线 + 逐级放开权限**：`shadow` → 单活动 → 多活动，权限随阶段放大。
- **A/B 实验**：一部分商家 AI 控盘、一部分人工规则，对比 **GMV / 售罄率 / 毛利 / 库存周转**。没有对照就不知道 AI 到底有没有用。
- **系统指标实时看板**：成功率、正确失败率、成本、延迟、人工接管率（L4）实时呈现。

---

## 8. 人工接管 HITL（Phase 10）

参考框架的 HITL 三要素，落到本项目的界面与流程：

1. **何时找人**：DSL Fail-Close 编译失败、置信度 < 阈值、高风险动作（跨活动预算再分配）、连续 Planner 失败。
2. **人看到哪些证据**：完整 Trace（span 树）+ Critic verdict + 与人工基线 diff + 本次预算 / 成本。
3. **人处理后从哪继续**：操作员 `approve` / `edit` DSL → 重新版本化部署（`guardrail_policy` 新版本 + `guardrail:dsl:{aid}` 热更新）→ Agent 回到监控态。

---

## 9. Phase 映射（内建而非补）

| Phase | 新增的监测 / 评估内建项 |
|---|---|
| **7 Agent Core** | Trace 持久化（`agent_trace` / `agent_span`）+ `trace_id` 生成；预算 / Token 上限 + 停止条件；反思重试写入 span；三态状态机（SUCCESS / CORRECT_FAILURE / FAILED）；L1 组件指标埋点；Critic 结构化 verdict 框架 |
| **8 多智能体离线规划** | Critic 升级为独立验证器（确定性程序 → 规则 → 模型 / 人工三道校验）；正确失败行为（ASK / REFUSE / ESCALATE）；L2 轨迹指标采集；最小 golden 评估集（seed ~10） |
| **9 护栏规则引擎** | DSL Fail-Close 门禁（L3 硬门槛，发布前 100% 编译）；影子模式框架；回测骨架（历史重放 oversell=0 / SLA）；护栏命中埋点；风险分级 |
| **10 前端 + 可观测 + 部署** | Trace 查看器 UI；四层指标看板（含正确失败率 / 护栏命中 / 成本延迟）；HITL 人工审批界面；风险分级展示；热路径联动归因 |
| **11 评估闭环** | 离线 eval harness（golden + 失败样本回归重放）；在线灰度 + 影子 + A/B；系统指标报告（含正确失败率）；CI 阈值阻断 |

---

## 10. 与现有热路径监控的边界

- **热路径（秒杀）监控**：扣减耗时 / 锁等待 / 超卖告警 —— Phase 10 Grafana（已在 spec `可观测` 行）。
- **Agent 监控**：本方案的四层指标 + Trace + HITL。
- **关联归因**：通过 `trace_id` ↔ 在线规则引擎 run 联动 —— 例如某次自动调价引发的用户侧延迟，可在大屏上把"规则引擎决策"与"用户侧 RT"并排归因，证明 LLM 不进热路径、控制面影响可控。

---

## 11. 验收（Phase 11 给出量化报告）

1. 量化报告：无超卖 + AI 控盘组售罄率 / 毛利优于人工组。
2. **正确失败率可观测、有数值**（如"本月 120 次运行中 8 次 CORRECT_FAILURE，主因数据不足 ASK"）。
3. 至少 1 条 `CORRECT_FAILURE` / 结果不达标样本**可重放**，且已进入回归评估集。
4. Trace 查看器能展示一次完整规划运行的 **span 树 + Critic verdict**。
5. HITL：注入高风险动作 → 触发审批流 → 操作员 approve / edit → 重新上线，闭环可演示。
6. DSL Fail-Close 门禁：注入错误 DSL → 编译拒绝、不上线（0 裸奔）。

---

## 12. 落地优先级（建议顺序）

1. **Phase 7**：先把 Trace + 状态机 + 预算闸门 + Critic verdict 框架打底（没有 Trace，后面所有评估都是空谈）。
2. **Phase 8**：Critic 三道校验 + 正确失败行为 + golden seed。
3. **Phase 9**：Fail-Close 门禁 + 影子 / 回测 + 护栏埋点。
4. **Phase 10**：把数据变成能看的看板 + HITL 界面。
5. **Phase 11**：eval harness 闭环 + A/B + CI。

> 一句话收尾：本项目"Agent 辅助"的含金量，最终靠**可观测、可评估、可追责**来证明——这套方案就是为此设计的。
