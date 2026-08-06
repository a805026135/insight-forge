package com.insightforge.agent.core;

import com.insightforge.agent.core.runtime.BudgetExceededException;
import com.insightforge.agent.core.runtime.BudgetGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 预算闸门单元测试（纯逻辑，不依赖 Spring）：token 超限 / 延迟超限分别触发优雅停止。
 */
class BudgetGateTest {

    @Test
    void throwsWhenTokensExceeded() {
        BudgetGate gate = new BudgetGate(10, 0.10, 30_000, 12, 3, 0.0005);
        gate.reset();
        gate.addTokens(5, 5);          // 累计 10，未超
        assertDoesNotThrow(gate::assertWithinBudget);
        gate.addTokens(1, 0);          // 累计 11，超 10
        assertThrows(BudgetExceededException.class, gate::assertWithinBudget);
    }

    @Test
    void throwsWhenLatencyExceeded() throws InterruptedException {
        // 延迟上限设为 1ms，reset 后睡 5ms 再断言
        BudgetGate gate = new BudgetGate(1_000_000, 0.10, 1, 12, 3, 0.0005);
        gate.reset();
        Thread.sleep(5);
        assertThrows(BudgetExceededException.class, gate::assertWithinBudget);
    }
}
