package com.insightforge.agent.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.agent.core.model.ModelResponse;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 一个步骤（span）领域对象，对应持久化表 {@code agent_span}。
 * 提供静态工厂，让 runtime 用一行就能造出「建模 / 工具 / Critic」三类 span。
 */
public record Span(
        String spanId,
        String traceId,
        String parentSpanId,
        String role,
        String stepName,
        String toolName,
        String toolInputJson,
        String toolOutputJson,
        int tokenIn,
        int tokenOut,
        long latencyMs,
        int retryCount,
        SpanStatus status,
        BigDecimal confidence,
        String artifactRef) {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 建模思考 span（Orchestrator 调模型）。 */
    public static Span model(String traceId, int step, ModelResponse resp, String parentSpanId) {
        String out = resp.toolCall() != null ? safe(resp.toolCall()) : safe(resp.content());
        return new Span(uuid(), traceId, parentSpanId, "Orchestrator", "think#" + step, null,
                null, out, resp.tokenIn(), resp.tokenOut(), resp.latencyMs(), 0, SpanStatus.OK, null, null);
    }

    /** 工具成功执行 span。 */
    public static Span toolOk(String traceId, int step, String toolName,
                              Map<String, Object> args, String outJson, long latencyMs) {
        return new Span(uuid(), traceId, null, "Tool", "execute:" + toolName + "#" + step, toolName,
                safe(args), outJson, 0, 0, latencyMs, 0, SpanStatus.OK, null, null);
    }

    /** 工具失败 span（未知工具 / 参数不合规 / 执行异常）。 */
    public static Span toolFail(String traceId, int step, String toolName,
                                Map<String, Object> args, String error) {
        return new Span(uuid(), traceId, null, "Tool", "execute:" + toolName + "#" + step, toolName,
                safe(args), error, 0, 0, 0, 0, SpanStatus.FAIL, null, null);
    }

    /** Critic 校验 span。 */
    public static Span critic(String traceId, int step, CriticVerdict verdict,
                              int retryCount, SpanStatus status, String artifactRef) {
        String out = verdict.passed() ? "PASS" : String.join("; ", verdict.reasons());
        return new Span(uuid(), traceId, null, "Critic", "verify#" + step, null, null, out,
                0, 0, 0, retryCount, status, BigDecimal.valueOf(verdict.confidence()), artifactRef);
    }

    private static String safe(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return OM.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
