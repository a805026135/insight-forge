package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.memory.Artifact;
import com.insightforge.agent.core.monitor.TraceRecorder;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.agent.core.runtime.AgentMetrics;
import com.insightforge.agent.core.runtime.CriticVerdict;
import com.insightforge.agent.core.runtime.FailureKind;
import com.insightforge.agent.core.runtime.Span;
import com.insightforge.agent.core.runtime.SpanStatus;
import com.insightforge.agent.core.runtime.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 多智能体离线规划编排器（Phase 8 的灵魂，对应 spec «多智能体离线规划»）。
 *
 * 它把 Phase 7 的单 Agent 发动机升级成「一支团队」：
 *   Planner 拆 DAG → 选品/定价/预热/风控 四个角色 Agent 并行产出 →
 *   Guardrail Policy Agent 汇编成 DSL → DslCompiler 编译（Fail-Close）→
 *   IndependentCritic 三道校验 → 通过则版本化发布（仓库+缓存），不通过则反思重试 ≤ maxRetry。
 *
 * 全程受三态状态机守护：SUCCESS（发布成功）/ CORRECT_FAILURE（正确终止，如 REFUSE 不合规 DSL）/
 * FAILED（崩溃）。无论成败 Trace 落库——可观测、可评估、可追责。
 *
 * 纪律重申：本编排器跑在「控制面」（离线），产出的是 DSL；它绝不改库存/订单——那属于
 * Phase 9 护栏规则引擎（在线确定性热路径）。LLM 永不进秒杀热路径。
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final RoleAgent roleAgent;
    private final GuardrailPolicyAgent policyAgent;
    private final DslCompiler dslCompiler;
    private final IndependentCritic critic;
    private final GuardrailPolicyRepository repository;
    private final TraceRecorder traceRecorder;
    private final AgentMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int maxRetry;
    private final boolean forceInvalidPolicy;

    private final String modelVersion;
    private final String promptVersion;

    public MultiAgentOrchestrator(RoleAgent roleAgent,
                                  GuardrailPolicyAgent policyAgent,
                                  DslCompiler dslCompiler,
                                  IndependentCritic critic,
                                  GuardrailPolicyRepository repository,
                                  TraceRecorder traceRecorder,
                                  AgentMetrics metrics,
                                  @Value("${insightforge.agent.planning.max-retry:3}") int maxRetry,
                                  @Value("${insightforge.agent.planning.force-invalid-policy:false}") boolean forceInvalidPolicy,
                                  @Value("${insightforge.agent.model-version:local-det-1.0}") String modelVersion,
                                  @Value("${insightforge.agent.prompt-version:insightforge-prompt-1.0}") String promptVersion) {
        this.roleAgent = roleAgent;
        this.policyAgent = policyAgent;
        this.dslCompiler = dslCompiler;
        this.critic = critic;
        this.repository = repository;
        this.traceRecorder = traceRecorder;
        this.metrics = metrics;
        this.maxRetry = maxRetry;
        this.forceInvalidPolicy = forceInvalidPolicy;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
    }

    /** 执行一次离线规划，返回结果（已落库 Trace）。 */
    public PlanningResult plan(AgentTask task) {
        String traceId = uuid();
        LocalDateTime startedAt = LocalDateTime.now();
        Trace.Builder tb = Trace.builder(traceId, task.activityId(), startedAt, modelVersion, promptVersion);
        PlanningContext ctx = new PlanningContext(task.activityId(), BigDecimal.valueOf(5999.00), task.goal(), forceInvalidPolicy);

        try {
            // —— Planner：把目标拆成 DAG（这里四个角色并行，无依赖）——
            List<SubTask> plan = List.of(
                    new SubTask(Role.SELECTOR, "选品", task.activityId()),
                    new SubTask(Role.PRICER, "定价", task.activityId()),
                    new SubTask(Role.WARMUP, "库存预热", task.activityId()),
                    new SubTask(Role.RISK, "风控", task.activityId()));
            tb.addSpan(span(traceId, Role.PLANNER, "buildDag", objectMapper.writeValueAsString(plan), SpanStatus.OK));

            // —— 角色 Agent 并行产出（Phase 14.2：真 LLM 下 4 角色并发调模型，串行要 60s+）——
            Map<Role, Artifact> artifacts = runRoleAgentsParallel(plan, ctx, traceId, tb);

            // —— Guardrail Policy Agent 汇编 DSL ——
            GuardrailPolicy policy = policyAgent.assemble(ctx, artifacts);
            tb.addSpan(span(traceId, Role.GUARDRAIL, "assemble", objectMapper.writeValueAsString(policy), SpanStatus.OK));

            // —— 编译 + 独立验证器 + 反思重试（≤ maxRetry）——
            for (int attempt = 1; attempt <= maxRetry + 1; attempt++) {
                DslCompileResult compile = dslCompiler.compile(policy);
                CriticVerdict verdict = critic.verify(policy, compile);

                if (verdict.passed()) {
                    // 三道全过 → 版本化发布（Fail-Close：仅合规 DSL 走到这）
                    GuardrailPolicy published = repository.publish(policy);
                    tb.addSpan(Span.critic(traceId, attempt, verdict, tb.retryCount(), SpanStatus.OK,
                            "policy-" + published.version()));
                    return finish(startedAt, tb, AgentRunStatus.SUCCESS, null, false, published);
                }

                // 不通过 → 记一次 Critic 拦截（L2），反思重试
                metrics.criticInterceptions().incrementAndGet();
                tb.addSpan(Span.critic(traceId, attempt, verdict, tb.retryCount(), SpanStatus.RETRY, null));
                if (tb.retryCount() < maxRetry) {
                    tb.incRetry();
                    metrics.retries().incrementAndGet();
                    continue;   // 回到循环：真实场景会把 verdict.reasons() 回灌 Planner 修订；本地确定性故重算同结果
                }
                // 重试耗尽：正确失败（拒绝发布不合规 DSL，比假装成功可靠）
                tb.addSpan(Span.critic(traceId, attempt, verdict, tb.retryCount(), SpanStatus.FAIL, null));
                return finish(startedAt, tb, AgentRunStatus.CORRECT_FAILURE, FailureKind.REFUSE, true, null);
            }
        } catch (Exception e) {
            log.error("规划运行异常 traceId={}", traceId, e);
            tb.addSpan(Span.critic(traceId, maxRetry + 1,
                    CriticVerdict.fail(List.of("编排器异常: " + e.getMessage()), 0.5, "HIGH"),
                    tb.retryCount(), SpanStatus.FAIL, null));
            return finish(startedAt, tb, AgentRunStatus.FAILED, null, false, null);
        }
        // 理论上不会到这（循环内已 return），兜底 FAILED
        return finish(startedAt, tb, AgentRunStatus.FAILED, null, false, null);
    }

    /** 4 个角色并发执行（每个子任务独立调模型），逐个收结果并记录 span。 */
    private Map<Role, Artifact> runRoleAgentsParallel(List<SubTask> plan, PlanningContext ctx,
                                                      String traceId, Trace.Builder tb) {
        Map<Role, CompletableFuture<Artifact>> futures = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(plan.size(), 4));
        try {
            for (SubTask st : plan) {
                futures.put(st.role(), CompletableFuture.supplyAsync(() -> roleAgent.run(st, ctx), pool));
            }
            Map<Role, Artifact> artifacts = new LinkedHashMap<>();
            for (Map.Entry<Role, CompletableFuture<Artifact>> e : futures.entrySet()) {
                try {
                    Artifact a = e.getValue().get(90, TimeUnit.SECONDS);
                    if (a == null) {
                        metrics.invalidSteps().incrementAndGet();   // L2：无效步骤率
                        tb.addSpan(span(traceId, e.getKey(), "produce", "null", SpanStatus.FAIL));
                    } else {
                        artifacts.put(e.getKey(), a);
                        // 只序列化产物内容（Map），避免 Artifact.createdAt(LocalDateTime) 需 JSR310 模块
                        tb.addSpan(span(traceId, e.getKey(), "produce",
                                objectMapper.writeValueAsString(a.content()), SpanStatus.OK));
                    }
                } catch (Exception ex) {
                    metrics.invalidSteps().incrementAndGet();
                    tb.addSpan(span(traceId, e.getKey(), "produce",
                            "exception:" + ex.getMessage(), SpanStatus.FAIL));
                }
            }
            return artifacts;
        } finally {
            pool.shutdownNow();
        }
    }

    private PlanningResult finish(LocalDateTime startedAt, Trace.Builder tb,
                                  AgentRunStatus status, FailureKind kind, boolean correctFailure,
                                  GuardrailPolicy published) {
        LocalDateTime now = LocalDateTime.now();
        tb.status(status).failureKind(kind).correctFailure(correctFailure).finishedAt(now)
                .totalLatencyMs(java.time.Duration.between(startedAt, now).toMillis())
                .budget(0, BigDecimal.ZERO);
        metrics.terminal(status, kind).incrementAndGet();
        Trace trace = tb.build();
        traceRecorder.persistTrace(trace);
        return new PlanningResult(trace, published, status);
    }

    private Span span(String traceId, Role role, String stepName, String output, SpanStatus status) {
        return new Span(uuid(), traceId, null, role.name(), stepName, null,
                null, truncate(output), 0, 0, 0, 0, status, null, null);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
