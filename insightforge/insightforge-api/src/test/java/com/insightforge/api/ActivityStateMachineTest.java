package com.insightforge.api;

import com.insightforge.seckill.core.state.ActivityStatus;
import com.insightforge.seckill.core.state.IllegalStateTransitionException;
import com.insightforge.seckill.core.state.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机单元测试（Phase 6，纯逻辑，不依赖 Spring）。
 * 验证「合法迁转放行、非法迁转抛异常、终态不可再迁、脏数据解析抛异常」。
 */
class ActivityStateMachineTest {

    // ---------- 活动状态机 ----------

    @Test
    void draft_can_arm() {
        // DRAFT → ARMED 是合法迁转，应返回目标状态而非抛异常
        assertEquals(ActivityStatus.ARMED, ActivityStatus.DRAFT.transitionTo(ActivityStatus.ARMED));
    }

    @Test
    void draft_cannot_skip_to_live() {
        // DRAFT 不能直接跳到 LIVE（必须先进 ARMED），应抛非法迁转异常
        assertThrows(IllegalStateTransitionException.class,
                () -> ActivityStatus.DRAFT.transitionTo(ActivityStatus.LIVE));
    }

    @Test
    void closed_is_terminal() {
        // CLOSED 是终态，没有合法去处
        assertTrue(ActivityStatus.CLOSED.isTerminal());
        assertThrows(IllegalStateTransitionException.class,
                () -> ActivityStatus.CLOSED.transitionTo(ActivityStatus.ARMED));
    }

    @Test
    void armed_can_revert_to_draft() {
        // 允许回退重改方案：ARMED → DRAFT
        assertEquals(ActivityStatus.DRAFT, ActivityStatus.ARMED.transitionTo(ActivityStatus.DRAFT));
    }

    @Test
    void from_unknown_status_throws() {
        // 脏数据（数据库里出现未知状态字符串）必须被拦下来
        assertThrows(IllegalStateTransitionException.class, () -> ActivityStatus.from("NONSENSE"));
    }

    // ---------- 订单状态机 ----------

    @Test
    void order_create_can_pay_then_done() {
        // 合法链路：CREATE_ORDER → PAY → DONE（链式调用，任一步非法即中断）
        OrderStatus.from("CREATE_ORDER").transitionTo(OrderStatus.PAY).transitionTo(OrderStatus.DONE);
    }

    @Test
    void order_create_cannot_jump_to_done() {
        // 跳过支付直接 DONE 不被允许
        assertThrows(IllegalStateTransitionException.class,
                () -> OrderStatus.CREATE_ORDER.transitionTo(OrderStatus.DONE));
    }

    @Test
    void order_done_is_terminal() {
        assertTrue(OrderStatus.DONE.isTerminal());
    }

    @Test
    void order_done_cannot_cancel() {
        // 已完成订单不允许取消（防止已发货/已支付被随意撤销）
        assertThrows(IllegalStateTransitionException.class,
                () -> OrderStatus.DONE.transitionTo(OrderStatus.CANCEL));
    }
}
