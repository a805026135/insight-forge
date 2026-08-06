package com.insightforge.agent.core.runtime;

import java.util.List;

/**
 * Critic 的结构化裁决（设计文档 §4）。
 * 这是「独立验证器」的最小产出：通过/不通过 + 理由 + 置信度 + 严重度。
 * 失败裁决会被 runtime 用来触发反思重试——绝不让原 Agent 自评「我做对了吗」。
 */
public class CriticVerdict {

    private final boolean pass;
    private final List<String> reasons;
    private final double confidence;
    private final String severity;

    private CriticVerdict(boolean pass, List<String> reasons, double confidence, String severity) {
        this.pass = pass;
        this.reasons = reasons;
        this.confidence = confidence;
        this.severity = severity;
    }

    public static CriticVerdict pass() {
        return new CriticVerdict(true, List.of(), 0.99, "NONE");
    }

    public static CriticVerdict fail(List<String> reasons, double confidence, String severity) {
        return new CriticVerdict(false, reasons, confidence, severity);
    }

    public boolean passed() {
        return pass;
    }

    public List<String> reasons() {
        return reasons;
    }

    public double confidence() {
        return confidence;
    }

    public String severity() {
        return severity;
    }
}
