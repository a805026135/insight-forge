package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.memory.Artifact;
import com.insightforge.agent.core.monitor.TraceRecorder;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.agent.core.runtime.AgentMetrics;
import com.insightforge.agent.core.runtime.CheckpointService;
import com.insightforge.agent.core.runtime.CriticVerdict;
import com.insightforge.agent.core.runtime.FailureKind;
import com.insightforge.agent.core.runtime.InterruptGate;
import com.insightforge.agent.core.runtime.InterruptSignal;
import com.insightforge.agent.core.runtime.Span;
import com.insightforge.agent.core.runtime.SpanStatus;
import com.insightforge.agent.core.runtime.TerminationReason;
import com.insightforge.agent.core.runtime.Trace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.AgentCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 多智能体离线规划编排器（Phase 8 + Phase 19d checkpoint）。
 *
 * 运行按阶段推进并在每个阶段边界保存 checkpoint：
 *   DAG → ROLES → ASSEMBLE → ATTEMPT:n → PUBLISH
 * 阶段边界检查 {@link InterruptGate}：被暂停则优雅停止（状态 PAUSED，checkpoint 已保存），
 * 之后可通过 {@link #resume(String)} 从断点继续，不必重跑已完成阶段。
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
    private final CheckpointService checkpointService;
    private final InterruptGate interruptGate;
    private final TaskComplexityRouter complexityRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int maxRetry;
    private final boolean forceInvalidPolicy;

    private final String modelVersion;
    private final String promptVersion;
    private final BigDecimal tokenPriceUsdPer1k;

    public MultiAgentOrchestrator(RoleAgent roleAgent,
                                  GuardrailPolicyAgent policyAgent,
                                  DslCompiler dslCompiler,
                                  IndependentCritic critic,
                                  GuardrailPolicyRepository repository,
                                  TraceRecorder traceRecorder,
                                  AgentMetrics metrics,
                                  CheckpointService checkpointService,
                                  InterruptGate interruptGate,
                                  TaskComplexityRouter complexityRouter,
                                  @Value("${insightforge.agent.planning.max-retry:3}") int maxRetry,
                                  @Value("${insightforge.agent.planning.force-invalid-policy:false}") boolean forceInvalidPolicy,
                                  @Value("${insightforge.agent.model-version:local-det-1.0}") String modelVersion,
                                  @Value("${insightforge.agent.prompt-version:insightforge-prompt-1.0}") String promptVersion,
                                  @Value("${insightforge.agent.budget.token-price-usd-per-1k:0.0005}") BigDecimal tokenPriceUsdPer1k) {
        this.roleAgent = roleAgent;
        this.policyAgent = policyAgent;
        this.dslCompiler = dslCompiler;
        this.critic = critic;
        this.repository = repository;
        this.traceRecorder = traceRecorder;
        this.metrics = metrics;
        this.checkpointService = checkpointService;
        this.interruptGate = interruptGate;
        this.complexityRouter = complexityRouter;
        this.maxRetry = maxRetry;
        this.forceInvalidPolicy = forceInvalidPolicy;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.tokenPriceUsdPer1k = tokenPriceUsdPer1k;
    }

    /** 执行一次离线规划（新任务），返回结果（已落库 Trace）。 */
    public PlanningResult plan(AgentTask task) {
        return planInternal(task, null);
    }

    /** 指定 runId 执行（测试/预注册场景：外部先 pause 该 runId 即可实现运行中暂停）。 */
    public PlanningResult plan(AgentTask task, String runId) {
        return planInternal(task, runId, null);
    }

    /** 从 checkpoint 继续被暂停的运行。 */
    public PlanningResult resume(String runId) {
        Optional<AgentCheckpoint> cp = checkpointService.load(runId);
        if (cp.isEmpty()) {
            throw new IllegalArgumentException("checkpoint 不存在或已完成: " + runId);
        }
        return planInternal(checkpointService.readTask(cp.get()), null, cp.get());
    }

    private PlanningResult planInternal(AgentTask task, AgentCheckpoint resumeCp) {
        return planInternal(task, null, resumeCp);
    }

    private PlanningResult planInternal(AgentTask task, String forcedRunId, AgentCheckpoint resumeCp) {
        String traceId = resumeCp != null ? resumeCp.getRunId()
                : (forcedRunId != null ? forcedRunId : uuid());
        if (resumeCp != null) {
            traceRecorder.deleteTrace(traceId);
        }
        LocalDateTime startedAt = LocalDateTime.now();
        Trace.Builder tb = Trace.builder(traceId, task.activityId(), startedAt, modelVersion, promptVersion);
        PlanningContext ctx = new PlanningContext(task.activityId(), BigDecimal.valueOf(5999.00), task.goal(), forceInvalidPolicy);

        try {
            Map<Role, Artifact> artifacts = new LinkedHashMap<>();
            GuardrailPolicy policy = null;
            int startAttempt = 1;
            RoutingDecision routing = forceInvalidPolicy ? complexityRouter.fullDecision("安全回放强制完整角色图")
                    : complexityRouter.route(task);
            List<SubTask> plan = routing.roles().stream()
                    .map(role -> new SubTask(role, roleLabel(role), task.activityId()))
                    .toList();

            // —— 阶段 DAG ——
            if (!after(resumeCp, "DAG")) {
                tb.addSpan(span(traceId, Role.PLANNER, "adaptiveRoute",
                        objectMapper.writeValueAsString(routing), SpanStatus.OK));
                checkpoint(traceId, task, "DAG", 0, Map.of());
                interruptGate.throwIfPaused(traceId);
            }

            // —— 阶段 ROLES：4 个角色并行产出 ——
            if (!after(resumeCp, "ROLES")) {
                artifacts = runRoleAgentsParallel(plan, ctx, traceId, tb);
                checkpoint(traceId, task, "ROLES", 0, Map.of("artifacts", serializeArtifacts(artifacts)));
                interruptGate.throwIfPaused(traceId);
            } else {
                artifacts = deserializeArtifacts(checkpointService.readContext(resumeCp));
            }

            // —— 阶段 ASSEMBLE：Guardrail Policy Agent 汇编 DSL ——
            if (!after(resumeCp, "ASSEMBLE")) {
                policy = policyAgent.assemble(ctx, artifacts);
                tb.addSpan(span(traceId, Role.GUARDRAIL, "assemble", objectMapper.writeValueAsString(policy), SpanStatus.OK));
                checkpoint(traceId, task, "ASSEMBLE", 0, Map.of("policy", policy));
                interruptGate.throwIfPaused(traceId);
            } else {
                policy = deserializePolicy(checkpointService.readContext(resumeCp));
            }

            // —— 阶段 ATTEMPT：编译 + 独立验证器 + 反思重试 ——
            if (resumeCp != null && resumeCp.getPhase() != null && resumeCp.getPhase().startsWith("ATTEMPT")) {
                startAttempt = resumeCp.getStepIndex() == null ? 1 : resumeCp.getStepIndex();
            }
            for (int attempt = startAttempt; attempt <= maxRetry + 1; attempt++) {
                checkpoint(traceId, task, "ATTEMPT:" + attempt, attempt, Map.of("policy", policy));
                interruptGate.throwIfPaused(traceId);

                DslCompileResult compile = dslCompiler.compile(policy);
                CriticVerdict verdict = critic.verify(policy, compile);

                if (verdict.passed()) {
                    GuardrailPolicy published = repository.publish(policy);
                    tb.addSpan(Span.critic(traceId, attempt, verdict, tb.retryCount(), SpanStatus.OK,
                            "policy-" + published.version()));
                    checkpointService.delete(traceId);
                    return finish(startedAt, tb, AgentRunStatus.SUCCESS, null, false,
                            TerminationReason.COMPLETED, published);
                }

                metrics.criticInterceptions().incrementAndGet();
                tb.addSpan(Span.critic(traceId, attempt, verdict, tb.retryCount(), SpanStatus.RETRY, null));
                if (tb.retryCount() < maxRetry) {
                    tb.incRetry();
                    metrics.retries().incrementAndGet();
                    continue;
                }
                tb.addSpan(Span.critic(traceId, attempt, verdict, tb.retryCount(), SpanStatus.FAIL, null));
                checkpointService.delete(traceId);
                return finish(startedAt, tb, AgentRunStatus.CORRECT_FAILURE, FailureKind.REFUSE, true,
                        TerminationReason.REFUSED, null);
            }
        } catch (InterruptSignal e) {
            // 暂停/取消：阶段边界已保存 checkpoint；取消则删除（不可恢复）
            if (e.isCancelled()) {
                checkpointService.delete(traceId);
            }
            return finish(startedAt, tb, AgentRunStatus.PAUSED, null, false,
                    e.isCancelled() ? TerminationReason.USER_CANCELLED : TerminationReason.INTERRUPTED, null);
        } catch (Exception e) {
            log.error("规划运行异常 traceId={}", traceId, e);
            tb.addSpan(Span.critic(traceId, maxRetry + 1,
                    CriticVerdict.fail(List.of("编排器异常: " + e.getMessage()), 0.5, "HIGH"),
                    tb.retryCount(), SpanStatus.FAIL, null));
            return finish(startedAt, tb, AgentRunStatus.FAILED, null, false, TerminationReason.ERROR, null);
        }
        return finish(startedAt, tb, AgentRunStatus.FAILED, null, false, TerminationReason.ERROR, null);
    }

    private boolean after(AgentCheckpoint cp, String phase) {
        if (cp == null || cp.getPhase() == null) {
            return false;
        }
        String p = cp.getPhase();
        int rank = phaseRank(p);
        return rank > phaseRank(phase);
    }

    private int phaseRank(String phase) {
        if (phase.startsWith("ATTEMPT")) {
            return 3;
        }
        return switch (phase) {
            case "DAG" -> 0;
            case "ROLES" -> 1;
            case "ASSEMBLE" -> 2;
            default -> 3;
        };
    }

    private void checkpoint(String runId, AgentTask task, String phase, int step, Map<String, Object> contextJson) {
        checkpointService.save(runId, task.activityId(), phase, step, task, contextJson);
    }

    private Map<String, Object> serializeArtifacts(Map<Role, Artifact> artifacts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Role, Artifact> e : artifacts.entrySet()) {
            out.put(e.getKey().name(), e.getValue().content());
        }
        return out;
    }

    private Map<Role, Artifact> deserializeArtifacts(Map<String, Object> context) {
        Map<Role, Artifact> out = new LinkedHashMap<>();
        Object raw = context.get("artifacts");
        if (!(raw instanceof Map)) {
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Role role = Role.valueOf(e.getKey());
            out.put(role, Artifact.of(role.name(), "role-artifact", e.getValue()));
        }
        return out;
    }

    private GuardrailPolicy deserializePolicy(Map<String, Object> context) {
        Object raw = context.get("policy");
        return objectMapper.convertValue(raw, GuardrailPolicy.class);
    }

    /** 4 个角色并行执行（每个子任务独立调模型），逐个收结果并记录 span。 */
    private Map<Role, Artifact> runRoleAgentsParallel(List<SubTask> plan, PlanningContext ctx,
                                                      String traceId, Trace.Builder tb) {
        Map<Role, CompletableFuture<RoleAgent.RoleAgentResult>> futures = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(plan.size(), 4));
        try {
            for (SubTask st : plan) {
                futures.put(st.role(), CompletableFuture.supplyAsync(() -> roleAgent.runMeasured(st, ctx), pool));
            }
            Map<Role, Artifact> artifacts = new LinkedHashMap<>();
            for (Map.Entry<Role, CompletableFuture<RoleAgent.RoleAgentResult>> e : futures.entrySet()) {
                try {
                    RoleAgent.RoleAgentResult measured = e.getValue().get(90, TimeUnit.SECONDS);
                    Artifact a = measured.artifact();
                    if (a == null) {
                        metrics.invalidSteps().incrementAndGet();
                        tb.addSpan(span(traceId, e.getKey(), "produce", "null", SpanStatus.FAIL));
                    } else {
                        artifacts.put(e.getKey(), a);
                        tb.addTokens(measured.tokenIn(), measured.tokenOut());
                        tb.addSpan(new Span(uuid(), traceId, null, e.getKey().name(), "produce", null,
                                null, truncate(objectMapper.writeValueAsString(a.content())),
                                measured.tokenIn(), measured.tokenOut(), measured.latencyMs(),
                                0, SpanStatus.OK, null, a.id()));
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
                                  TerminationReason reason, GuardrailPolicy published) {
        LocalDateTime now = LocalDateTime.now();
        tb.status(status).failureKind(kind).correctFailure(correctFailure).finishedAt(now)
                .terminationReason(reason)
                .totalLatencyMs(java.time.Duration.between(startedAt, now).toMillis())
                .budget(tb.budgetTokens(), tokenPriceUsdPer1k
                        .multiply(BigDecimal.valueOf(tb.budgetTokens()))
                        .divide(BigDecimal.valueOf(1000)));
        metrics.terminal(status, kind).incrementAndGet();
        Trace trace = tb.build();
        traceRecorder.persistTrace(trace);
        return new PlanningResult(trace, published, status);
    }

    private Span span(String traceId, Role role, String stepName, String output, SpanStatus status) {
        return new Span(uuid(), traceId, null, role.name(), stepName, null,
                null, truncate(output), 0, 0, 0, 0, status, null, null);
    }

    private String roleLabel(Role role) {
        return switch (role) {
            case SELECTOR -> "选品";
            case PRICER -> "定价";
            case WARMUP -> "库存预热";
            case RISK -> "风控";
            default -> role.name();
        };
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
