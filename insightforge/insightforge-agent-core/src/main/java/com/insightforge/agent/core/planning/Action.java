package com.insightforge.agent.core.planning;

/**
 * 护栏 DSL 命中的「动作」（大白话：规则触发后干什么）。
 * - REPRICE：调价（走同一套锁，热路径执行，LLM 不进热路径）
 * - REALLOCATE：库存调拨（只建新段，热路径执行）
 * - ALERT：仅告警（不写共享状态）
 * INVALID 兜底：未知动作编译期判非法。
 */
public enum Action {
    REPRICE, REALLOCATE, ALERT, INVALID
}
