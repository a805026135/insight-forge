package com.insightforge.agent.core.runtime;

/**
 * 运行中暂停信号：InterruptGate 检测到暂停/取消时抛出，
 * 运行时在阶段边界保存 checkpoint 后优雅停止（状态 PAUSED，可恢复）。
 */
public class InterruptSignal extends RuntimeException {

    private final boolean cancelled;

    public InterruptSignal(String message, boolean cancelled) {
        super(message);
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
