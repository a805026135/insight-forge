package com.insightforge.agent.core.model;

/**
 * 一次模型推理响应。
 * - content：最终答案（通常是结构化方案 JSON）。
 * - toolCall：若模型决定调工具，则非空（命中工具循环）；否则为空（代表本轮给出最终答案）。
 * - tokenIn/tokenOut/latencyMs：埋点用，供 BudgetGate 累计与 L1/L4 指标。
 */
public record ModelResponse(String content, ToolCall toolCall, int tokenIn, int tokenOut, long latencyMs) {

    /** 便捷构造：无工具调用（直接给最终答案） */
    public static ModelResponse answer(String content, int tokenIn, int tokenOut, long latencyMs) {
        return new ModelResponse(content, null, tokenIn, tokenOut, latencyMs);
    }

    /** 便捷构造：请求调用工具 */
    public static ModelResponse tool(ToolCall toolCall, int tokenIn, int tokenOut, long latencyMs) {
        return new ModelResponse(null, toolCall, tokenIn, tokenOut, latencyMs);
    }
}
