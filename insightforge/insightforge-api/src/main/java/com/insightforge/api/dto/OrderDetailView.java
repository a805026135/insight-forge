package com.insightforge.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单详情视图：订单头 + 多商品明细 + 收货快照。 */
public record OrderDetailView(
        String orderId,
        String status,
        BigDecimal totalAmount,
        String receiver,
        String phone,
        String addressSnapshot,
        LocalDateTime createdAt,
        LocalDateTime shippedAt,
        List<OrderItemView> items
) {
}
