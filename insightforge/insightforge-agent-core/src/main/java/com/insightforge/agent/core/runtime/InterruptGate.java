package com.insightforge.agent.core.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中暂停/继续门（Phase 19d）：内存标志位，运行循环在每个阶段边界
 * 调用 {@link #throwIfPaused(String)}，被暂停则抛 {@link InterruptSignal}，
 * 由运行时保存 checkpoint 后停止；{@link #resume} 清标志，从断点继续。
 * 生产可换成 Redis 键（runId → paused/cancelled），接口不变。
 */
@Component
public class InterruptGate {

    public static final String PAUSED = "PAUSED";
    public static final String CANCELLED = "CANCELLED";

    private final Map<String, String> signals = new ConcurrentHashMap<>();

    public void pause(String runId) {
        signals.put(runId, PAUSED);
    }

    public void cancel(String runId) {
        signals.put(runId, CANCELLED);
    }

    public void resume(String runId) {
        signals.remove(runId);
    }

    public boolean isPaused(String runId) {
        return PAUSED.equals(signals.get(runId));
    }

    /** 阶段边界检查：被暂停/取消则抛 InterruptSignal（由运行时保存 checkpoint 后优雅停止）。 */
    public void throwIfPaused(String runId) {
        String s = signals.get(runId);
        if (s != null) {
            throw new InterruptSignal("run " + runId + " 被" + (CANCELLED.equals(s) ? "取消" : "暂停"),
                    CANCELLED.equals(s));
        }
    }
}
