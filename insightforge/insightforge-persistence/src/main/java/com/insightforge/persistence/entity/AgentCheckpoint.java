package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行 checkpoint（Phase 19d）：运行中暂停时的快照，支持 /resume 继续。
 * 保存任务、阶段、进度与中间产物（artifacts/policy 序列化为 JSON）。
 */
@Data
@TableName("agent_checkpoint")
public class AgentCheckpoint {

    /** = traceId */
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    private Long activityId;

    /** DAG / ROLES / ASSEMBLE / ATTEMPT:n */
    private String phase;

    private Integer stepIndex;

    private String taskJson;

    /** 中间产物（artifacts / policy）序列化 */
    private String contextJson;

    /** PAUSED / CANCELLED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
