package com.insightforge.api.controller;

import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.AfterSaleDraft;
import com.insightforge.api.dto.AssistantIntent;
import com.insightforge.api.dto.BuyerAssistantReply;
import com.insightforge.api.service.AfterSaleService;
import com.insightforge.api.service.BuyerAssistantService;
import com.insightforge.api.service.BuyerChatHistoryService;
import com.insightforge.api.service.LogisticsReviewService;
import com.insightforge.api.service.SupportTicketService;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.BuyerChatMessage;
import com.insightforge.persistence.entity.ProductReview;
import com.insightforge.persistence.entity.SupportTicket;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 买家在线客服（Phase 19d）：用户级隔离——订单数据只查本人，知识库只取平台公开知识。
 */
@RestController
@RequestMapping("/api/buyer")
public class BuyerController {

    private final BuyerAssistantService assistantService;
    private final BuyerChatHistoryService chatHistoryService;
    private final AfterSaleService afterSaleService;
    private final SupportTicketService supportTicketService;
    private final LogisticsReviewService logisticsReviewService;

    public BuyerController(BuyerAssistantService assistantService,
                           BuyerChatHistoryService chatHistoryService,
                           AfterSaleService afterSaleService,
                           SupportTicketService supportTicketService,
                           LogisticsReviewService logisticsReviewService) {
        this.assistantService = assistantService;
        this.chatHistoryService = chatHistoryService;
        this.afterSaleService = afterSaleService;
        this.supportTicketService = supportTicketService;
        this.logisticsReviewService = logisticsReviewService;
    }

    /** POST /api/buyer/assistant 买家客服问答 */
    @PostMapping("/assistant")
    public ApiResult<BuyerAssistantReply> assistant(@RequestBody Map<String, Object> body) {
        long userId = AuthContextHolder.requireUserId();
        String question = body.get("question") == null ? null : String.valueOf(body.get("question"));
        if (question == null || question.isBlank()) {
            return ApiResult.fail(400, "问题不能为空");
        }
        Long merchantId = body.get("merchantId") instanceof Number n ? n.longValue() : null;
        return ApiResult.ok(assistantService.ask(userId, question.trim(), merchantId));
    }

    /** GET /api/buyer/chat/history 客服问答历史（跨会话持久化） */
    @GetMapping("/chat/history")
    public ApiResult<List<BuyerChatMessage>> chatHistory(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long merchantId) {
        return ApiResult.ok(chatHistoryService.history(AuthContextHolder.requireUserId(), merchantId, limit));
    }

    /** DELETE /api/buyer/chat/history 清空我的客服历史 */
    @DeleteMapping("/chat/history")
    public ApiResult<Void> chatHistoryClear() {
        chatHistoryService.clear(AuthContextHolder.requireUserId());
        return ApiResult.ok(null);
    }

    /** GET /api/buyer/after-sale/drafts?question=... AI 预填退款草稿（只识别，不写历史） */
    @GetMapping("/after-sale/drafts")
    public ApiResult<List<AfterSaleDraft>> afterSaleDrafts(
            @org.springframework.web.bind.annotation.RequestParam String question) {
        return ApiResult.ok(assistantService.intent(AuthContextHolder.requireUserId(), question, null).drafts());
    }

    /** GET /api/buyer/assistant/intent?question=... 意图识别：无命中→转人工，退款→草稿，商家列表 */
    @GetMapping("/assistant/intent")
    public ApiResult<AssistantIntent> assistantIntent(
            @org.springframework.web.bind.annotation.RequestParam String question,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long merchantId) {
        return ApiResult.ok(assistantService.intent(AuthContextHolder.requireUserId(), question, merchantId));
    }

