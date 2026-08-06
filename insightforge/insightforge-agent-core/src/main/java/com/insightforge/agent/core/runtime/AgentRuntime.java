package com.insightforge.agent.core.runtime;

import com.insightforge.agent.core.memory.Artifact;
import com.insightforge.agent.core.memory.ArtifactStore;
import com.insightforge.agent.core.memory.MemoryStore;
import com.insightforge.agent.core.memory.ShortTermMemory;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.ModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.agent.core.model.ToolCall;
import com.insightforge.agent.core.model.ToolSpec;
import com.insightforge.agent.core.monitor.TraceRecorder;
import com.insightforge.agent.core.tool.ParameterValidator;
import com.insightforge.agent.core.tool.Tool;
import com.insightforge.agent.core.tool.ToolContext;
import com.insightforge.agent.core.tool.ToolRegistry;
import com.insightforge.agent.core.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 运行时（自研编排核心，设计文档 §5 + Phase 7）。
 *
 * 这就是「Agent 核心自研」的发动机：一个 ReAct 循环——
 *   思考(调模型) → 是否调工具? → 执行工具并把观察回灌 → 再思考 → 给出最终答案 → Critic 校验
 * 全程被三道护栏守护：
 *   1) 预算闸门（BudgetGate）：token/成本/延迟超限优雅停（ESCALATE）。
 *   2) 参数 Schema 校验（ParameterValidator）：脏参数不进业务。
 *   3) Critic 确定性校验 + 反思重试（maxRetry=3）：不合规方案拒绝交付（REFUSE）。
 * 无论成败，Trace（含 span 树）都落库——可观测、可评估、可追责。
 *
 * 关键纪律：本运行时跑在「控制面」（离线规划），LLM 只在思考步出现；
 * 它产出的是方案 JSON / DSL，绝不直接改写秒杀库存——那属于热路径 + 护栏规则引擎（Phase 9）。
 */
