package com.insightforge.agent.core.planning;

import java.util.List;

/**
 * 一份护栏策略 DSL（Phase 8 的「产出物」）。
 * 大白话：这是 Agent 离线规划后交给「护栏规则引擎（Phase 9）」去在线执行的「控盘说明书」。
 * 它是结构化、可编译、可版本化的中间表示——把 AI 的「想法」和「执行」解耦，
 * LLM 只负责生成它，绝不直接碰库存/订单（控制面/数据面分离）。
 *
 * version 由仓库在发布时分配（v1/v2...），编排器产出时占位 "v?"。
 */
public record GuardrailPolicy(
        Long activityId,
        String version,
        List<GuardrailRule> rules
) {
}
