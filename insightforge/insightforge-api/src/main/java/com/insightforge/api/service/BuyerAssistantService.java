package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.kb.KbSearchHit;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.DeepSeekModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.api.dto.AssistantReply;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.OrdersMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 买家在线客服（Phase 19d）：用户级隔离——
 *  - 用户订单走结构化查询（仅本人，地址/电话脱敏），绝不进向量库；
 *  - RAG 只检索平台级公开知识（merchantId=0：FAQ/买家售后政策/运营手册）；
 *  - 知识库未收录时明确转人工，禁止编造。
 */
@Service
public class BuyerAssistantService {

    private final ObjectProvider<DeepSeekModelClient> deepSeekModelClient;
    private final ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider;
    private final OrdersMapper ordersMapper;

    public BuyerAssistantService(ObjectProvider<DeepSeekModelClient> deepSeekModelClient,
                                 ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider,
                                 OrdersMapper ordersMapper) {
        this.deepSeekModelClient = deepSeekModelClient;
        this.knowledgeBaseProvider = knowledgeBaseProvider;
        this.ordersMapper = ordersMapper;
    }

    public AssistantReply ask(long userId, String question) {
        String answer = generate(userId, buildContext(userId, question), question);
        return new AssistantReply(answer, "SUPPORT", answer, List.of());
    }

    public void askStream(long userId, String question, Consumer<String> onChunk) {
        String text = generate(userId, buildContext(userId, question), question);
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

    /** 平台公开知识注入：只检索 merchantId=0 的知识（FAQ/售后政策/手册），用户数据不进向量库。 */
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
                sb.append("请优先参考以上平台公开知识回答，并简要说明依据来源。\n");
            }
        }
        return sb.toString();
    }

    private String deterministicAnswer(String context, String question) {
        String q = question == null ? "" : question;
        if (q.contains("退货") || q.contains("退款")) {
            return "根据平台售后政策：确认收货后 48 小时内原路退回，大促期间最迟 72 小时；7 天内支持无理由退货（食品/生鲜/定制类除外）。";
        }
        if (q.contains("物流") || q.contains("发货") || q.contains("快递")) {
            return "商家承诺支付后 48 小时内发货；建议当面验货再签收，外包装破损可拒收或拍照留证后联系客服。";
        }
        if (q.contains("发票")) {
            return "支持电子发票，可在订单详情页申请，7 个工作日内开具。";
        }
        if (q.contains("订单")) {
            return "我查询到你的最近订单如下（详见上方订单摘要）。如需更详细物流信息，请提供订单号或联系人工客服。";
        }
        if (q.contains("客服")) {
            return "订单页在线客服 7×24 小时在线；电话客服 400-888-0000，工作日 9:00-21:00。";
        }
        return "我是平台在线客服。关于退货退款、物流发货、发票、订单状态等常见问题都可以问我；更复杂的问题会转人工处理。";
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
            只基于【我的最近订单】中的真实订单数据和【平台公开知识】回答，不要编造数据。
            涉及订单状态/金额时，只引用订单摘要中的真实数据；涉及政策时引用《来源》标题。
            若知识库未收录相关内容，明确说"该问题需要联系人工客服"。
            """;
}
