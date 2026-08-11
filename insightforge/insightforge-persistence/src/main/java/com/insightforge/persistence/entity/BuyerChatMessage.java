package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家客服问答历史（Phase 19f）：用户级隔离，跨会话可见，可审计。
 */
@Data
@TableName("buyer_chat_message")
public class BuyerChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** user / assistant */
    private String role;

    private String content;

    private LocalDateTime createdAt;
}
