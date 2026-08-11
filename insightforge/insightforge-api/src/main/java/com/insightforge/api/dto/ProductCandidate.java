package com.insightforge.api.dto;

/**
 * 商品候选（Phase 19h）：平台客服无法直接锁定商家时，让用户选择具体商品，
 * 选定后按该商家公开知识回答。
 */
public record ProductCandidate(
        Long productId,
        String productName,
        Long merchantId,
        String merchantName
) {
}
