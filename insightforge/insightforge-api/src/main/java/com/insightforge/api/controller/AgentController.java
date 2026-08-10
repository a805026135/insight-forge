package com.insightforge.api.controller;

import com.insightforge.agent.core.planning.MultiAgentOrchestrator;
import com.insightforge.agent.core.planning.PlanningResult;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.api.dto.AgentPlanRequest;
import com.insightforge.api.dto.AgentPlanResponse;
import com.insightforge.api.observability.dto.TraceView;
import com.insightforge.common.result.ApiResult;
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

    public AgentController(MultiAgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 手动触发一次 Agent 离线规划。
     * 请求体示例：{"activityId": 1, "goal": "为活动 1 制定双11 控盘预案"}
     * 返回 traceId（可在 Trace 查看器里查详情）+ 发布结果。
     */
    @PostMapping("/plan")
    public ApiResult<AgentPlanResponse> plan(@RequestBody AgentPlanRequest req) {
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
