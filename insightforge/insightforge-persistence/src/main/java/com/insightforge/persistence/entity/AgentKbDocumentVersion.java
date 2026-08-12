package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_kb_document_version")
public class AgentKbDocumentVersion {
    @TableId(type = IdType.AUTO) private Long id;
    private String docKey;
    private Integer version;
    private String title;
    private String sourceType;
    private Long merchantId;
    private String content;
    private String checksum;
    private LocalDateTime createdAt;
}
