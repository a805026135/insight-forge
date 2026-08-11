package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款/售后工单（Phase 19f）：真实退款闭环——
 * APPLIED（待审核）→ APPROVED（已通过）→ REFUNDED（已退款）
 * APPLIED → REJECTED（已驳回）/ CANCELLED（用户取消）
 */
@Data
@TableName("after_sale")
public class AfterSale {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String orderId;

    private Long skuId;

    /** 归属商家（商家侧售后处理按店铺隔离） */
    private Long merchantId;

    private String productName;

    private Integer qty;

    private BigDecimal amount;

    private String reason;

    /** 申诉理由（REJECTED 后可发起） */
    private String appealReason;

    private LocalDateTime appealAt;

    /** 处理方（merchant/{id} 或 platform） */
    private String handledBy;

    private LocalDateTime handledAt;

    /** 商家/平台处理备注 */
    private String note;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
