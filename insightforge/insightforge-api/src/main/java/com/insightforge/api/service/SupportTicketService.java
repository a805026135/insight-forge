package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.SupportTicket;
import com.insightforge.persistence.mapper.SupportTicketMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 咨询工单（Phase 19g）：买家发起 → 商家端回复 → 买家关闭。
 * 用户级/商家级双重隔离：买家只能看自己的，商家只能看本店的。
 */
@Service
public class SupportTicketService {

    private final SupportTicketMapper mapper;

    public SupportTicketService(SupportTicketMapper mapper) {
        this.mapper = mapper;
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
        t.setStatus("OPEN");
        t.setCreatedAt(LocalDateTime.now());
        mapper.insert(t);
        return t;
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
