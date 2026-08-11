package com.insightforge.agent.core.runtime;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 运行（= 一个 trace）的领域对象，对应持久化表 {@code agent_trace} + 多条 {@code agent_span}。
 * 用 Builder 累积 span 与终态，最后 {@link #build()} 生成不可变快照落库。
 */
public record Trace(
        String traceId,
        Long activityId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        AgentRunStatus status,
        FailureKind failureKind,
        String modelVersion,
        String promptVersion,
        int budgetTokens,
        BigDecimal budgetCostUsd,
        long totalLatencyMs,
        boolean correctFailure,
        TerminationReason terminationReason,
        List<Span> spans) {

    public static Builder builder(String traceId, Long activityId, LocalDateTime startedAt,
                                  String modelVersion, String promptVersion) {
        return new Builder(traceId, activityId, startedAt, modelVersion, promptVersion);
    }

    /** 累积式构造器（运行期内可变，build 后不可变）。 */
    public static class Builder {
        private final String traceId;
        private final Long activityId;
        private final LocalDateTime startedAt;
        private final String modelVersion;
        private final String promptVersion;

        private LocalDateTime finishedAt;
        private AgentRunStatus status = AgentRunStatus.FAILED;
        private FailureKind failureKind;
        private int budgetTokens;
        private BigDecimal budgetCostUsd;
        private long totalLatencyMs;
        private boolean correctFailure;
        private TerminationReason terminationReason;
        private int retryCount;
        private final List<Span> spans = new ArrayList<>();

        Builder(String traceId, Long activityId, LocalDateTime startedAt,
                String modelVersion, String promptVersion) {
            this.traceId = traceId;
            this.activityId = activityId;
            this.startedAt = startedAt;
            this.modelVersion = modelVersion;
            this.promptVersion = promptVersion;
        }

        public Builder status(AgentRunStatus status) {
            this.status = status;
            return this;
        }

        public Builder failureKind(FailureKind failureKind) {
            this.failureKind = failureKind;
            return this;
        }

        public Builder finishedAt(LocalDateTime finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder budget(int tokens, BigDecimal cost) {
            this.budgetTokens = tokens;
            this.budgetCostUsd = cost;
            return this;
        }

        public Builder totalLatencyMs(long ms) {
            this.totalLatencyMs = ms;
            return this;
        }

        public Builder correctFailure(boolean b) {
            this.correctFailure = b;
            return this;
        }

        public Builder terminationReason(TerminationReason reason) {
            this.terminationReason = reason;
            return this;
        }

        public Builder addSpan(Span span) {
            this.spans.add(span);
            return this;
        }

        public int retryCount() {
            return retryCount;
        }

        public Builder incRetry() {
            this.retryCount++;
            return this;
        }

        public Trace build() {
            return new Trace(traceId, activityId, startedAt, finishedAt, status, failureKind,
                    modelVersion, promptVersion, budgetTokens, budgetCostUsd, totalLatencyMs,
                    correctFailure, terminationReason, List.copyOf(spans));
        }
    }
}
