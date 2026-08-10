package com.insightforge.api.dto;

/**
 * Agent 离线规划的结果 DTO（给前端展示用）。
 *
 * 无论成功还是正确失败，trace 都已落库（可观测、可追责）。
 * publishedVersion 仅在 SUCCESS 时有值（Fail-Close：不合规 DSL 不发布）。
 */
public record AgentPlanResponse(
        String traceId,
        String status,           // SUCCESS / CORRECT_FAILURE / FAILED
        String failureKind,      // ASK / REFUSE / ESCALATE / null
        Boolean correctFailure,
        String publishedVersion, // 发布的 DSL 版本号（如 "v1"），仅 SUCCESS 时有值
        Long totalLatencyMs,
        String verdict           // 人话结论
) {
}
