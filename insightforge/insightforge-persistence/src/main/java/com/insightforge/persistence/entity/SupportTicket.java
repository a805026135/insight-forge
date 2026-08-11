package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 咨询工单（Phase 19g）：平台客服无法回答时，买家发起咨询 → 商家端回复。
 * 状态：OPEN → REPLIED → CLOSED。
 */
@Data
@TableName("support_ticket")
public class SupportTicket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long merchantId;

    private String question;

    private String answer;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime repliedAt;
}
