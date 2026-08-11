package com.insightforge.agent.core.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.AgentCheckpoint;
import com.insightforge.persistence.mapper.AgentCheckpointMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 运行 checkpoint 存取（Phase 19d）：暂停时保存任务/阶段/中间产物，
 * resume 从断点继续；成功或取消后删除。
 */
@Service
public class CheckpointService {

    private final AgentCheckpointMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CheckpointService(AgentCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    public void save(String runId, Long activityId, String phase, int stepIndex,
                     AgentTask task, Map<String, Object> contextJson) {
        AgentCheckpoint cp = mapper.selectById(runId);
        if (cp == null) {
            cp = new AgentCheckpoint();
            cp.setRunId(runId);
            cp.setCreatedAt(LocalDateTime.now());
        }
        cp.setActivityId(activityId);
        cp.setPhase(phase);
        cp.setStepIndex(stepIndex);
        cp.setStatus("PAUSED");
        cp.setTaskJson(toJson(taskSnapshot(task)));
        cp.setContextJson(toJson(contextJson));
        cp.setUpdatedAt(LocalDateTime.now());
        if (cp.getCreatedAt() == null) {
            cp.setCreatedAt(LocalDateTime.now());
        }
        if (mapper.selectById(runId) == null) {
            mapper.insert(cp);
        } else {
            mapper.updateById(cp);
        }
    }

    public Optional<AgentCheckpoint> load(String runId) {
        return Optional.ofNullable(mapper.selectById(runId));
    }

    public void delete(String runId) {
        mapper.deleteById(runId);
    }

    public AgentTask readTask(AgentCheckpoint cp) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = fromJson(cp.getTaskJson());
        Long activityId = m.get("activityId") instanceof Number n ? n.longValue() : null;
        String goal = String.valueOf(m.getOrDefault("goal", ""));
        Object ctx = m.get("context");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = ctx instanceof Map ? (Map<String, Object>) ctx : Map.of();
        return new AgentTask(activityId, goal, context);
    }

    public Map<String, Object> readContext(AgentCheckpoint cp) {
        return fromJson(cp.getContextJson());
    }

    private Map<String, Object> taskSnapshot(AgentTask task) {
        return Map.of(
                "activityId", task.activityId() == null ? 0L : task.activityId(),
                "goal", task.goal() == null ? "" : task.goal(),
                "context", task.context() == null ? Map.of() : task.context());
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("checkpoint 序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("checkpoint 反序列化失败", e);
        }
    }
}
