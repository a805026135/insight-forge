package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家自定义知识文档（Phase 19e）：商家上传自己的 SOP/手册/话术，
 * 全文落库（可展示/审计），doc_key 关联向量库切块，商家级隔离。
 */
@Data
@TableName("agent_kb_custom_doc")
public class AgentKbCustomDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String docKey;

    private String title;

    private String content;

    private LocalDateTime createdAt;
}
