package com.insightforge.api.dto;

import java.math.BigDecimal;

/** 购物车条目视图（含商品/商家快照）。 */
public record CartItemView(
        Long id,
        Long activitySkuId,
        Long productId,
        String productName,
        String productImage,
        Long merchantId,
        String merchantName,
        BigDecimal price,
        BigDecimal marketPrice,
        Integer qty,
        Integer stock,
        Integer sold,
        String status
) {
}
