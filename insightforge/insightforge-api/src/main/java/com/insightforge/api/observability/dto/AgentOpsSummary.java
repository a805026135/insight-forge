package com.insightforge.api.observability.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Agent 版本、用量、成本和失败回放总览。 */
public record AgentOpsSummary(long traces, long spans, long inputTokens, long outputTokens,
                              BigDecimal totalCostUsd, BigDecimal averageCostUsd,
                              long p95LatencyMs, Map<String, Long> promptVersions,
                              Map<String, Long> modelVersions, Map<String, Long> kbVersions,
                              List<ReplayCandidate> replayCandidates) {
    public record ReplayCandidate(String traceId, Long activityId, String status,
                                  String failureKind, String promptVersion,
                                  String modelVersion, Long latencyMs, BigDecimal costUsd) {}
}
