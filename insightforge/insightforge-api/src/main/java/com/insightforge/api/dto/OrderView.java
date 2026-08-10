package com.insightforge.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图——在订单实体基础上补齐商品名/图片与活动名，给「我的订单」页展示。
 */
public record OrderView(
        String orderId,
        Long userId,
        Long activityId,
        Long skuId,
        Integer qty,
        BigDecimal price,
        String status,
        LocalDateTime createdAt,
        String productName,
        String productImage,
        String activityName
) {
}
