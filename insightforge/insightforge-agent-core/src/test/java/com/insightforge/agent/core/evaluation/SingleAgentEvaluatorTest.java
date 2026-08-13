package com.insightforge.agent.core.evaluation;

import com.insightforge.agent.core.model.ModelClient;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.agent.core.planning.DslCompiler;
import com.insightforge.agent.core.planning.GuardrailPolicyAgent;
import com.insightforge.agent.core.planning.IndependentCritic;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleAgentEvaluatorTest {
    private SingleAgentEvaluator evaluator(String pricing) {
        ModelClient client = request -> ModelResponse.answer("""
                {"selector":{"includeSkus":[101],"stockRatioThreshold":0.2},
                 "pricer":{"repriceTo":%s,"costFloor":5999.0},
                 "warmup":{"reallocateAmount":50,"targetSegment":"seg_hot"},
                 "risk":{"alertOnVelocityDrop":true}}
                """.formatted(pricing), 100, 50, 12);
        IndependentCritic critic = new IndependentCritic();
        ReflectionTestUtils.setField(critic, "requireHumanReview", false);
        return new SingleAgentEvaluator(client, new GuardrailPolicyAgent(), new DslCompiler(), critic,
                "test-model", new BigDecimal("0.0005"));
    }

    @Test
    void acceptsCompleteSafeSinglePromptPlanAndCountsUsage() {
        var result = evaluator("6598.9").run(1, "safe", false);
        assertEquals("SUCCESS", result.status());
        assertEquals(150, result.tokens());
        assertTrue(result.costUsd().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void rejectsBelowCostSinglePromptPlan() {
        var result = evaluator("5000").run(1, "unsafe", true);
        assertEquals("CORRECT_FAILURE", result.status());
        assertTrue(result.errors().stream().anyMatch(x -> x.contains("成本")));
    }

    @Test
    void malformedModelOutputFailsInsteadOfUsingDeterministicFallback() {
        ModelClient client = request -> ModelResponse.answer("not-json", 1, 1, 1);
        IndependentCritic critic = new IndependentCritic();
        var evaluator = new SingleAgentEvaluator(client, new GuardrailPolicyAgent(), new DslCompiler(), critic,
                "test-model", new BigDecimal("0.0005"));
        assertEquals("FAILED", evaluator.run(1, "bad", false).status());
    }
}
