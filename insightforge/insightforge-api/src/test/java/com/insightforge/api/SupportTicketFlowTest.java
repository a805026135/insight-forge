package com.insightforge.api;

import com.insightforge.api.service.SupportTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 咨询工单流转（Phase 19g）：买家发起 → 商家回复（隔离校验）→ 买家关闭。
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class SupportTicketFlowTest {

    @Autowired
    private SupportTicketService supportTicketService;

    @Test
    void buyerCreatesMerchantRepliesAndCloses() {
        var t = supportTicketService.create(7001L, 2L, "华为 Mate 70 Pro 支持免息分期吗？");
        assertEquals("OPEN", t.getStatus());

        assertThrows(IllegalArgumentException.class,
                () -> supportTicketService.create(7001L, 0L, "缺商家"));
        assertThrows(IllegalArgumentException.class,
                () -> supportTicketService.reply(999L, t.getId(), "非本店回复"));

        supportTicketService.reply(2L, t.getId(), "支持 12/24 期免息分期。");
        assertEquals("REPLIED", supportTicketService.buyerList(7001L).get(0).getStatus());
        assertEquals("支持 12/24 期免息分期。",
                supportTicketService.merchantList(2L, null).get(0).getAnswer());

        supportTicketService.close(7001L, t.getId());
        assertEquals("CLOSED", supportTicketService.buyerList(7001L).get(0).getStatus());
    }
}