    /** POST /api/buyer/support-ticket 发起咨询（转商家客服） */
    @PostMapping("/support-ticket")
    public ApiResult<SupportTicket> supportTicketCreate(@RequestBody Map<String, Object> body) {
        long userId = AuthContextHolder.requireUserId();
        long merchantId = body.get("merchantId") instanceof Number n ? n.longValue() : 0L;
        String question = body.get("question") == null ? null : String.valueOf(body.get("question"));
        try {
            return ApiResult.ok(supportTicketService.create(userId, merchantId, question));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** GET /api/buyer/support-ticket 我的咨询列表 */
    @GetMapping("/support-ticket")
    public ApiResult<List<SupportTicket>> supportTicketList() {
        return ApiResult.ok(supportTicketService.buyerList(AuthContextHolder.requireUserId()));
    }

    /** POST /api/buyer/support-ticket/{id}/close 关闭咨询 */
    @PostMapping("/support-ticket/{id}/close")
    public ApiResult<Void> supportTicketClose(@PathVariable Long id) {
        try {
            supportTicketService.close(AuthContextHolder.requireUserId(), id);
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** POST /api/buyer/support-ticket/{merchantId}/wake 用户唤醒商家 Agent（解除人工接管暂停） */
    @PostMapping("/support-ticket/{merchantId}/wake")
    public ApiResult<Void> supportTicketWake(@PathVariable Long merchantId) {
        long userId = AuthContextHolder.requireUserId();
        supportTicketService.wakeAgent(userId, merchantId);
        return ApiResult.ok(null);
    }

    /** POST /api/buyer/after-sale/apply 提交退款申请（AI 预填后由用户确认提交） */
    @PostMapping("/after-sale/apply")
    public ApiResult<AfterSale> afterSaleApply(@RequestBody Map<String, String> body) {
        long userId = AuthContextHolder.requireUserId();
        try {
            return ApiResult.ok(afterSaleService.apply(userId, body.get("orderId"), body.get("reason")));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** GET /api/buyer/after-sale 我的售后工单列表 */
    @GetMapping("/after-sale")
    public ApiResult<List<AfterSale>> afterSaleList() {
        return ApiResult.ok(afterSaleService.list(AuthContextHolder.requireUserId()));
    }

    /** POST /api/buyer/after-sale/{id}/cancel 取消待审核的退款申请 */
        /** POST /api/buyer/after-sale/{id}/appeal ?????????REJECTED ? APPEAL? */
    @PostMapping("/after-sale/{id}/appeal")
    public ApiResult<Void> afterSaleAppeal(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        long userId = AuthContextHolder.requireUserId();
        try {
            afterSaleService.appeal(userId, id, body.get("reason"));
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

@PostMapping("/after-sale/{id}/cancel")
    public ApiResult<Void> afterSaleCancel(@PathVariable Long id) {
        long userId = AuthContextHolder.requireUserId();
        try {
            afterSaleService.cancel(userId, id);
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** POST /api/buyer/orders/{orderId}/confirm 买家确认收货（SHIPPED → DONE + 签收轨迹） */
    @PostMapping("/orders/{orderId}/confirm")
    public ApiResult<Void> confirmReceipt(@PathVariable String orderId) {
        return logisticsReviewService.confirmReceipt(AuthContextHolder.requireUserId(), orderId);
    }

    /** GET /api/buyer/orders/{orderId}/logistics 订单物流轨迹 */
    @GetMapping("/orders/{orderId}/logistics")
    public ApiResult<Map<String, Object>> logistics(@PathVariable String orderId) {
        return logisticsReviewService.logistics(AuthContextHolder.requireUserId(), orderId);
    }

    /** POST /api/buyer/review 提交评价（DONE 订单，同一订单商品仅一次） */
    @PostMapping("/review")
    public ApiResult<ProductReview> review(@RequestBody Map<String, Object> body) {
        long userId = AuthContextHolder.requireUserId();
        String orderId = body.get("orderId") == null ? null : String.valueOf(body.get("orderId"));
        Long productId = body.get("productId") instanceof Number n ? n.longValue() : null;
        int rating = body.get("rating") instanceof Number n ? n.intValue() : 0;
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        try {
            return logisticsReviewService.createReview(userId, orderId, productId, rating, content);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** GET /api/buyer/review?productId= 商品评价列表 */
    @GetMapping("/review")
    public ApiResult<Map<String, Object>> reviews(
            @org.springframework.web.bind.annotation.RequestParam Long productId) {
        return logisticsReviewService.productReviews(productId);
    }

    /** POST /api/buyer/assistant/stream 流式回答（SSE） */
    @PostMapping("/assistant/stream")
    public SseEmitter assistantStream(@RequestBody Map<String, Object> body) {
        long userId = AuthContextHolder.requireUserId();
        String question = body.get("question") == null ? null : String.valueOf(body.get("question"));
        Long merchantId = body.get("merchantId") instanceof Number n ? n.longValue() : null;
        if (question == null || question.isBlank()) {
            SseEmitter bad = new SseEmitter();
            try {
                bad.send(SseEmitter.event().name("error").data("问题不能为空"));
            } catch (Exception ignored) {
                // ignore
            }
            bad.complete();
            return bad;
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread t = new Thread(() -> {
            try {
                assistantService.askStream(userId, question.trim(), merchantId, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                    // ignore
                }
            } finally {
                emitter.complete();
            }
        });
        t.setDaemon(true);
        t.start();
        return emitter;
    }
}
