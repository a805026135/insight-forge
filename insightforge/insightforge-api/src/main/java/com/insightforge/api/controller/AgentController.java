package com.insightforge.api.controller;

import com.insightforge.agent.core.planning.MultiAgentOrchestrator;
import com.insightforge.agent.core.planning.PlanningResult;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.api.dto.AgentPlanRequest;
import com.insightforge.api.dto.AgentPlanResponse;
import com.insightforge.api.observability.dto.TraceView;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.common.result.ApiResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 控制面接入层：手动触发离线规划。
 *
 * POST /api/agent/plan   触发一次多智能体离线规划（Planner → 四角色 → 汇编 DSL → 编译 → 发布）
 *
 * 纪律重申：本端点跑在「控制面」（离线），产出的 DSL 落库后供在线规则引擎读取执行。
 * 它绝不直接改库存/订单——那属于秒杀热路径（数据面）。LLM 永不进秒杀热路径。
 *
 * 沙箱环境默认用 LocalDeterministicModelClient（确定性桩，不联网），
 * 生产环境配 insightforge.agent.deepseek.enabled=true 后自动切换到 DeepSeek。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final MultiAgentOrchestrator orchestrator;
    private final ActivitySkuMapper activitySkuMapper;

    public AgentController(MultiAgentOrchestrator orchestrator, ActivitySkuMapper activitySkuMapper) {
        this.orchestrator = orchestrator;
        this.activitySkuMapper = activitySkuMapper;
    }

    /**
     * 手动触发一次 Agent 离线规划。
     * 请求体示例：{"activityId": 1, "goal": "为活动 1 制定双11 控盘预案"}
     * 返回 traceId（可在 Trace 查看器里查详情）+ 发布结果。
     */
    @PostMapping("/plan")
    public ApiResult<AgentPlanResponse> plan(@RequestBody AgentPlanRequest req) {
        // Phase 17 商家隔离：商家只能规划自己参与的活动（活动下存在属于该商家的 SKU）
        var user = AuthContextHolder.get();
        if (user != null && user.isMerchant() && user.merchantId() != null) {
            long count = activitySkuMapper.selectCount(new QueryWrapper<ActivitySku>()
                    .eq("activity_id", req.activityId())
                    .eq("merchant_id", user.merchantId()));
            if (count == 0) {
                return ApiResult.fail(403, "无权规划未参与的活動");
            }
        }
        AgentTask task = new AgentTask(req.activityId(), req.goal(), Map.of());
        PlanningResult result = orchestrator.plan(task);

        String publishedVersion = null;
        if (result.status() == AgentRunStatus.SUCCESS && result.published() != null) {
            publishedVersion = result.published().version();
        }

        // Trace 是 record，用访问器方法（无 get 前缀）
        String failureKindStr = result.trace().failureKind() != null
                ? result.trace().failureKind().name() : null;

        String verdict = TraceView.toVerdict(result.status().name(), failureKindStr);

        return ApiResult.ok(new AgentPlanResponse(
                result.trace().traceId(),
                result.status().name(),
                failureKindStr,
                result.trace().correctFailure(),
                publishedVersion,
                result.trace().totalLatencyMs(),
                verdict
        ));
    }
}
