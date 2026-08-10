package com.insightforge.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建活动请求 DTO（给 POST /api/activity 用）。
 *
 * 一个活动可以挂多个 SKU（商品），每个 SKU 需要指定秒杀价和库存。
 * 如果 productId 为空则按 productName 自动创建新商品。
 */
public record CreateActivityRequest(
        String name,
        BigDecimal targetGmv,
        BigDecimal targetRoi,
        LocalDateTime startAt,
        LocalDateTime endAt,
        List<SkuConfig> skus
) {
    /**
     * 单个 SKU 配置：可关联已有商品（productId）或自动创建新商品（productName + skuCode + costPrice）。
     */
    public record SkuConfig(
            Long productId,        // 关联已有商品；为 null 则自动创建
            String productName,    // 新商品名（productId 为 null 时必填）
            String skuCode,        // 新商品编码
            BigDecimal costPrice,  // 新商品成本价
            BigDecimal seckillPrice, // 秒杀价
            Integer totalStock     // 初始库存
    ) {
    }
}
