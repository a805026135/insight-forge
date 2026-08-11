package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.kb.KbSearchHit;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.DeepSeekModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.api.dto.AfterSaleDraft;
import com.insightforge.api.dto.AssistantIntent;
import com.insightforge.api.dto.BuyerAssistantReply;
import com.insightforge.api.dto.MerchantContact;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.AfterSaleMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 买家在线客服（Phase 19d/g）：用户级隔离 + 防幻觉。
 *  - 订单走 SQL 仅本人，RAG 只取平台级公开知识（merchantId=0）；
 *  - 知识库无命中 → 强制转人工/商家（needHuman=true），绝不调用 LLM 编造；
 *  - 退款意图 → 按真实订单生成草稿，用户确认后提交工单；
 *  - 问答历史持久化。
 */
@Service
public class BuyerAssistantService {

    private final ObjectProvider<DeepSeekModelClient> deepSeekModelClient;
    private final ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final BuyerChatHistoryService chatHistory;

    public BuyerAssistantService(ObjectProvider<DeepSeekModelClient> deepSeekModelClient,
                                 ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider,
                                 OrdersMapper ordersMapper,
                                 OrderItemMapper orderItemMapper,
                                 AfterSaleMapper afterSaleMapper,
                                 BuyerChatHistoryService chatHistory) {
        this.deepSeekModelClient = deepSeekModelClient;
        this.knowledgeBaseProvider = knowledgeBaseProvider;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.chatHistory = chatHistory;
    }

    public BuyerAssistantReply ask(long userId, String question) {
        boolean refund = isRefundIntent(question);
        List<AfterSaleDraft> drafts = refund ? buildRefundDrafts(userId) : List.of();
        boolean needHuman;
        List<MerchantContact> merchants = List.of();
        String answer;

        if (refund && drafts.isEmpty()) {
            answer = "你暂时没有可退款的订单（需 已支付/已发货/已完成 状态）。如需帮助，可以联系商家或人工客服。";
            needHuman = true;
            merchants = recentMerchants(userId);
        } else if (!refund && kbHitEmpty(question)) {
            answer = "这个问题平台知识库暂时没有收录。为避免误导，建议转人工客服，或直接联系具体商家咨询。";
            needHuman = true;
            merchants = recentMerchants(userId);
        } else {
            answer = generate(userId, buildContext(userId, question), question);
            needHuman = false;
        }
        chatHistory.record(userId, "user", question);
        chatHistory.record(userId, "assistant", answer);
        return new BuyerAssistantReply(answer, needHuman, merchants, drafts);
    }

    /** 意图识别（不生成、不记录）：无命中 → needHuman；退款 → drafts；merchants 供转商家选择。 */
    public AssistantIntent intent(long userId, String question) {
        boolean refund = isRefundIntent(question);
        List<AfterSaleDraft> drafts = refund ? buildRefundDrafts(userId) : List.of();
        boolean needHuman = (refund && drafts.isEmpty()) || (!refund && kbHitEmpty(question));
        List<MerchantContact> merchants = needHuman ? recentMerchants(userId) : List.of();
        return new AssistantIntent(needHuman, merchants, drafts);
    }

