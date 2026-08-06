package com.insightforge.agent.core.runtime;

/**
 * Agent 一次运行的终态（设计文档 §1 运行状态机）。
 * 三态对应「正确失败率」红线：SUCCESS 与 CORRECT_FAILURE 都是「体面结束」，FAILED 是必须修的崩溃。
 */
public enum AgentRunStatus {
    SUCCESS,          // 产出有效方案 + Critic 通过
    CORRECT_FAILURE,  // 主动、正确地终止（ASK/REFUSE/ESCALATE）—— 失败的「对」
    FAILED            // 崩溃 / 超时 / 预算耗尽且未优雅降级 —— 坏的失败
}
