package com.insightforge.agent.core.runtime;

/**
 * 运行终止原因（显式化，Phase 19d）：无论成功失败，一次 run 都以明确原因收尾，
 * 写入 agent_trace.termination_reason，可观测、可复盘。
 */
public enum TerminationReason {
    /** 正常产出并通过 Critic */
    COMPLETED,
    /** 预算（token/成本/延迟）超限，优雅降级 */
    BUDGET_EXCEEDED,
    /** 方案不合规被拒绝交付（REFUSE） */
    REFUSED,
    /** 用户主动取消 */
    USER_CANCELLED,
    /** 运行中被暂停（checkpoint 已保存，可恢复） */
    INTERRUPTED,
    /** 运行时异常/崩溃 */
    ERROR
}
