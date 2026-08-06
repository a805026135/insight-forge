package com.insightforge.agent.core.runtime;

/**
 * 正确失败的具体类型（设计文档 §1）。仅 CORRECT_FAILURE 时有值；SUCCESS/FAILED 时为 null。
 */
public enum FailureKind {
    ASK,       // 信息不足，主动请求补充
    REFUSE,    // 权限/范围/合规不足，拒绝交付不合规结果
    ESCALATE   // 超预算/超阈值，优雅转人工
}
