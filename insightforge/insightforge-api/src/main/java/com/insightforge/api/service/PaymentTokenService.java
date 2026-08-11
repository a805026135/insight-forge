package com.insightforge.api.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付确认一次性令牌（Phase 19j）：扫码后在手机端确认支付。
 * 令牌绑定订单与用户，10 分钟有效，确认后立即作废（防重放）。
 */
@Service
public class PaymentTokenService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private final Map<String, PaymentToken> tokens = new ConcurrentHashMap<>();

    public record PaymentToken(String token, String orderId, long userId, long expireAt) {
    }

    public PaymentToken issue(String orderId, long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        PaymentToken pt = new PaymentToken(token, orderId, userId,
                System.currentTimeMillis() + TTL.toMillis());
        tokens.put(token, pt);
        return pt;
    }

    /** 查看（不消费），供确认页展示订单信息。 */
    public PaymentToken peek(String token) {
        PaymentToken pt = tokens.get(token);
        if (pt == null) {
            throw new IllegalArgumentException("支付令牌无效或已过期");
        }
        if (System.currentTimeMillis() > pt.expireAt()) {
            tokens.remove(token);
            throw new IllegalArgumentException("支付令牌已过期，请重新发起支付");
        }
        return pt;
    }

    /** 消费：校验通过后作废，返回令牌信息（仅调用一次）。 */
    public PaymentToken consume(String token) {
        PaymentToken pt = peek(token);
        tokens.remove(token);
        return pt;
    }
}
