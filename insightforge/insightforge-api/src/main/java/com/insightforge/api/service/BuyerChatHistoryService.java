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

    public void record(long userId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        BuyerChatMessage m = new BuyerChatMessage();
        m.setUserId(userId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        mapper.insert(m);
    }

    public List<BuyerChatMessage> history(long userId, int limit) {
        List<BuyerChatMessage> rows = mapper.selectList(new QueryWrapper<BuyerChatMessage>()
                .eq("user_id", userId).orderByDesc("id").last("LIMIT " + Math.min(Math.max(limit, 10), 200)));
        List<BuyerChatMessage> out = new ArrayList<>(rows);
        Collections.reverse(out);
        return out;
    }
}
