package com.insightforge.agent.core.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库（RAG）配置（Phase 19）。
 */
@ConfigurationProperties(prefix = "insightforge.agent.knowledge-base")
public class KnowledgeBaseProperties {

    /** 总开关：false 时服务可用但不检索 */
    private boolean enabled = true;

    /** 单块最大字符数 */
    private int maxChunkChars = 400;

    /** 相邻切块重叠字符数 */
    private int overlapChars = 50;

    /** 检索注入 prompt 的 topK */
    private int topK = 4;

    /** 命中置信度阈值：低于该相似度视为不可靠，不算命中（防幻觉第 5 道防线） */
    private double minScore = 0.1;

    /** 启动时自动入库平台手册/FAQ/商品/决策 */
    private boolean seedOnStartup = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    public void setMaxChunkChars(int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
    }

    public int getOverlapChars() {
        return overlapChars;
    }

    public void setOverlapChars(int overlapChars) {
        this.overlapChars = overlapChars;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public boolean isSeedOnStartup() {
        return seedOnStartup;
    }

    public void setSeedOnStartup(boolean seedOnStartup) {
        this.seedOnStartup = seedOnStartup;
    }
}
