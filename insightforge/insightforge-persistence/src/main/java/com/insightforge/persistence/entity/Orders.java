package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单（Phase 1 建表）。
 *
 * orderId：订单号。Phase 1 先用 UUID 占位；
 *          Phase 4 会改成 orderId = hash(userId, skuId, activityId)，
 *          配合 Redis SETNX 做幂等（同一用户重复点抢只生成 1 张单）。
 * status：Phase 1 写 "CREATED"，Phase 6 升级成订单状态机
 *         （PRE_CHECK → LOCK_STOCK → CREATE_ORDER → PAY → DONE / CANCEL）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orders")
public class Orders extends BaseEntity {

    /** 订单号（Phase 4 改为幂等 hash） */
    private String orderId;

    /** 用户 id */
    private Long userId;

    /** 活动 id */
    private Long activityId;

    /** SKU id（= product.id） */
    private Long skuId;

    /** 购买数量 */
    private Integer qty;

    /** 订单状态（Phase 6 状态机） */
    private String status;

    /** 下单时间 */
    private LocalDateTime createdAt;
}
