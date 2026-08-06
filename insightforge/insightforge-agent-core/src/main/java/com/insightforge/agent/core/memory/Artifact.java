package com.insightforge.agent.core.memory;

import java.time.LocalDateTime;

/**
 * 跨 Agent 共享的结构化产物（设计文档 §5.2 跨 Agent 共享 Artifact）。
 * 例如：选品清单 / 定价方案 / 护栏 DSL / 运营报告。不把一切塞进 prompt，经 ArtifactStore 传递。
 */
public record Artifact(String id, String type, Object content, LocalDateTime createdAt) {

    public static Artifact of(String id, String type, Object content) {
        return new Artifact(id, type, content, LocalDateTime.now());
    }
}
