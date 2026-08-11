package com.insightforge.api.dto;

import java.util.List;

/**
 * 买家客服回复：文本回答 + 可确认的退款草稿（真实提交由用户触发）。
 */
public record BuyerAssistantReply(
        String answer,
        List<AfterSaleDraft> drafts
) {
}