@Component
public class AgentRuntime {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final ArtifactStore artifactStore;
    private final TraceRecorder traceRecorder;
    private final Critic critic;
    private final BudgetGate budgetGate;
    private final AgentMetrics metrics;
    private final String modelVersion;
    private final String promptVersion;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRuntime(ModelClient modelClient,
                        ToolRegistry toolRegistry,
                        MemoryStore memoryStore,
                        ArtifactStore artifactStore,
                        TraceRecorder traceRecorder,
                        Critic critic,
                        BudgetGate budgetGate,
                        AgentMetrics metrics,
                        @Value("${insightforge.agent.model-version:local-det-1.0}") String modelVersion,
                        @Value("${insightforge.agent.prompt-version:insightforge-prompt-1.0}") String promptVersion) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.memoryStore = memoryStore;
        this.artifactStore = artifactStore;
        this.traceRecorder = traceRecorder;
        this.critic = critic;
        this.budgetGate = budgetGate;
        this.metrics = metrics;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
    }

    /**
     * 执行一次规划任务，返回完整 Trace（已落库）。
     */
    public Trace run(AgentTask task) {
        String traceId = uuid();
        LocalDateTime startedAt = LocalDateTime.now();
        Trace.Builder tb = Trace.builder(traceId, task.activityId(), startedAt, modelVersion, promptVersion);

        ShortTermMemory stm = new ShortTermMemory();
        budgetGate.reset();
        stm.add(ChatMessage.system(buildSystemPrompt()));
        stm.add(ChatMessage.user("目标: " + task.goal()));

        try {
            for (int step = 1; step <= budgetGate.getMaxSteps(); step++) {

                // —— 护栏 1：预算闸门（每步开始前）——
                try {
                    budgetGate.assertWithinBudget();
                } catch (BudgetExceededException e) {
                    return terminate(startedAt, tb, AgentRunStatus.CORRECT_FAILURE, FailureKind.ESCALATE, true);
                }

                // —— 思考：调模型 ——
                ModelRequest req = new ModelRequest(stm.messages(), modelVersion, toolRegistry.specs());
                long m0 = System.nanoTime();
                ModelResponse resp = modelClient.generate(req);
                long mLat = (System.nanoTime() - m0) / 1_000_000;
                budgetGate.addTokens(resp.tokenIn(), resp.tokenOut());
                metrics.modelCalls().incrementAndGet();
                tb.addSpan(Span.model(traceId, step, resp, null));

                // —— 工具循环：模型决定调工具 ——
                if (resp.toolCall() != null) {
                    tb.addSpan(executeTool(traceId, step, resp.toolCall(), stm, task));
                    continue; // 回到循环，带着观察再决策
                }

                // —— 最终答案：Critic 校验 ——
                CriticVerdict verdict = critic.verifyPlan(resp.content());
                if (verdict.passed()) {
                    // 成功：方案存为 Artifact，供下游（Phase 8 多智能体 / Phase 9 规则引擎）复用
                    artifactStore.put(Artifact.of("plan-" + traceId, "plan", resp.content()));
                    tb.addSpan(Span.critic(traceId, step, verdict, tb.retryCount(), SpanStatus.OK, "plan-" + traceId));
                    return terminate(startedAt, tb, AgentRunStatus.SUCCESS, null, false);
                }

                // —— 反思重试 ——
                int retry = tb.retryCount();
                if (retry < budgetGate.getMaxRetry()) {
                    metrics.criticRejections().incrementAndGet();
                    metrics.retries().incrementAndGet();
                    tb.addSpan(Span.critic(traceId, step, verdict, retry, SpanStatus.RETRY, null));
                    stm.add(ChatMessage.user("Critic 校验未通过，请修订：" + String.join("; ", verdict.reasons())));
                    tb.incRetry();
                    continue;
                }
                // 重试耗尽：正确失败（拒绝交付不合规方案，比假装成功可靠）
                metrics.criticRejections().incrementAndGet();
                tb.addSpan(Span.critic(traceId, step, verdict, retry, SpanStatus.FAIL, null));
                return terminate(startedAt, tb, AgentRunStatus.CORRECT_FAILURE, FailureKind.REFUSE, true);
            }
            // 步数耗尽仍未成功 → 正确失败
            return terminate(startedAt, tb, AgentRunStatus.CORRECT_FAILURE, FailureKind.REFUSE, true);

        } catch (Exception e) {
            // 崩溃：FAILED（坏的失败，必须修）
            tb.addSpan(Span.critic(traceId, budgetGate.getMaxSteps(),
                    CriticVerdict.fail(List.of("运行时异常: " + e.getMessage()), 0.5, "HIGH"),
                    tb.retryCount(), SpanStatus.FAIL, null));
            return terminate(startedAt, tb, AgentRunStatus.FAILED, null, false);
        }
    }

    /** 执行一个工具：先校验 Schema，再执行，观察回灌短期记忆，记录 span。 */
    private Span executeTool(String traceId, int step, ToolCall tc, ShortTermMemory stm, AgentTask task) {
        String toolName = tc.name();
        Map<String, Object> args = tc.args();

        Tool tool = toolRegistry.get(toolName);
        if (tool == null) {
            String err = "未知工具: " + toolName;
            stm.add(ChatMessage.tool("ERROR: " + err));
            return Span.toolFail(traceId, step, toolName, args, err);
        }

        // 护栏 2：参数 Schema 校验（L1 参数合规率 100%）
        List<String> schemaErrors = ParameterValidator.validate(tool.parametersJson(), args);
        if (!schemaErrors.isEmpty()) {
            metrics.schemaViolations().incrementAndGet();
            String err = "参数不合规: " + String.join("; ", schemaErrors);
            stm.add(ChatMessage.tool("ERROR: " + err));
            return Span.toolFail(traceId, step, toolName, args, err);
        }

        // 执行（沙箱隔离：真实接入时此处跑在独立进程/容器，崩了不拖核心服务）
        ToolContext ctx = new ToolContext(traceId, task.activityId(), artifactStore, memoryStore);
        ToolResult result;
        long t0 = System.nanoTime();
        try {
            result = tool.execute(args, ctx);
        } catch (Exception e) {
            result = ToolResult.fail(e.getMessage());
        }
        long lat = (System.nanoTime() - t0) / 1_000_000;
        metrics.toolCalls().incrementAndGet();

        if (result.success()) {
            String outJson = toJson(result.data());
            stm.add(ChatMessage.tool(outJson));
            return Span.toolOk(traceId, step, toolName, args, outJson, lat);
        } else {
            stm.add(ChatMessage.tool("ERROR: " + result.error()));
            return Span.toolFail(traceId, step, toolName, args, result.error());
        }
    }

    /** 统一收口：填终态/耗时/预算、累加终态计数、落库、返回。 */
    private Trace terminate(LocalDateTime startedAt, Trace.Builder tb,
                            AgentRunStatus status, FailureKind kind, boolean correctFailure) {
        LocalDateTime now = LocalDateTime.now();
        tb.status(status)
                .failureKind(kind)
                .correctFailure(correctFailure)
                .finishedAt(now)
                .totalLatencyMs(Duration.between(startedAt, now).toMillis())
                .budget(budgetGate.getTokens(), BigDecimal.valueOf(budgetGate.costUsd()));
        metrics.terminal(status, kind).incrementAndGet();
        Trace trace = tb.build();
        traceRecorder.persistTrace(trace);
        return trace;
    }

    private String buildSystemPrompt() {
        String tools = toolRegistry.specs().stream().map(ToolSpec::name).collect(Collectors.joining(", "));
        return """
                你是 InsightForge 的运营规划 Agent（控制面，LLM 不进秒杀热路径）。
                可用工具: %s
                护栏（硬编码，违反即被 Critic 拒绝）:
                  1) 方案售价 repriceTo 必须 ≥ 成本 costFloor；
                  2) 再分配数量 amount 必须 ≥ 0（库存不可透支）；
                  3) 必备字段 activityId / pricing / reallocation。
                如需调用工具，仅输出 <tool_call>{"name":"...","args":{...}}</tool_call>；否则直接输出方案 JSON。
                """.formatted(tools);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