    public void askStream(long userId, String question, Consumer<String> onChunk) {
        boolean refund = isRefundIntent(question);
        boolean needHuman = !refund && kbHitEmpty(question);
        String text;
        if (refund) {
            text = generate(userId, buildContext(userId, question), question);
        } else if (needHuman) {
            text = "这个问题平台知识库暂时没有收录。为避免误导，建议转人工客服，或直接联系具体商家咨询。";
        } else {
            text = generate(userId, buildContext(userId, question), question);
        }
        chatHistory.record(userId, "user", question);
        chatHistory.record(userId, "assistant", text);
        for (int i = 0; i < text.length(); i += 6) {
            onChunk.accept(text.substring(i, Math.min(i + 6, text.length())));
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 平台知识库是否无命中（买家客服只检索平台级知识）。 */
    private boolean kbHitEmpty(String question) {
        KnowledgeBaseService kb = knowledgeBaseProvider.getIfAvailable();
        return kb == null || kb.search(0L, question, 3).isEmpty();
    }

    private List<MerchantContact> recentMerchants(long userId) {
        List<Orders> orders = ordersMapper.selectList(new QueryWrapper<Orders>()
                .eq("user_id", userId).orderByDesc("created_at").last("LIMIT 20"));
        List<String> orderIds = orders.stream().map(Orders::getOrderId).distinct().toList();
        if (orderIds.isEmpty()) {
            return List.of();
        }
        List<OrderItem> items = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().in("order_id", orderIds));
        Map<Long, String> distinct = new LinkedHashMap<>();
        for (OrderItem it : items) {
            if (it.getMerchantId() != null) {
                distinct.putIfAbsent(it.getMerchantId(), it.getMerchantName() == null ? "商家" + it.getMerchantId() : it.getMerchantName());
            }
        }
        return distinct.entrySet().stream()
                .map(e -> new MerchantContact(e.getKey(), e.getValue()))
                .limit(5).collect(Collectors.toList());
    }

    private String generate(long userId, String context, String question) {
        DeepSeekModelClient ds = deepSeekModelClient.getIfAvailable();
        if (ds != null) {
            ModelResponse resp = ds.generate(new ModelRequest(
                    List.of(ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(context + "\n\n买家问题：" + question)),
                    "buyer-assistant",
                    List.of()));
            return resp.content() == null ? "（无回答）" : resp.content();
        }
        return deterministicAnswer(context, question);
    }

    private boolean isRefundIntent(String question) {
        String q = question == null ? "" : question;
        return q.contains("退款") || q.contains("退货");
    }

    private List<AfterSaleDraft> buildRefundDrafts(long userId) {
        List<Orders> orders = ordersMapper.selectList(new QueryWrapper<Orders>()
                .eq("user_id", userId)
                .in("status", "PAY", "DONE", "SHIPPED")
                .orderByDesc("created_at").last("LIMIT 5"));
        List<AfterSaleDraft> drafts = new ArrayList<>();
        for (Orders o : orders) {
            long existing = afterSaleMapper.selectCount(new QueryWrapper<AfterSale>()
                    .eq("user_id", userId).eq("order_id", o.getOrderId())
                    .in("status", "APPLIED", "APPROVED"));
            if (existing > 0) {
                continue;
            }
            drafts.add(new AfterSaleDraft(
                    o.getOrderId(),
                    o.getTotalAmount(),
                    "7天无理由退货",
                    "订单 " + o.getOrderId() + "（¥" + o.getTotalAmount() + "）退款申请草稿"));
        }
        return drafts;
    }

    private String buildContext(long userId, String question) {
        StringBuilder sb = new StringBuilder();
        List<Orders> orders = ordersMapper.selectList(
                new QueryWrapper<Orders>().eq("user_id", userId)
                        .orderByDesc("created_at").last("LIMIT 5"));
        if (orders.isEmpty()) {
            sb.append("【我的最近订单】暂无订单。\n");
        } else {
            sb.append("【我的最近订单】（最多 5 单，地址/电话已脱敏）\n");
            for (Orders o : orders) {
                sb.append("- 订单").append(o.getOrderId())
                  .append("：金额 ¥").append(o.getTotalAmount())
                  .append("；状态 ").append(o.getStatus())
                  .append("；时间 ").append(o.getCreatedAt())
                  .append("；收货人 ").append(mask(o.getReceiver()))
                  .append("；电话 ").append(maskPhone(o.getPhone()))
                  .append("；地址 ").append(maskAddress(o.getAddressSnapshot()))
                  .append("\n");
            }
        }
        KnowledgeBaseService kb = knowledgeBaseProvider.getIfAvailable();
        if (kb != null && question != null && !question.isBlank()) {
            List<KbSearchHit> hits = kb.search(0L, question, 3);
            if (!hits.isEmpty()) {
                sb.append("\n【平台公开知识】（检索自知识库，可能相关）\n");
                for (KbSearchHit h : hits) {
                    sb.append("- 《").append(h.title()).append("》")
                      .append(h.content().replace("\n", " ")).append("\n");
                }
                sb.append("请仅依据以上平台公开知识与订单摘要回答，并标注《来源》标题；\n")
                  .append("若以上资料不足以回答，请明确说\"知识库未收录，请转人工客服\"，不要自行推断。\n");
            }
        }
        return sb.toString();
    }

    private String deterministicAnswer(String context, String question) {
        String q = question == null ? "" : question;
        if (q.contains("退款") || q.contains("退货")) {
            return "退款需要在系统内提交售后工单：我会根据你的真实订单预填申请（订单号、金额、原因），你确认后提交，状态会按“待审核 → 已通过 → 已退款”流转。";
        }
        if (q.contains("物流") || q.contains("发货") || q.contains("快递")) {
            return "根据《买家售后政策》：商家承诺支付后 48 小时内发货；建议当面验货再签收。";
        }
        if (q.contains("发票")) {
            return "根据《买家售后政策》：支持电子发票，可在订单详情页申请，7 个工作日内开具。";
        }
        if (q.contains("客服")) {
            return "订单页在线客服 7×24 小时在线；电话客服 400-888-0000，工作日 9:00-21:00。";
        }
        return "这个问题平台知识库暂时没有收录。为避免误导，建议转人工客服，或直接联系具体商家咨询。";
    }

    private String mask(String s) {
        return s == null || s.isBlank() ? "（未填写）" : s;
    }

    private String maskPhone(String p) {
        if (p == null || p.length() < 7) {
            return p == null ? "" : p;
        }
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    private String maskAddress(String a) {
        if (a == null || a.length() < 8) {
            return a == null ? "" : a;
        }
        return a.substring(0, 6) + "****";
    }

    private static final String SYSTEM_PROMPT = """
            你是一名电商平台在线客服，正在为买家解答问题。
            只能基于【我的最近订单】的真实订单数据和【平台公开知识】回答。
            回答必须标注《来源》标题；资料不足以回答时，必须说"知识库未收录，请转人工客服"，绝不编造政策条款或订单信息。
            退款/退货引导用户提交售后工单；工单状态：待审核（APPLIED）→ 已通过（APPROVED）→ 已退款（REFUNDED）；或被驳回（REJECTED）。
            """;
}
