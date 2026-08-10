package com.insightforge.seckill.core.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单生命周期状态机（Phase 6）。
 *
 * 大白话：一张秒杀订单从「尝试下单」到「完成 / 取消」也有固定的走法，不能随意跳。
 * 本项目出于范围克制（支付链路属于 M7+ 红线，本 Phase 不实现真实支付回调），
 * 把「下单落库」当成订单的第一个持久状态 CREATE_ORDER；PRE_CHECK / LOCK_STOCK 是
 * 内存中的前置瞬态（查商品、Redis 原子锁库存），不落库。但我们仍然把完整迁转矩阵
 * 写清楚，方便后续接支付时直接复用，而不是回头补。
 *
 * 状态流转（箭头 = 允许的迁转）：
 *
 *      PRE_CHECK ──▶ LOCK_STOCK ──▶ CREATE_ORDER ──▶ PAY ──▶ DONE
 *      （前置校验）   （锁库存）      （订单落库）   （支付）  （完成，终态）
 *                            │            │
 *                            └──▶ CANCEL ─┘
 *                              （取消，终态；释放已锁库存）
 *
 * - PRE_CHECK    ：进入秒杀主流程的瞬态（商品存在、活动 LIVE 等前置校验）。
 * - LOCK_STOCK   ：Redis Lua 原子扣减成功的瞬态（库存已锁）。
 * - CREATE_ORDER ：订单落库后的持久初态——代表「下单成功、库存已锁」。
 * - PAY          ：支付中（本项目无真实支付，仅预留 / 演示迁转）。
 * - DONE         ：支付完成，终态。
 * - CANCEL       ：取消并释放库存，终态（演示见 {@code OrderStateService.cancel}）。
 *
 * 对账时「成功单」定义为 status != CANCEL 的订单：CREATE_ORDER / PAY / DONE 都算成功，
 * CANCEL 不算（它已回补库存）。
 */
public enum OrderStatus {

    PRE_CHECK,
    LOCK_STOCK,
    CREATE_ORDER,
    PAY,
    SHIPPED,
    DONE,
    CANCEL;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(PRE_CHECK, EnumSet.of(LOCK_STOCK));
        TRANSITIONS.put(LOCK_STOCK, EnumSet.of(CREATE_ORDER, CANCEL));
        TRANSITIONS.put(CREATE_ORDER, EnumSet.of(PAY, CANCEL));
        TRANSITIONS.put(PAY, EnumSet.of(SHIPPED, DONE, CANCEL));
        TRANSITIONS.put(SHIPPED, EnumSet.of(DONE));
        TRANSITIONS.put(DONE, EnumSet.noneOf(OrderStatus.class));   // 终态
        TRANSITIONS.put(CANCEL, EnumSet.noneOf(OrderStatus.class)); // 终态
    }

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public OrderStatus transitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    String.format("订单状态非法迁转：%s → %s（合法下一状态：%s）",
                            this, target, TRANSITIONS.get(this)));
        }
        return target;
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    public static OrderStatus from(String status) {
        if (status == null) {
            throw new IllegalStateTransitionException("订单状态为空，无法解析");
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateTransitionException("未知的订单状态：" + status);
        }
    }
}
