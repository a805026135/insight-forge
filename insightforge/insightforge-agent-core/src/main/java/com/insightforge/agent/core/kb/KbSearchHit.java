package com.insightforge.agent.core.kb;

/**
 * 知识库检索命中（Phase 19）：带标题/来源/得分，注入商家助手 prompt 时作为引用。
 */
public record KbSearchHit(String docKey, String title, String content, String sourceType, double score) {
}
