package com.insightforge.api.controller;

import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.AfterSaleDraft;
import com.insightforge.api.dto.BuyerAssistantReply;
import com.insightforge.api.service.AfterSaleService;
import com.insightforge.api.service.BuyerAssistantService;
import com.insightforge.api.service.BuyerChatHistoryService;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.BuyerChatMessage;
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

    public BuyerController(BuyerAssistantService assistantService,
                           BuyerChatHistoryService chatHistoryService,
                           AfterSaleService afterSaleService) {
        this.assistantService = assistantService;
        this.chatHistoryService = chatHistoryService;
        this.afterSaleService = afterSaleService;
    }

    /** POST /api/buyer/assistant 买家客服问答 */
    @PostMapping("/assistant")
    public ApiResult<BuyerAssistantReply> assistant(@RequestBody Map<String, String> body) {
        long userId = AuthContextHolder.requireUserId();
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ApiResult.fail(400, "问题不能为空");
        }
        return ApiResult.ok(assistantService.ask(userId, question.trim()));
    }

    /** GET /api/buyer/chat/history 客服问答历史（跨会话持久化） */
    @GetMapping("/chat/history")
    public ApiResult<List<BuyerChatMessage>> chatHistory(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(chatHistoryService.history(AuthContextHolder.requireUserId(), limit));
    }

    /** GET /api/buyer/after-sale/drafts?question=... AI 预填退款草稿（只识别，不写历史） */
    @GetMapping("/after-sale/drafts")
    public ApiResult<List<AfterSaleDraft>> afterSaleDrafts(
            @org.springframework.web.bind.annotation.RequestParam String question) {
        return ApiResult.ok(assistantService.refundDrafts(AuthContextHolder.requireUserId(), question));
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

    /** POST /api/buyer/assistant/stream 流式回答（SSE） */
    @PostMapping("/assistant/stream")
    public SseEmitter assistantStream(@RequestBody Map<String, String> body) {
        long userId = AuthContextHolder.requireUserId();
        String question = body.get("question");
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
                assistantService.askStream(userId, question.trim(), chunk -> {
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
