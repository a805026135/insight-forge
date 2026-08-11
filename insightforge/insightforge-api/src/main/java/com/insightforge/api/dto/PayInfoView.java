package com.insightforge.api.dto;

import java.math.BigDecimal;

/**
 * 支付确认页信息（Phase 19j）：扫码后在手机端展示订单/商品/金额并确认。
 */
public record PayInfoView(
        String orderId,
        String productName,
        BigDecimal amount,
        String status
) {
}
