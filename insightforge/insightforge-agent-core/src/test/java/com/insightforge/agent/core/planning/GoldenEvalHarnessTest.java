package com.insightforge.agent.core.planning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线评估 Harness 测试：跑 golden 评估集（~10 条正向 case），逐条核对验收准则。
 * 这是 Phase 8「最小 golden 评估集」的落地，也是 Phase 11 评估闭环的雏形。
 */
@SpringBootTest(classes = com.insightforge.agent.core.AgentCoreTestConfig.class)
@ActiveProfiles("test")
class GoldenEvalHarnessTest {

    @Autowired
    private GoldenEvalHarness harness;

    @Test
    void goldenCasesAllAccepted() throws Exception {
        List<GoldenEvalHarness.GoldenResult> results = harness.runAll();

        // 评估集规模（与设计文档「seed ~10」一致）
        assertEquals(10, results.size(), "golden 评估集应有 10 条");

        for (GoldenEvalHarness.GoldenResult r : results) {
            assertTrue(r.accepted(),
                    "case [" + r.c().name() + "] 未通过验收：status=" + r.status());
        }
    }
}
