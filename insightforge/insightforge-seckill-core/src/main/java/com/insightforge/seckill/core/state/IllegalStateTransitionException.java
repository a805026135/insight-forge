package com.insightforge.seckill.core.state;

/**
 * 非法状态迁转异常（Phase 6）。
 *
 * 大白话：状态机规定了一个对象「现在在哪、下一步能去哪」。如果代码试图让它跳到
 * 一个不被允许的状态（比如活动还是草稿 DRAFT 就想直接关掉 CLOSED，或者订单
 * 还没支付就直接 DONE），我们就抛这个异常，把错误的迁转拦在业务层之外。
 *
 * 为什么要单独建一个异常类？
 *   这样上层（Controller / 定时任务）可以精准 catch 它，返回 409「状态不允许」，
 *   而不是和数据库异常、空指针混在一起。状态机是「业务正确性」的守门员。
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
