package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 控盘动作审计（Phase 14.1）。
 *
 * AI 调拨 / 调价等「写共享库存」的动作必须落这条表，作为与 Redis 扣减/订单台账并列的
 * 第三类事实源：结算对账据此知道「DB 库存里有多少是 AI 调进来的」，不会误判为漂移。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("audit_log")
public class AuditLog extends BaseEntity {

    /** 活动 id */
    private Long activityId;

    /** activity_sku.id（与秒杀热路径的 skuId 口径一致） */
    private Long skuId;

    /** REALLOCATE / REPRICE */
    private String actionType;

    /** 调拨数量（REPRICE 时为 0） */
    private Integer amount;

    /** 人话摘要（含归因可选） */
    private String detail;

    /** 动作时间 */
    private LocalDateTime createdAt;
}
