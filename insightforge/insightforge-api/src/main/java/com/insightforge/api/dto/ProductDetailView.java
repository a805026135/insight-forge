package com.insightforge.api.dto;

import java.math.BigDecimal;

/** 用户端商品详情视图：商品 + 商家 + 当前活动秒杀价。 */
public record ProductDetailView(
        Long id,
        String name,
        String sku,
        BigDecimal marketPrice,
        BigDecimal seckillPrice,
        String imageUrl,
        String category,
        String description,
        String status,
        Long merchantId,
        String merchantName,
        BigDecimal merchantRating,
        Long merchantSalesCount,
        Boolean merchantVerified,
        Integer stock,
        Integer sold
) {
}
