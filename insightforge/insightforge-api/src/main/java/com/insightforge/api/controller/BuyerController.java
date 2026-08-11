package com.insightforge.api.controller;

import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.AssistantReply;
import com.insightforge.api.service.BuyerAssistantService;
import com.insightforge.common.result.ApiResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 买家在线客服（Phase 19d）：用户级隔离——订单数据只查本人，知识库只取平台公开知识。
 */
@RestController
@RequestMapping("/api/buyer")
public class BuyerController {

    private final BuyerAssistantService assistantService;

    public BuyerController(BuyerAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    /** POST /api/buyer/assistant 买家客服问答 */
    @PostMapping("/assistant")
    public ApiResult<AssistantReply> assistant(@RequestBody Map<String, String> body) {
        long userId = AuthContextHolder.requireUserId();
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ApiResult.fail(400, "问题不能为空");
        }
        return ApiResult.ok(assistantService.ask(userId, question.trim()));
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
