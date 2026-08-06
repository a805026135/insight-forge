package com.insightforge.agent.core.tool;

import com.insightforge.agent.core.memory.ArtifactStore;
import com.insightforge.agent.core.memory.MemoryStore;

/**
 * 工具执行时的上下文。runtime 在调用每个工具前构造，携带 trace 归因与共享存储句柄。
 */
public class ToolContext {

    private final String traceId;
    private final Long activityId;
    private final ArtifactStore artifactStore;
    private final MemoryStore memoryStore;

    public ToolContext(String traceId, Long activityId, ArtifactStore artifactStore, MemoryStore memoryStore) {
        this.traceId = traceId;
        this.activityId = activityId;
        this.artifactStore = artifactStore;
        this.memoryStore = memoryStore;
    }

    public String traceId() {
        return traceId;
    }

    public Long activityId() {
        return activityId;
    }

    public ArtifactStore artifactStore() {
        return artifactStore;
    }

    public MemoryStore memoryStore() {
        return memoryStore;
    }
}
