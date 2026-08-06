package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控盘指令执行器（真正动库存/价格的地方，走同一套锁）。
 *
 * 大白话：规则引擎开出的工单（REPRICE / REALLOCATE / ALERT），由这里落地执行：
 * - ALERT：只记埋点，不写共享状态（最安全）；
 * - REPRICE：走锁更新价格（教学用内存价表，生产接 Redis/DB）；
 * - REALLOCATE：走锁 + 只建新段 + pending 预留（StockSegmentService）；
 * 锁抢不到（0ms Fail-Fast）→ 立即放弃这一轮，绝不阻塞用户热路径（用户流量优先的硬保障）。
 */
@Service
public class ControlInstructionExecutor {

    private final LockService lockService;
    private final StockSegmentService segmentService;
    private final GuardrailMetrics metrics;

    // 教学内存价表（生产接 Redis/DB）
    private final ConcurrentHashMap<Long, Double> priceTable = new ConcurrentHashMap<>();

    public ControlInstructionExecutor(LockService lockService,
                                      StockSegmentService segmentService,
                                      GuardrailMetrics metrics) {
        this.lockService = lockService;
        this.segmentService = segmentService;
        this.metrics = metrics;
    }

    /** 执行一批指令，返回每条的执行结果（成功 / 告警 / 锁失败）。 */
    public List<ExecutionResult> executeAll(List<ControlInstruction> instructions) {
        List<ExecutionResult> results = new ArrayList<>();
        for (ControlInstruction ci : instructions) {
            results.add(execute(ci));
        }
        return results;
    }

    public ExecutionResult execute(ControlInstruction ci) {
        metrics.hit(ci);
        String lockKey = lockKey(ci.getActivityId(), ci.getSkuId());
        return switch (ci.getAction()) {
            case ALERT -> ExecutionResult.alerted(ci);
            case REPRICE -> {
                if (!lockService.tryLock(lockKey)) {
                    yield ExecutionResult.lockFailed(ci);
                }
                try {
                    double newPrice = ci.doubleParam("repriceTo", priceTable.getOrDefault(ci.getSkuId(), 0d));
                    priceTable.put(ci.getSkuId(), newPrice);
                    yield ExecutionResult.applied(ci);
                } finally {
                    lockService.unlock(lockKey);
                }
            }
            case REALLOCATE -> {
                if (!lockService.tryLock(lockKey)) {
                    yield ExecutionResult.lockFailed(ci);
                }
                try {
                    int amount = ci.intParam("amount", 0);
                    StockSegmentService.AllocResult ar =
                            segmentService.allocateNewSegment(ci.getActivityId(), ci.getSkuId(), amount);
                    // 两阶段预留：这里演示「确认」阶段（真实场景由下游确认事件触发 confirmPending）
                    segmentService.confirmPending(ci.getActivityId(), ci.getSkuId(), amount);
                    yield ExecutionResult.allocated(ci, ar.newSegment(), amount);
                } finally {
                    lockService.unlock(lockKey);
                }
            }
            default -> ExecutionResult.ignored(ci);
        };
    }

    private String lockKey(long activityId, long skuId) {
        return "a:" + activityId + ":s:" + skuId;
    }

    public Double getPrice(long skuId) {
        return priceTable.get(skuId);
    }

    public record ExecutionResult(Action action, boolean applied, String detail, ControlInstruction instruction) {
        static ExecutionResult alerted(ControlInstruction ci) {
            return new ExecutionResult(ci.getAction(), true, "ALERT only (no write)", ci);
        }

        static ExecutionResult applied(ControlInstruction ci) {
            return new ExecutionResult(ci.getAction(), true, "REPRICE applied", ci);
        }

        static ExecutionResult allocated(ControlInstruction ci, int seg, int amount) {
            return new ExecutionResult(ci.getAction(), true, "REALLOCATE seg_" + seg + " +" + amount, ci);
        }

        static ExecutionResult lockFailed(ControlInstruction ci) {
            return new ExecutionResult(ci.getAction(), false, "lock not acquired (Fail-Fast, user priority)", ci);
        }

        static ExecutionResult ignored(ControlInstruction ci) {
            return new ExecutionResult(ci.getAction(), false, "ignored", ci);
        }
    }
}
