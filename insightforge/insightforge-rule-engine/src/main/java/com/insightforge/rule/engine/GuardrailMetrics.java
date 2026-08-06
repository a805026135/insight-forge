package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 护栏命中埋点 + 风险分级计数（评估方案 §5 L3：护栏命中率、0 超卖 / 0 违规是健康底线）。
 *
 * 大白话：规则引擎每开出一张工单，就在这儿记一笔——调价几次、调拨几次、告警几次，
 * 以及每张工单的风险等级。命中不等于坏事（说明护栏在起作用），但「越界必须为 0」：
 * 真正的红线是 0 超卖、0 违规。影子模式的对比结果也在这儿累计（shadowMismatches 用于上线前安全评估）。
 */
@Component
public class GuardrailMetrics {

    private final AtomicLong repriceHits = new AtomicLong();
    private final AtomicLong reallocateHits = new AtomicLong();
    private final AtomicLong alertHits = new AtomicLong();
    private final AtomicLong lowRisk = new AtomicLong();
    private final AtomicLong mediumRisk = new AtomicLong();
    private final AtomicLong highRisk = new AtomicLong();
    private final AtomicLong shadowChecks = new AtomicLong();
    private final AtomicLong shadowMismatches = new AtomicLong();

    /** 每次规则引擎产出一张工单就调用一次。 */
    public void hit(ControlInstruction ci) {
        Action a = ci.getAction();
        if (a == Action.REPRICE) {
            repriceHits.incrementAndGet();
        } else if (a == Action.REALLOCATE) {
            reallocateHits.incrementAndGet();
        } else if (a == Action.ALERT) {
            alertHits.incrementAndGet();
        }

        RiskLevel r = ci.getRiskLevel();
        if (r == RiskLevel.LOW) {
            lowRisk.incrementAndGet();
        } else if (r == RiskLevel.MEDIUM) {
            mediumRisk.incrementAndGet();
        } else if (r == RiskLevel.HIGH) {
            highRisk.incrementAndGet();
        }
    }

    /** 影子模式：记录一次对比，matched=false 计一次不一致。 */
    public void recordShadow(boolean matched) {
        shadowChecks.incrementAndGet();
        if (!matched) {
            shadowMismatches.incrementAndGet();
        }
    }

    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
                repriceHits.get(), reallocateHits.get(), alertHits.get(),
                lowRisk.get(), mediumRisk.get(), highRisk.get(),
                shadowChecks.get(), shadowMismatches.get());
    }

    public record MetricsSnapshot(long repriceHits, long reallocateHits, long alertHits,
                                  long lowRisk, long mediumRisk, long highRisk,
                                  long shadowChecks, long shadowMismatches) {
    }
}
