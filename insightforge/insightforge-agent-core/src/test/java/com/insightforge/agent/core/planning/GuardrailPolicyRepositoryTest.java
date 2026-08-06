package com.insightforge.agent.core.planning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 护栏策略仓库测试：版本化持久化（v1→v2）+ 热更新缓存（guardrail:dsl:{aid}）。
 */
@SpringBootTest(classes = com.insightforge.agent.core.AgentCoreTestConfig.class)
@ActiveProfiles("test")
class GuardrailPolicyRepositoryTest {

    @Autowired
    private GuardrailPolicyRepository repository;

    private GuardrailRule okRule() {
        return new GuardrailRule("r1", Metric.PRICE_GAP, Operator.GT, new BigDecimal("0.15"),
                Action.REPRICE, Map.of("repriceTo", new BigDecimal("6299"), "costFloor", new BigDecimal("5999")));
    }

    @Test
    void publishVersionsSequentiallyAndCaches() {
        GuardrailPolicy p1 = new GuardrailPolicy(77L, "v?", List.of(okRule()));
        GuardrailPolicy pub1 = repository.publish(p1);
        assertEquals("v1", pub1.version());

        GuardrailPolicy p2 = new GuardrailPolicy(77L, "v?", List.of(okRule()));
        GuardrailPolicy pub2 = repository.publish(p2);
        assertEquals("v2", pub2.version());

        // getLatest 返回最新版（且命中缓存）
        GuardrailPolicy latest = repository.getLatest(77L);
        assertNotNull(latest);
        assertEquals("v2", latest.version());
    }

    @Test
    void getLatestMissingReturnsNull() {
        assertNull(repository.getLatest(99999L));
    }
}
