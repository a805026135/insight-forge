package com.insightforge.api.dto;

import java.math.BigDecimal;

/** 商家视角的商品视图（含归属商家名，供店铺/工作台展示）。 */
public record MerchantProductView(
        Long id,
        String name,
        String sku,
        BigDecimal costPrice,
        BigDecimal marketPrice,
        BigDecimal seckillPrice,
        String imageUrl,
        String category,
        String description,
        String status,
        Long merchantId,
        String merchantName,
        Integer stock,
        Integer sold
) {
}
