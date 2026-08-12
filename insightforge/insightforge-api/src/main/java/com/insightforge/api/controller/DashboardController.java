package com.insightforge.api.controller;

import com.insightforge.api.explain.ExplainInput;
import com.insightforge.api.explain.ExplainabilityService;
import com.insightforge.api.observability.ObservabilityService;
import com.insightforge.api.observability.dto.DashboardSnapshot;
import com.insightforge.api.observability.dto.GuardrailView;
import com.insightforge.api.observability.dto.SpanView;
import com.insightforge.api.observability.dto.SkuView;
import com.insightforge.api.observability.dto.TraceView;
import com.insightforge.api.observability.dto.AgentOpsSummary;
import com.insightforge.api.service.RagEvaluationService;
import com.insightforge.api.service.PromptVersionService;
import com.insightforge.agent.core.evaluation.EvaluationHarness;
import com.insightforge.api.service.FailureReplayService;
import com.insightforge.common.result.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 大屏 / 可观测数据 API（Phase 10 + 扩展）。
 *
 * 这是「控制面可视化」的后端入口：把秒杀经营、Agent 规划质量、护栏策略三块数据，
 * 以及每条控盘指令的「人话解释」通过 REST 暴露给前端。全部只读（HITL 审批除外）。
 *
 * 路径约定（与既有 controller 一致，统一挂在 /api 下）：
 *   GET  /api/dashboard/overview              总览快照
 *   GET  /api/dashboard/traces                最近 trace（Trace 查看器）
 *   GET  /api/dashboard/traces/{traceId}/spans 某条 trace 的 span 树（Trace 详情展开）
 *   GET  /api/dashboard/hitl                  HITL 人工复核队列
 *   POST /api/dashboard/hitl/{traceId}/approve  HITL 审批通过
 *   POST /api/dashboard/hitl/{traceId}/reject   HITL 审批驳回
 *   GET  /api/dashboard/guardrails            护栏策略 + 风险分级
 *   GET  /api/dashboard/explain               给定决策事实 → 自然语言解释（加分项 #3）
 *   GET  /api/dashboard/skus?activityId={id}  某活动下全部 SKU（含商品名/价格/库存）
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ObservabilityService observabilityService;
    private final ExplainabilityService explainabilityService;
    private final RagEvaluationService ragEvaluationService;
    private final PromptVersionService promptVersionService;
    private final EvaluationHarness evaluationHarness;
    private final FailureReplayService failureReplayService;

    public DashboardController(ObservabilityService observabilityService,
                               ExplainabilityService explainabilityService,
                               RagEvaluationService ragEvaluationService,
                               PromptVersionService promptVersionService,
                               EvaluationHarness evaluationHarness,
                               FailureReplayService failureReplayService) {
        this.observabilityService = observabilityService;
        this.explainabilityService = explainabilityService;
        this.ragEvaluationService = ragEvaluationService;
        this.promptVersionService = promptVersionService;
        this.evaluationHarness = evaluationHarness;
        this.failureReplayService = failureReplayService;
    }

    @GetMapping("/overview")
    public ApiResult<DashboardSnapshot> overview() {
        return ApiResult.ok(observabilityService.buildSnapshot());
    }

    @GetMapping("/traces")
    public ApiResult<List<TraceView>> traces(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(observabilityService.recentTraces(limit));
    }

    @GetMapping("/agent-ops")
    public ApiResult<AgentOpsSummary> agentOps() {
        return ApiResult.ok(observabilityService.agentOpsSummary());
    }

    @GetMapping("/prompt-versions")
    public ApiResult<?> promptVersions() {
        return ApiResult.ok(promptVersionService.list());
    }

    @PostMapping("/rag-evaluation/run")
    public ApiResult<RagEvaluationService.Report> runRagEvaluation() throws Exception {
        return ApiResult.ok(ragEvaluationService.run());
    }

    @PostMapping("/agent-evaluation/run")
    public ApiResult<?> runAgentEvaluation() throws Exception {
        var report = evaluationHarness.run();
        boolean gatePassed = report.gatePassed();
        return ApiResult.ok(new AgentEvaluationView(report.taskMetrics(), gatePassed,
                List.copyOf(report.gateFailures()), report.goldenResults(), report.regressionResults()));
    }

    public record AgentEvaluationView(
            com.insightforge.agent.core.evaluation.EvaluationReport.TaskMetrics metrics,
            boolean gatePassed, List<String> gateFailures, List<?> positiveCases, List<?> safetyCases) {}

    @PostMapping("/failure-replay/run")
    public ApiResult<?> replayFailures() throws Exception {
        return ApiResult.ok(failureReplayService.start());
    }

    @GetMapping("/failure-replay/{jobId}")
    public ApiResult<?> replayStatus(@PathVariable String jobId) {
        try { return ApiResult.ok(failureReplayService.get(jobId)); }
        catch (IllegalArgumentException e) { return ApiResult.fail(404, e.getMessage()); }
    }

    /** 某条 trace 的 span 树（前端 Trace 查看器点击某行后展开详情）。 */
    @GetMapping("/traces/{traceId}/spans")
    public ApiResult<List<SpanView>> spans(@PathVariable String traceId) {
        return ApiResult.ok(observabilityService.spansByTrace(traceId));
    }

    @GetMapping("/hitl")
    public ApiResult<List<TraceView>> hitl() {
        return ApiResult.ok(observabilityService.hitlQueue());
    }

    /** HITL 审批通过：把 trace 标记为 APPROVED。 */
    @PostMapping("/hitl/{traceId}/approve")
    public ApiResult<Void> approve(@PathVariable String traceId) {
        observabilityService.approveTrace(traceId);
        return ApiResult.ok(null);
    }

    /** HITL 审批驳回：把 trace 标记为 REJECTED。 */
    @PostMapping("/hitl/{traceId}/reject")
    public ApiResult<Void> reject(@PathVariable String traceId) {
        observabilityService.rejectTrace(traceId);
        return ApiResult.ok(null);
    }

    @GetMapping("/guardrails")
    public ApiResult<List<GuardrailView>> guardrails(@RequestParam(required = false) Long activityId) {
        return ApiResult.ok(observabilityService.guardrails(activityId));
    }

    /** 某活动下全部 SKU（含商品名/成本价/库存，给前端秒杀商品列表用）。 */
    @GetMapping("/skus")
    public ApiResult<List<SkuView>> skus(@RequestParam Long activityId) {
        return ApiResult.ok(observabilityService.skusByActivity(activityId));
    }

    /**
     * 控盘指令可解释性。前端把「规则引擎刚做了什么」的事实字段传进来，
     * 后端用确定性模板生成人话，绝不用 LLM 实时编造。
     */
    @GetMapping("/explain")
    public ApiResult<String> explain(@RequestParam(required = false) Long activityId,
                                     @RequestParam(required = false) Long skuId,
                                     @RequestParam(required = false) String metric,
                                     @RequestParam(required = false) String metricValue,
                                     @RequestParam(required = false) String threshold,
                                     @RequestParam String action,
                                     @RequestParam(required = false) Integer amount,
                                     @RequestParam(required = false) BigDecimal price,
                                     @RequestParam(required = false) String riskLevel) {
        ExplainInput in = new ExplainInput(activityId, skuId, metric, metricValue,
                threshold, action, amount, price, riskLevel);
        return ApiResult.ok(explainabilityService.explain(in));
    }
}
