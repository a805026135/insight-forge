package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 一次规划运行（= 一个 trace）的持久化实体。
 *
 * 对应监测设计文档 {@code agent-observability-evaluation.md} §2 的 {@code agent_trace} 表。
 * status 三态：SUCCESS / CORRECT_FAILURE / FAILED（见 {@code AgentRunStatus}）。
 * 这里用 VARCHAR 而非 ENUM，保证 H2(本地) 与 MySQL 8 都能零改动执行同一份 DDL。
 *
 * 为什么落 MySQL 而不是 Langfuse：教学项目要零外部依赖可跑；MySQL 已在本项目里，
 * Trace 落库即「状态外化」（设计文档 §0 第 7 条），Phase 10 再做一个只读查看器即可。
 */
@Data
@TableName("agent_trace")
public class AgentTrace {

    /** = Orchestrator run id；也用于在线规则引擎运行的归因（trace_id 透传）。 */
    @TableId(value = "trace_id", type = IdType.INPUT)
    private String traceId;

    private Long activityId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** SUCCESS / CORRECT_FAILURE / FAILED */
    private String status;

    /** ASK / REFUSE / ESCALATE / null（仅 CORRECT_FAILURE 时有值） */
    private String failureKind;

    private String modelVersion;
    private String promptVersion;

    /** 本次运行累计消耗 token（预算闸门统计） */
    private Integer budgetTokens;
    /** 本次运行预估成本（美元），用于 L4 系统评估 */
    private BigDecimal budgetCostUsd;
    /** 总耗时（毫秒），用于 L4 离线规划延迟 P95 */
    private Long totalLatencyMs;

    /** 冗余字段，便于聚合正确失败率（设计文档 §1） */
    private Boolean correctFailure;
}
