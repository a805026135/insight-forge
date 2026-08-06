package com.insightforge.seckill.core.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 活动生命周期状态机（Phase 6）。
 *
 * 大白话：一个秒杀活动从「写方案」到「下线归档」要走好几步，每一步只能往规定的下一步走，
 * 不能跳着走、也不能随便倒退。这个枚举把「哪些走法合法」写死在代码里，谁想乱跳直接抛异常。
 *
 * 状态流转（箭头 = 允许的迁转）：
 *
 *      DRAFT ──▶ ARMED ──▶ LIVE ──▶ SETTLING ──▶ CLOSED
 *      （草稿）  （武装/   （开抢）  （对账/    （关闭，终态）
 *              预热完）         结算）
 *
 * - DRAFT    ：运营 / Agent 还在规划（选品、定价、写护栏 DSL）。
 * - ARMED    ：库存已预热进 Redis，且「MySQL↔Redis 库存对账」通过，整装待发。
 *              进入 ARMED 前必须过一遍对账（见 {@link com.insightforge.seckill.core.reconcile.ReconcileService}），
 *              不一致就绝不允许上线——这是「上线前最后一道闸门」。
 * - LIVE     ：用户正在抢、Agent 正在控盘（调价 / 再分配）。
 * - SETTLING ：活动结束，做「结算对账」（最终库存 = 初始 - 成功单数），验证没超卖。
 * - CLOSED   ：对账完成、活动归档，终态，不能再动。
 *
 * 数据库里 activity.status 存的就是这些大写名字的字符串（与 Phase 1 建表兼容，零类型改动）；
 * 本枚举只在代码层做「合法迁转校验」，是状态一致性的守门员。
 */
public enum ActivityStatus {

    DRAFT,
    ARMED,
    LIVE,
    SETTLING,
    CLOSED;

    /**
     * 迁转矩阵：key = 当前状态，value = 允许的下一状态集合。
     * 用静态 EnumMap 在类加载时填好（不能在枚举常量声明里直接引用尚未定义的常量，
     * 所以统一在这里用 static 块装配，规避「前向引用」编译错误）。
     */
    private static final Map<ActivityStatus, Set<ActivityStatus>> TRANSITIONS = new EnumMap<>(ActivityStatus.class);

    static {
        TRANSITIONS.put(DRAFT, EnumSet.of(ARMED));                 // 草稿只能去武装
        TRANSITIONS.put(ARMED, EnumSet.of(LIVE, DRAFT));           // 武装后可开抢，也可回退重改方案
        TRANSITIONS.put(LIVE, EnumSet.of(SETTLING));               // 开抢中只能去结算
        TRANSITIONS.put(SETTLING, EnumSet.of(CLOSED, LIVE));       // 结算后可关闭，也可退回重算
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(ActivityStatus.class)); // 终态，无去处
    }

    /** 当前状态能否迁到 target（只查矩阵，不抛异常，方便「预判」）。 */
    public boolean canTransitionTo(ActivityStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /**
     * 执行迁转：合法则返回目标状态；非法抛 {@link IllegalStateTransitionException}。
     * 调用方通常写法：{@code ActivityStatus.from(a.getStatus()).transitionTo(ActivityStatus.ARMED)}
     */
    public ActivityStatus transitionTo(ActivityStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    String.format("活动状态非法迁转：%s → %s（合法下一状态：%s）",
                            this, target, TRANSITIONS.get(this)));
        }
        return target;
    }

    /** 是否终态（无去处）。 */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /** 把数据库里的字符串解析成枚举；未知值直接抛异常（防止脏数据绕过状态机）。 */
    public static ActivityStatus from(String status) {
        if (status == null) {
            throw new IllegalStateTransitionException("活动状态为空，无法解析");
        }
        try {
            return ActivityStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateTransitionException("未知的活动状态：" + status);
        }
    }
}
