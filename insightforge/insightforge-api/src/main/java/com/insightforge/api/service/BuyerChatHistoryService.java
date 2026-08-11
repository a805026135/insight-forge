package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.BuyerChatMessage;
import com.insightforge.persistence.mapper.BuyerChatMessageMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 买家客服历史（Phase 19f）：问答持久化，用户重新进入客服页仍可见。
 */
@Service
public class BuyerChatHistoryService {

    private final BuyerChatMessageMapper mapper;

    public BuyerChatHistoryService(BuyerChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    public void record(long userId, Long merchantId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        BuyerChatMessage m = new BuyerChatMessage();
        m.setUserId(userId);
        m.setMerchantId(merchantId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        mapper.insert(m);
    }

    public List<BuyerChatMessage> history(long userId, int limit) {
        return history(userId, null, limit);
    }

    /** 按 (userId, merchantId) 取历史；merchantId 为空表示平台客服会话 */
    public List<BuyerChatMessage> history(long userId, Long merchantId, int limit) {
        QueryWrapper<BuyerChatMessage> qw = new QueryWrapper<BuyerChatMessage>()
                .eq("user_id", userId);
        if (merchantId == null) {
            qw.isNull("merchant_id");
        } else {
            qw.eq("merchant_id", merchantId);
        }
        List<BuyerChatMessage> rows = mapper.selectList(qw
                .orderByDesc("id").last("LIMIT " + Math.min(Math.max(limit, 10), 200)));
        List<BuyerChatMessage> out = new ArrayList<>(rows);
        Collections.reverse(out);
        return out;
    }

    /** 按商家取最近若干条（供转人工时生成对话摘要） */
    public List<BuyerChatMessage> recent(long userId, Long merchantId, int limit) {
        List<BuyerChatMessage> rows = mapper.selectList(new QueryWrapper<BuyerChatMessage>()
                .eq("user_id", userId)
                .eq("merchant_id", merchantId)
                .orderByDesc("id").last("LIMIT " + Math.min(Math.max(limit, 10), 200)));
        List<BuyerChatMessage> out = new ArrayList<>(rows);
        Collections.reverse(out);
        return out;
    }

    /** 清空该用户的客服历史（用户主动清空对话）。 */
    public void clear(long userId) {
        mapper.delete(new QueryWrapper<BuyerChatMessage>().eq("user_id", userId));
    }
}
