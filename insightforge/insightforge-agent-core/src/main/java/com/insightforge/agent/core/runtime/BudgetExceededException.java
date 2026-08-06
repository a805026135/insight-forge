package com.insightforge.agent.core.runtime;

/**
 * 预算/护栏闸门异常（设计文档 §1 停止条件）。
 * 由 {@link BudgetGate} 在 token / 成本 / 延迟超限时抛出；
 * runtime 捕获后优雅终止为 CORRECT_FAILURE(ESCALATE)，而不是硬撑到崩溃。
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
