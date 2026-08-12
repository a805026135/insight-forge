package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_prompt_version")
public class AgentPromptVersion {
    @TableId(type = IdType.AUTO) private Long id;
    private String promptKey;
    private String version;
    private String content;
    private String checksum;
    private String status;
    private LocalDateTime createdAt;
}
