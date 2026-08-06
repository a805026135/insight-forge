package com.insightforge.agent.core.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 预算闸门（Harness 护栏第一道，设计文档 §1 停止条件）。
 *
 * 控制单次运行的「花钱」与「耗时」上限：token 累计、成本估算、墙钟延迟。
 * 超限即抛 {@link BudgetExceededException}，由 runtime 优雅降级为正确失败（ESCALATE），
 * 绝不为了「跑完」而无节制地烧 token / 拖延迟。
 */
@Component
public class BudgetGate {

    private final int maxTokens;
    private final double maxCostUsd;
    private final long maxLatencyMs;
    private final int maxSteps;
    private final int maxRetry;
    private final double tokenPriceUsdPer1k;

    private int tokens;
    private long startTs;

    public BudgetGate(
            @Value("${insightforge.agent.budget.max-tokens:20000}") int maxTokens,
            @Value("${insightforge.agent.budget.max-cost-usd:0.10}") double maxCostUsd,
            @Value("${insightforge.agent.budget.max-latency-ms:30000}") long maxLatencyMs,
            @Value("${insightforge.agent.budget.max-steps:12}") int maxSteps,
            @Value("${insightforge.agent.budget.max-retry:3}") int maxRetry,
            @Value("${insightforge.agent.budget.token-price-usd-per-1k:0.0005}") double tokenPriceUsdPer1k) {
        this.maxTokens = maxTokens;
        this.maxCostUsd = maxCostUsd;
        this.maxLatencyMs = maxLatencyMs;
        this.maxSteps = maxSteps;
        this.maxRetry = maxRetry;
        this.tokenPriceUsdPer1k = tokenPriceUsdPer1k;
    }

    /** 每个 run 开始时调用，清零累计并打时间戳。 */
    public void reset() {
        this.tokens = 0;
        this.startTs = System.nanoTime();
    }

    /** 每步开始前调用；超限抛异常（runtime 据此优雅停止）。 */
    public void assertWithinBudget() {
        if (tokens > maxTokens) {
            throw new BudgetExceededException("token 预算超限: " + tokens + " > " + maxTokens);
        }
        long elapsedMs = (System.nanoTime() - startTs) / 1_000_000;
        if (elapsedMs > maxLatencyMs) {
            throw new BudgetExceededException("延迟预算超限: " + elapsedMs + "ms > " + maxLatencyMs + "ms");
        }
    }

    public void addTokens(int tokenIn, int tokenOut) {
        this.tokens += (tokenIn + tokenOut);
    }

    public int getTokens() {
        return tokens;
    }

    /** 当前累计成本（美元，粗略估算）。 */
    public double costUsd() {
        return tokens * tokenPriceUsdPer1k / 1000.0;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getMaxRetry() {
        return maxRetry;
    }
}
