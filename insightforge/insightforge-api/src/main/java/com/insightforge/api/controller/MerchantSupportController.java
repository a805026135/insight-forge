package com.insightforge.api.controller;

import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.service.SupportTicketService;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.SupportTicket;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商家端咨询工单（Phase 19g）：买家转商家客服后，商家在本端回复。
 * 仅能看到本店的工单。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantSupportController {

    private final SupportTicketService supportTicketService;

    public MerchantSupportController(SupportTicketService supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    /** GET /api/merchant/support-tickets?status=OPEN 本店咨询工单 */
    @GetMapping("/support-tickets")
    public ApiResult<List<SupportTicket>> tickets(@RequestParam(required = false) String status) {
        return ApiResult.ok(supportTicketService.merchantList(AuthContextHolder.requireMerchantId(), status));
    }

    /** POST /api/merchant/support-tickets/{id}/reply 回复买家咨询 */
    @PostMapping("/support-tickets/{id}/reply")
    public ApiResult<Void> reply(@PathVariable Long id, @RequestBody Map<String, String> body) {
        long merchantId = AuthContextHolder.requireMerchantId();
        try {
            supportTicketService.reply(merchantId, id, body.get("answer"));
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }
}
