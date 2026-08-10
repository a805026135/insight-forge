package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 知识库文档（Phase 19）：RAG 的文档级元数据。
 * doc_key 幂等（平台手册/FAQ/商品目录/决策记录），重新入库即版本 +1。
 * merchant_id 为 NULL 表示平台全局知识，否则仅归属商家可见。
 */
@Data
@TableName("agent_kb_document")
public class AgentKbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等入库键：platform/playbook、merchant/{id}/products ... */
    private String docKey;

    private String title;

    /** PLAYBOOK / FAQ / PRODUCT / DECISION */
    private String sourceType;

    /** NULL = 平台全局 */
    private Long merchantId;

    private Integer version;

    private Integer chunkCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
