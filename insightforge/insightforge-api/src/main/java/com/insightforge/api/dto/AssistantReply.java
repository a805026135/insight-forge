package com.insightforge.api.dto;

import java.util.List;

/** AI 运营助手回复：文本回答 + 可执行动作（商家批准后执行）。 */
public record AssistantReply(
        String answer,
        String suggestionType,
        String suggestionDetail,
        List<AssistantAction> actions
) {
}
