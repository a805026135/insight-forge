package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 购物车条目：用户 x 活动SKU x 数量（Phase 17）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cart_item")
public class CartItem extends BaseEntity {
    private Long userId;
    private Long activitySkuId;
    private Integer qty;
    private LocalDateTime createdAt;
}
