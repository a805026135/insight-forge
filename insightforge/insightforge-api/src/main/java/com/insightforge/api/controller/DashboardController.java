package com.insightforge.api.controller;

import com.insightforge.api.explain.ExplainInput;
import com.insightforge.api.explain.ExplainabilityService;
import com.insightforge.api.observability.ObservabilityService;
import com.insightforge.api.observability.dto.DashboardSnapshot;
import com.insightforge.api.observability.dto.GuardrailView;
import com.insightforge.api.observability.dto.TraceView;
import com.insightforge.common.result.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 大屏 / 可观测数据 API（Phase 10）。
 *
 * 这是「控制面可视化」的后端入口：把秒杀经营、Agent 规划质量、护栏策略三块数据，
 * 以及每条控盘指令的「人话解释」通过 REST 暴露给前端。全部只读，全部来自已落库真相源。
 *
 * 路径约定（与既有 controller 一致，统一挂在 /api 下）：
 *   GET /api/dashboard/overview   总览快照
 *   GET /api/dashboard/traces     最近 trace（Trace 查看器）
 *   GET /api/dashboard/hitl       HITL 人工复核队列
 *   GET /api/dashboard/guardrails 护栏策略 + 风险分级
 *   GET /api/dashboard/explain    给定决策事实 → 自然语言解释（加分项 #3）
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ObservabilityService observabilityService;
    private final ExplainabilityService explainabilityService;

    public DashboardController(ObservabilityService observabilityService,
                               ExplainabilityService explainabilityService) {
        this.observabilityService = observabilityService;
        this.explainabilityService = explainabilityService;
    }

    @GetMapping("/overview")
    public ApiResult<DashboardSnapshot> overview() {
        return ApiResult.ok(observabilityService.buildSnapshot());
    }

    @GetMapping("/traces")
    public ApiResult<List<TraceView>> traces(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(observabilityService.recentTraces(limit));
    }

    @GetMapping("/hitl")
    public ApiResult<List<TraceView>> hitl() {
        return ApiResult.ok(observabilityService.hitlQueue());
    }

    @GetMapping("/guardrails")
    public ApiResult<List<GuardrailView>> guardrails(@RequestParam(required = false) Long activityId) {
        return ApiResult.ok(observabilityService.guardrails(activityId));
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
