package com.insightforge.api.observability.dto;

import java.math.BigDecimal;

/**
 * 活动下某个 SKU 的完整视图（给前端秒杀商品列表用）。
 *
 * 把 activity_sku（库存/价格）和 product（商品名/编码/成本价）拼在一起，
 * 前端无需再硬编码商品名和价格。
 */
public record SkuView(
        Long skuId,           // = product.id = activity_sku.sku_id
        Long activitySkuId,   // = activity_sku.id
        Long activityId,
        String productName,
        String skuCode,
        String productImage,
        BigDecimal seckillPrice,
        BigDecimal marketPrice,
        BigDecimal costPrice,
        Integer totalStock,
        Integer stock,
        Integer sold
) {
}
