package com.insightforge.agent.core.runtime;

/**
 * 单个步骤（span）的状态。
 */
public enum SpanStatus {
    OK,    // 正常完成
    RETRY, // Critic 拦截，进入反思重试
    FAIL   // 失败（工具异常 / 重试耗尽）
}
