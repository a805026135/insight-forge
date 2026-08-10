package com.insightforge.api.observability.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 一次规划运行（trace）的大屏视图（Phase 10，对应监测设计文档 L1/L2/L4）。
 *
 * 由 {@code AgentTrace} 实体裁剪而来——只暴露大屏需要、且能外化的字段，
 * 敏感内部状态不外泄。verdict 是把三态状态机 + failureKind 翻译成人话，
 * 让运营一眼看懂「这次 AI 跑了什么结果」。
 */
public record TraceView(
        String traceId,
        Long activityId,
        String status,
        String failureKind,
        Boolean correctFailure,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long totalLatencyMs,
        BigDecimal budgetCostUsd,
        String modelVersion,
        /** 人话 verdict，如「主动询问运营（正确失败）」 */
        String verdict,
        /** HITL 审批结果：null=未复核 / APPROVED=已通过 / REJECTED=已驳回 */
        String reviewStatus
) {

    /** 把数据库里的三态 + failureKind 翻译成运营能秒懂的结论。 */
    public static String toVerdict(String status, String failureKind) {
        if ("SUCCESS".equals(status)) {
            return "规划成功并落库策略";
        }
        if ("CORRECT_FAILURE".equals(status)) {
            return switch (failureKind == null ? "" : failureKind) {
                case "ASK" -> "主动询问运营（正确失败）";
                case "REFUSE" -> "拒绝越权/不安全指令（正确失败）";
                case "ESCALATE" -> "升级人工处理（正确失败）";
                default -> "正确失败（未执行风险动作）";
            };
        }
        if ("FAILED".equals(status)) {
            return "运行失败，需排查";
        }
        return "未知状态";
    }
}
