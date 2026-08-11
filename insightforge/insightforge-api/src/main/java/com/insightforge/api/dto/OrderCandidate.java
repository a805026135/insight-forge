package com.insightforge.api.dto;

import java.math.BigDecimal;

/**
 * 订单候选（Phase 19i）：电话/售后类问题先让用户确认是哪一笔订单，
 * 再按对应商家知识回答，避免把"平台电话"误当成所有问题的答案。
 */
public record OrderCandidate(
        String orderId,
        Long merchantId,
        String merchantName,
        String productName,
        BigDecimal amount
) {
}
