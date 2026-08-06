package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Agent 每个步骤（span）的持久化实体，对应设计文档 §2 的 {@code agent_span} 表。
 *
 * 一次 trace 由多条 span 组成（建模调用 / 工具调用 / Critic 校验 …），构成 span 树（parent_span_id）。
 * tool_input / tool_output 以 JSON 字符串存储（避免 H2/MySQL 对 JSON 类型的差异），写入前由运行时截断，防止撑爆。
 */
@Data
@TableName("agent_span")
public class AgentSpan {

    @TableId(value = "span_id", type = IdType.INPUT)
    private String spanId;

    private String traceId;
    private String parentSpanId;

    /** Planner / Data / Viz / Writer / Critic / Orchestrator / Tool */
    private String role;
    private String stepName;

    /** 命中的工具名（仅工具类 span 有值） */
    private String toolName;
    /** 工具入参（JSON 字符串） */
    private String toolInput;
    /** 工具/模型出参（JSON 字符串，已截断） */
    private String toolOutput;

    private Integer tokenIn;
    private Integer tokenOut;
    private Long latencyMs;

    /** 反思重试次数（≤3，见 BudgetGate.maxRetry） */
    private Integer retryCount;
    /** OK / RETRY / FAIL */
    private String status;

    /** 该步骤置信度（0~1） */
    private BigDecimal confidence;
    /** 关联产物 id（DSL id / report id / artifact id） */
    private String artifactRef;
}
