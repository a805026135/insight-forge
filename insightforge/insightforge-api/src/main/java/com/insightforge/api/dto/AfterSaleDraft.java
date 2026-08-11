package com.insightforge.api.dto;

import java.math.BigDecimal;

/**
 * AI 预填的退款申请草稿（Phase 19f）：由客服识别退款意图后生成，
 * 前端展示给买家确认，确认后调用 POST /api/buyer/after-sale/apply 真实提交。
 */
public record AfterSaleDraft(
        String orderId,
        BigDecimal amount,
        String reason,
        String description
) {
}
