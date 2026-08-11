package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.AgentCoreTestConfig;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import com.insightforge.agent.core.runtime.CheckpointService;
import com.insightforge.agent.core.runtime.InterruptGate;
import com.insightforge.agent.core.runtime.TerminationReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * checkpoint 暂停/继续：先 pause 再 plan（显式 runId）→ 在阶段边界 PAUSED 并保存 checkpoint；
 * resume 后从断点继续并成功完成，checkpoint 清理。
 */
@SpringBootTest(classes = AgentCoreTestConfig.class)
@ActiveProfiles("test")
class CheckpointPauseResumeTest {

    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @Autowired
    private InterruptGate interruptGate;

    @Autowired
    private CheckpointService checkpointService;

    @Test
    void pauseThenResumeCompletesAndCleansCheckpoint() {
        String runId = "pause-resume-test-1";
        AgentTask task = new AgentTask(1L, "为活动 1 制定秒杀 1 控盘预案", Map.of());

        interruptGate.pause(runId);
        PlanningResult paused = orchestrator.plan(task, runId);
        assertEquals(AgentRunStatus.PAUSED, paused.status());
        assertEquals(TerminationReason.INTERRUPTED, paused.trace().terminationReason());
        assertTrue(checkpointService.load(runId).isPresent(), "暂停后必须保存 checkpoint");

        interruptGate.resume(runId);
        PlanningResult resumed = orchestrator.resume(runId);
        assertEquals(AgentRunStatus.SUCCESS, resumed.status());
        assertEquals(TerminationReason.COMPLETED, resumed.trace().terminationReason());
        assertTrue(checkpointService.load(runId).isEmpty(), "完成后必须清理 checkpoint");
    }

    @Test
    void cancelBeforeRunLeavesNoCheckpoint() {
        String runId = "cancel-test-1";
        AgentTask task = new AgentTask(1L, "为活动 2 制定秒杀 2 控盘预案", Map.of());

        interruptGate.cancel(runId);
        PlanningResult cancelled = orchestrator.plan(task, runId);
        assertEquals(AgentRunStatus.PAUSED, cancelled.status());
        assertEquals(TerminationReason.USER_CANCELLED, cancelled.trace().terminationReason());
        assertFalse(checkpointService.load(runId).isPresent(), "取消后 checkpoint 应删除");
        interruptGate.resume(runId);
    }
}
