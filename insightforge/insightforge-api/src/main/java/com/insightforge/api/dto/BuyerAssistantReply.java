package com.insightforge.api.dto;

import java.util.List;

/**
 * 买家客服回复：文本回答 + 是否需转人工（无知识库命中时强制转人工，绝不编造）
 * + 可联系商家 + 可确认的退款草稿。
 */
public record BuyerAssistantReply(
        String answer,
        boolean needHuman,
        List<MerchantContact> merchants,
        List<AfterSaleDraft> drafts,
        List<ProductCandidate> productCandidates,
        List<OrderCandidate> orderCandidates
) {
}
