package com.insightforge.agent.core.monitor;

import com.insightforge.agent.core.runtime.Span;
import com.insightforge.agent.core.runtime.Trace;
import com.insightforge.persistence.entity.AgentSpan;
import com.insightforge.persistence.entity.AgentTrace;
import com.insightforge.persistence.mapper.AgentSpanMapper;
import com.insightforge.persistence.mapper.AgentTraceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

/**
 * Trace 持久化（设计文档 §2）。把运行期累积的 {@link Trace}（含 span 树）落 MySQL。
 * 这是「状态外化」的硬落地——Agent 实际做了什么，事后可查、可重放、可沉淀回归评估集。
 */
@Component
public class TraceRecorder {

    private final AgentTraceMapper traceMapper;
    private final AgentSpanMapper spanMapper;

    public TraceRecorder(AgentTraceMapper traceMapper, AgentSpanMapper spanMapper) {
        this.traceMapper = traceMapper;
        this.spanMapper = spanMapper;
    }

    public void persistTrace(Trace trace) {
        AgentTrace entity = new AgentTrace();
        entity.setTraceId(trace.traceId());
        entity.setActivityId(trace.activityId());
        entity.setStartedAt(trace.startedAt());
        entity.setFinishedAt(trace.finishedAt());
        entity.setStatus(trace.status().name());
        entity.setFailureKind(trace.failureKind() == null ? null : trace.failureKind().name());
        entity.setModelVersion(trace.modelVersion());
        entity.setPromptVersion(trace.promptVersion());
        entity.setBudgetTokens(trace.budgetTokens());
        entity.setBudgetCostUsd(trace.budgetCostUsd());
        entity.setTotalLatencyMs(trace.totalLatencyMs());
        entity.setCorrectFailure(trace.correctFailure());
        entity.setTerminationReason(trace.terminationReason() == null ? null : trace.terminationReason().name());
        traceMapper.insert(entity);

        for (Span s : trace.spans()) {
            AgentSpan se = new AgentSpan();
            se.setSpanId(s.spanId());
            se.setTraceId(s.traceId());
            se.setParentSpanId(s.parentSpanId());
            se.setRole(s.role());
            se.setStepName(s.stepName());
            se.setToolName(s.toolName());
            se.setToolInput(truncate(s.toolInputJson()));
            se.setToolOutput(truncate(s.toolOutputJson()));
            se.setTokenIn(s.tokenIn());
            se.setTokenOut(s.tokenOut());
            se.setLatencyMs(s.latencyMs());
            se.setRetryCount(s.retryCount());
            se.setStatus(s.status().name());
            se.setConfidence(s.confidence());
            se.setArtifactRef(s.artifactRef());
            spanMapper.insert(se);
        }
    }

    /** 删除一次运行的全部 trace/span（resume 前清理旧快照，避免主键冲突）。 */
    public void deleteTrace(String traceId) {
        spanMapper.delete(new QueryWrapper<AgentSpan>().eq("trace_id", traceId));
        traceMapper.deleteById(traceId);
    }

    /** 列定义 VARCHAR(4000)，超出截断，防止撑爆。 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
