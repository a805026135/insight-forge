package com.insightforge.api.dto;

import java.math.BigDecimal;

/** 订单明细视图。 */
public record OrderItemView(
        Long activitySkuId,
        Long productId,
        String productName,
        String productImage,
        Long merchantId,
        String merchantName,
        Integer qty,
        BigDecimal price
) {
}
