package com.insightforge.api.observability.dto;

import java.math.BigDecimal;

/**
 * Agent 单步 span 的大屏视图（Phase 10 扩展：Trace 详情展开）。
 *
 * 一次 trace 由多条 span 构成（建模调用 / 工具调用 / Critic 校验…），
 * 形成 span 树（parentSpanId 指向父步骤）。
 * 本 DTO 从 AgentSpan 实体裁剪而来，只暴露大屏需要的字段。
 */
public record SpanView(
        String spanId,
        String traceId,
        String parentSpanId,
        String role,
        String stepName,
        String toolName,
        String toolInput,
        String toolOutput,
        Integer tokenIn,
        Integer tokenOut,
        Long latencyMs,
        Integer retryCount,
        String status,
        BigDecimal confidence,
        String artifactRef
) {
}
