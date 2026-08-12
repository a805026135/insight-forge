package com.insightforge.api.controller;

import com.insightforge.agent.core.planning.MultiAgentOrchestrator;
import com.insightforge.agent.core.planning.PlanningResult;
import com.insightforge.agent.core.runtime.CheckpointService;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.agent.core.runtime.InterruptGate;
import com.insightforge.persistence.entity.AgentCheckpoint;
import com.insightforge.api.dto.AgentPlanRequest;
import com.insightforge.api.dto.AgentPlanResponse;
import com.insightforge.api.observability.dto.TraceView;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.common.result.ApiResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final InterruptGate interruptGate;
    private final CheckpointService checkpointService;

    public AgentController(MultiAgentOrchestrator orchestrator, ActivitySkuMapper activitySkuMapper,
                           InterruptGate interruptGate, CheckpointService checkpointService) {
        this.orchestrator = orchestrator;
        this.activitySkuMapper = activitySkuMapper;
        this.interruptGate = interruptGate;
        this.checkpointService = checkpointService;
    }

    /** POST /api/agent/runs/{runId}/pause 请求暂停运行中的规划（下一个阶段边界生效） */
    @PostMapping("/runs/{runId}/pause")
    public ApiResult<Map<String, Object>> pause(@PathVariable String runId) {
        interruptGate.pause(runId);
        return ApiResult.ok(Map.of("runId", runId, "status", "PAUSE_REQUESTED"));
    }

    /** POST /api/agent/runs/{runId}/cancel 取消运行（checkpoint 删除，不可恢复） */
    @PostMapping("/runs/{runId}/cancel")
    public ApiResult<Map<String, Object>> cancel(@PathVariable String runId) {
        interruptGate.cancel(runId);
        return ApiResult.ok(Map.of("runId", runId, "status", "CANCEL_REQUESTED"));
    }

    /** POST /api/agent/runs/{runId}/resume 从 checkpoint 继续被暂停的规划 */
    @PostMapping("/runs/{runId}/resume")
    public ApiResult<AgentPlanResponse> resume(@PathVariable String runId) {
        try {
            PlanningResult result = orchestrator.resume(runId);
            String publishedVersion = null;
            if (result.status() == AgentRunStatus.SUCCESS && result.published() != null) {
                publishedVersion = result.published().version();
            }
            String failureKindStr = result.trace().failureKind() != null
                    ? result.trace().failureKind().name() : null;
            return ApiResult.ok(new AgentPlanResponse(
                    result.trace().traceId(), result.status().name(), failureKindStr,
                    result.trace().correctFailure(), publishedVersion,
                    result.trace().totalLatencyMs(),
                    TraceView.toVerdict(result.status().name(), failureKindStr)));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(404, e.getMessage());
        }
    }

    /** GET /api/agent/runs/{runId} 查询 checkpoint 状态（阶段/进度） */
    @GetMapping("/runs/{runId}")
    public ApiResult<Map<String, Object>> runStatus(@PathVariable String runId) {
        var cp = checkpointService.load(runId);
        if (cp.isEmpty()) {
            return ApiResult.fail(404, "无该运行的 checkpoint（可能已完成或不存在）");
        }
        AgentCheckpoint c = cp.get();
        return ApiResult.ok(Map.of(
                "runId", runId,
                "phase", c.getPhase(),
                "stepIndex", c.getStepIndex(),
                "status", c.getStatus(),
                "activityId", c.getActivityId(),
                "updatedAt", String.valueOf(c.getUpdatedAt())));
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

    /** 失败回放：从原 trace 的活动和首个工具输入恢复上下文，生成一条新 trace 对比修复效果。 */
    @PostMapping("/replay/{traceId}")
    public ApiResult<AgentPlanResponse> replay(@PathVariable String traceId) {
        var original = checkpointService.load(traceId);
        if (original.isPresent()) {
            return resume(traceId);
        }
        return ApiResult.fail(409, "该 trace 已终结且未保留 checkpoint；请从评测失败样本执行确定性回放");
    }
}
