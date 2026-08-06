package com.insightforge.agent.core.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L1 组件指标埋点（设计文档 §3 L1 + 三条铁律之「状态外化」）。
 * 纯内存计数器，Phase 10 再接到 actuator / 看板。Phase 7 先把这些「可观测数字」生产出来：
 *   - modelCalls / toolCalls：组件调用量
 *   - schemaViolations：参数 Schema 违规（目标 0）
 *   - criticRejections / retries：反思重试分布
 *   - successes / correctFailures / failures：终态分布（正确失败率 = correctFailures / 总结束）
 */
@Component
public class AgentMetrics {

    private final AtomicLong modelCalls = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong schemaViolations = new AtomicLong();
    private final AtomicLong criticRejections = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong correctFailures = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public AtomicLong modelCalls() {
        return modelCalls;
    }

    public AtomicLong toolCalls() {
        return toolCalls;
    }

    public AtomicLong schemaViolations() {
        return schemaViolations;
    }

    public AtomicLong criticRejections() {
        return criticRejections;
    }

    public AtomicLong retries() {
        return retries;
    }

    public AtomicLong successes() {
        return successes;
    }

    public AtomicLong correctFailures() {
        return correctFailures;
    }

    public AtomicLong failures() {
        return failures;
    }

    /** 终态计数器（便于终态分布统计）。 */
    public AtomicLong terminal(AgentRunStatus status, FailureKind kind) {
        if (status == AgentRunStatus.SUCCESS) {
            return successes;
        }
        if (status == AgentRunStatus.FAILED) {
            return failures;
        }
        // CORRECT_FAILURE
        return correctFailures;
    }

    /** 当前快照（Phase 10 看板/actuator 直接序列化）。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> s = new LinkedHashMap<>();
        s.put("modelCalls", modelCalls.get());
        s.put("toolCalls", toolCalls.get());
        s.put("schemaViolations", schemaViolations.get());
        s.put("criticRejections", criticRejections.get());
        s.put("retries", retries.get());
        s.put("successes", successes.get());
        s.put("correctFailures", correctFailures.get());
        s.put("failures", failures.get());
        long total = successes.get() + correctFailures.get() + failures.get();
        s.put("totalRuns", total);
        s.put("correctFailureRate", total == 0 ? 0 : correctFailures.get() * 100 / total);
        return s;
    }
}
