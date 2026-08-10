package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 知识库切块（Phase 19）：RAG 的最小检索单元。
 * embedding 列存归一化向量的 JSON 数组（TEXT，兼容 H2/MySQL）；
 * dimension 记录维度，加载索引时按维度过滤。
 */
@Data
@TableName("agent_kb_chunk")
public class AgentKbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docKey;

    private Long documentId;

    /** NULL = 平台全局 */
    private Long merchantId;

    private String title;

    /** PLAYBOOK / FAQ / PRODUCT / DECISION */
    private String sourceType;

    private String content;

    private String embedding;

    private Integer dimension;

    private LocalDateTime createdAt;
}
