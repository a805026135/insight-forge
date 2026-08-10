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

    /** 购物车存商品（Phase 18）：普通商品直接按商品购买，秒杀商品仍可关联活动 SKU */
    private Long productId;

    private Integer qty;
    private LocalDateTime createdAt;
}
