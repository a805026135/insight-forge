package com.insightforge.agent.core.memory;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨 Agent 产物仓库（内存实现）。
 * Orchestrator 把各 Agent 产出的 Artifact 放这里，下游 Agent 按 id 取用——对应设计文档的「消息总线传结构化 Artifact」。
 * 生产可换成 Redis / DB 持久化（带 trace_id 维度），Phase 7 先用内存版跑通链路。
 */
@Component
public class ArtifactStore {

    private final Map<String, Artifact> store = new ConcurrentHashMap<>();

    public void put(Artifact artifact) {
        store.put(artifact.id(), artifact);
    }

    public Artifact get(String id) {
        return store.get(id);
    }

    public Map<String, Artifact> all() {
        return Map.copyOf(store);
    }
}
