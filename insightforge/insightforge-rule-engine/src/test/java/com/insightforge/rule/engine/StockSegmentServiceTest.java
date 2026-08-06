package com.insightforge.rule.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证分段库存的两条防超卖铁律：① 只建新段；② 两阶段预留 pending 回滚不超卖。
 */
class StockSegmentServiceTest {

    private final StockSegmentService svc = new StockSegmentService();

    @Test
    void onlyAppendsNewSegment() {
        var a1 = svc.allocateNewSegment(1, 1, 50);
        assertEquals(1, a1.newSegment());
        assertEquals(1, svc.segmentCount(1, 1));
        assertEquals(50, svc.total(1, 1));

        var a2 = svc.allocateNewSegment(1, 1, 30);
        assertEquals(2, a2.newSegment());           // O(1) 追加新段，不重排
        assertEquals(2, svc.segmentCount(1, 1));
        assertEquals(80, svc.total(1, 1));
    }

    @Test
    void pendingRollbackDoesNotOversell() {
        svc.allocateNewSegment(1, 1, 100);          // total=100, pending=100, available=0
        assertEquals(0, svc.available(1, 1));

        svc.releasePending(1, 1, 100);              // 回滚：total=0, pending=0
        assertEquals(0, svc.available(1, 1));
        assertEquals(0, svc.total(1, 1));
        assertEquals(0, svc.segmentCount(1, 1));
    }

    @Test
    void confirmMakesStockAvailable() {
        svc.allocateNewSegment(1, 1, 100);          // 调拨中，用户不可抢
        assertEquals(0, svc.available(1, 1));
        svc.confirmPending(1, 1, 100);              // 确认调入
        assertEquals(100, svc.available(1, 1));
    }

    @Test
    void rejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> svc.allocateNewSegment(1, 1, -1));
    }
}
