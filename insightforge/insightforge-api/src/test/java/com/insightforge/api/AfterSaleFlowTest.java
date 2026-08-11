package com.insightforge.api;

import com.insightforge.api.service.AfterSaleService;
import com.insightforge.api.service.BuyerChatHistoryService;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.OrdersMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 退款工单真实闭环（Phase 19f）：申请 → 防重复/防越权 → 取消；
 * 客服历史跨调用持久化。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class AfterSaleFlowTest {

    @Autowired
    private AfterSaleService afterSaleService;

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private BuyerChatHistoryService chatHistoryService;

    @Test
    void refundLifecycleApplyDuplicateAndCancel() {
        Orders o = new Orders();
        o.setOrderId("AS-TEST-ORDER-1");
        o.setUserId(9001L);
        o.setStatus("DONE");
        o.setTotalAmount(new BigDecimal("199.00"));
        o.setCreatedAt(LocalDateTime.now());
        ordersMapper.insert(o);

        AfterSale a = afterSaleService.apply(9001L, "AS-TEST-ORDER-1", "7天无理由退货");
        assertEquals("APPLIED", a.getStatus());
        assertEquals(new BigDecimal("199.00"), a.getAmount());

        assertThrows(IllegalArgumentException.class,
                () -> afterSaleService.apply(9001L, "AS-TEST-ORDER-1", "重复申请"));
        assertThrows(IllegalArgumentException.class,
                () -> afterSaleService.apply(9999L, "AS-TEST-ORDER-1", "非本人"));

        afterSaleService.cancel(9001L, a.getId());
        assertEquals("CANCELLED", afterSaleService.list(9001L).get(0).getStatus());
    }

    @Test
    void chatHistoryPersistsAcrossCalls() {
        chatHistoryService.record(9002L, null, "user", "你好，怎么退款？");
        chatHistoryService.record(9002L, null, "assistant", "我为你预填了退款申请，请确认提交。");

        var rows = chatHistoryService.history(9002L, 50);
        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).getRole());
        assertEquals("assistant", rows.get(1).getRole());
    }
}
