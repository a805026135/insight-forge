package com.insightforge.agent.core.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线评估 Harness（设计文档 §6：最小评估集 + 回归评估集）。
 *
 * 它把「golden 评估集」跑一遍：每条 case 是一个真实规划任务（不同品类/目标/约束），
 * 经 MultiAgentOrchestrator 产出 DSL，再核对「验收准则」（是否达到期望终态、DSL 是否含关键动作）。
 * Phase 11 会把每次 CORRECT_FAILURE / 不达标样本沉淀回这个集，形成工程复利。
 *
 * 这里 golden 集是 ~10 条「应成功」的正向 case（演示 harness 跑通 + 防回归）；
 * 负向 case（应 CORRECT_FAILURE）由 DslCompilerTest / MultiAgentOrchestratorRejectTest 覆盖。
 */
@Component
public class GoldenEvalHarness {

    private final MultiAgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GoldenEvalHarness(MultiAgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 加载 golden 评估集（classpath: golden/phase8-cases.json）。 */
    public List<GoldenCase> loadCases() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("golden/phase8-cases.json")) {
            if (is == null) {
                throw new IllegalStateException("golden/phase8-cases.json 未找到");
            }
            return objectMapper.readValue(is, new TypeReference<List<GoldenCase>>() {
            });
        }
    }

    /** 跑完整评估集，逐条核对验收准则，返回每条结果。 */
    public List<GoldenResult> runAll() throws Exception {
        List<GoldenResult> out = new ArrayList<>();
        for (GoldenCase c : loadCases()) {
            AgentTask task = new AgentTask(c.activityId(), c.goal(), null);
            PlanningResult r = orchestrator.plan(task);
            boolean accept = accept(c, r);
            out.add(new GoldenResult(c, r.status(), r.published(), accept, r.trace()));
        }
        return out;
    }

    private boolean accept(GoldenCase c, PlanningResult r) {
        if (!c.expect().equalsIgnoreCase(String.valueOf(r.status()))) {
            return false;
        }
        if (r.status() == AgentRunStatus.SUCCESS) {
            // 验收准则：SUCCESS 必须产出含 REPRICE 与 REALLOCATE 的 DSL（否则控盘不完整）
            boolean hasReprice = r.published().rules().stream().anyMatch(x -> x.action() == Action.REPRICE);
            boolean hasAlloc = r.published().rules().stream().anyMatch(x -> x.action() == Action.REALLOCATE);
            return hasReprice && hasAlloc;
        }
        return true;
    }

    /** 一条评估样本。 */
    public record GoldenCase(String name, Long activityId, String goal, String expect) {
    }

    /** 一条评估结果。 */
    public record GoldenResult(GoldenCase c, AgentRunStatus status, GuardrailPolicy published,
                               boolean accepted, com.insightforge.agent.core.runtime.Trace trace) {
    }
}
