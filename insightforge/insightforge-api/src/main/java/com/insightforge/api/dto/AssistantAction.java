package com.insightforge.api.dto;

/**
 * Agent 建议的可执行动作（待商家批准后执行）：
 * ADD_STOCK 加库存 / TOGGLE_SHELF 上下架 / REPRICE 调价。
 */
public record AssistantAction(
        String type,
        Long productId,
        String productName,
        Integer amount,
        java.math.BigDecimal price,
        String description
) {
}
