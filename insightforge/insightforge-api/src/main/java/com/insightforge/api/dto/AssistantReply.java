package com.insightforge.api.dto;

/** AI 运营助手回复：文本回答 + 可选的结构化建议（前端渲染为可操作卡片）。 */
public record AssistantReply(
        String answer,
        String suggestionType,
        String suggestionDetail
) {
}
