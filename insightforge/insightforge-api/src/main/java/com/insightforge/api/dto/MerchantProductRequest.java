package com.insightforge.api.dto;

import java.math.BigDecimal;

/** 商家创建/更新商品请求体。 */
public record MerchantProductRequest(
        String name,
        String sku,
        BigDecimal costPrice,
        BigDecimal marketPrice,
        String imageUrl,
        String category,
        String description
) {
}
