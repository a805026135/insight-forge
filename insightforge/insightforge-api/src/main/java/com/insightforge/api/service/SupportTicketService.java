package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.SupportTicket;
import com.insightforge.persistence.mapper.SupportTicketMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 咨询工单（Phase 19g）：买家发起 → 商家端回复 → 买家关闭。
 * 用户级/商家级双重隔离：买家只能看自己的，商家只能看本店的。
 */
@Service
public class SupportTicketService {

    private final SupportTicketMapper mapper;
    private final BuyerChatHistoryService chatHistory;
    /** (userId, merchantId) -> Agent 暂停开始时间；10 分钟后自动恢复 */
    private static final long PAUSE_MILLIS = 10 * 60 * 1000L;
    private final Map<String, Long> agentPausedAt = new ConcurrentHashMap<>();

    public SupportTicketService(SupportTicketMapper mapper, BuyerChatHistoryService chatHistory) {
        this.mapper = mapper;
        this.chatHistory = chatHistory;
    }

    public SupportTicket create(long userId, long merchantId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (merchantId <= 0) {
            throw new IllegalArgumentException("请选择要咨询的商家");
        }
        SupportTicket t = new SupportTicket();
        t.setUserId(userId);
        t.setMerchantId(merchantId);
        t.setQuestion(question.trim());
        t.setTranscript(buildTranscript(userId, merchantId));
        t.setStatus("OPEN");
        t.setCreatedAt(LocalDateTime.now());
        mapper.insert(t);
        // 转人工后，该商家专属 Agent 暂停 10 分钟（可提前唤醒/新会话恢复）
        agentPausedAt.put(pauseKey(userId, merchantId), System.currentTimeMillis());
        return t;
    }

    /** Agent 是否处于人工接管暂停期（10 分钟内不再回答；超时自动恢复） */
    public boolean isAgentPaused(long userId, long merchantId) {
        Long at = agentPausedAt.get(pauseKey(userId, merchantId));
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at >= PAUSE_MILLIS) {
            agentPausedAt.remove(pauseKey(userId, merchantId));
            return false;
        }
        return true;
    }

    /** 用户主动唤醒 Agent / 开始新会话：立即解除暂停 */
    public void wakeAgent(long userId, long merchantId) {
        agentPausedAt.remove(pauseKey(userId, merchantId));
    }

    private String pauseKey(long userId, long merchantId) {
        return userId + ":" + merchantId;
    }

    /** 转人工时把最近的对话截取成摘要，商家侧可看到完整上下文 */
    private String buildTranscript(long userId, long merchantId) {
        var msgs = chatHistory.recent(userId, merchantId, 20);
        if (msgs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (var m : msgs) {
            String who = "user".equals(m.getRole()) ? "买家" : "客服Agent";
            String content = m.getContent() == null ? "" : m.getContent().replace("\n", " ");
            if (content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            sb.append(who).append("：").append(content).append("\n");
        }
        return sb.toString();
    }

    public List<SupportTicket> buyerList(long userId) {
        return mapper.selectList(new QueryWrapper<SupportTicket>()
                .eq("user_id", userId).orderByDesc("id"));
    }

    public List<SupportTicket> merchantList(long merchantId, String status) {
        QueryWrapper<SupportTicket> qw = new QueryWrapper<SupportTicket>()
                .eq("merchant_id", merchantId).orderByDesc("id");
        if (status != null && !status.isBlank()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        return mapper.selectList(qw);
    }

    public void reply(long merchantId, long id, String answer) {
        SupportTicket t = mapper.selectById(id);
        if (t == null || t.getMerchantId() == null || t.getMerchantId() != merchantId) {
            throw new IllegalArgumentException("工单不存在或无权回复");
        }
        if ("CLOSED".equals(t.getStatus())) {
            throw new IllegalArgumentException("工单已关闭，无法回复");
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("回复内容不能为空");
        }
        t.setAnswer(answer.trim());
        t.setStatus("REPLIED");
        t.setRepliedAt(LocalDateTime.now());
        mapper.updateById(t);
        // 商家回复后：本会话仍处于人工接管（Agent 保持暂停直至用户唤醒/超时）
        agentPausedAt.put(pauseKey(t.getUserId(), merchantId), System.currentTimeMillis());
    }

    public void close(long userId, long id) {
        SupportTicket t = mapper.selectById(id);
        if (t == null || t.getUserId() == null || t.getUserId() != userId) {
            throw new IllegalArgumentException("工单不存在或无权操作");
        }
        if ("CLOSED".equals(t.getStatus())) {
            return;
        }
        t.setStatus("CLOSED");
        mapper.updateById(t);
    }
}
