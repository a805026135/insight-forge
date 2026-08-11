package com.insightforge.api.dto;

import java.util.List;

/**
 * 客服意图识别结果（Phase 19g）：无命中 → needHuman=true（转人工/商家）；
 * 退款意图 → drafts；merchants 为可联系商家列表。
 */
public record AssistantIntent(
        boolean needHuman,
        List<MerchantContact> merchants,
        List<AfterSaleDraft> drafts,
        List<ProductCandidate> productCandidates,
        List<OrderCandidate> orderCandidates
) {
}
