package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.kb.KbSearchHit;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.DeepSeekModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.api.dto.AfterSaleDraft;
import com.insightforge.api.dto.BuyerAssistantReply;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.AfterSaleMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 买家在线客服（Phase 19d/f）：用户级隔离——
 *  - 用户订单走结构化查询（仅本人，地址/电话脱敏），绝不进向量库；
 *  - RAG 只检索平台级公开知识（merchantId=0）；
 *  - 退款意图识别 → 生成退款申请草稿（基于真实订单），由用户确认后提交工单；
 *  - 问答历史持久化，重新进入客服页仍可见。
 */
@Service
public class BuyerAssistantService {

    private final ObjectProvider<DeepSeekModelClient> deepSeekModelClient;
    private final ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider;
    private final OrdersMapper ordersMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final BuyerChatHistoryService chatHistory;

    public BuyerAssistantService(ObjectProvider<DeepSeekModelClient> deepSeekModelClient,
                                 ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider,
                                 OrdersMapper ordersMapper,
                                 AfterSaleMapper afterSaleMapper,
                                 BuyerChatHistoryService chatHistory) {
        this.deepSeekModelClient = deepSeekModelClient;
        this.knowledgeBaseProvider = knowledgeBaseProvider;
        this.ordersMapper = ordersMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.chatHistory = chatHistory;
    }

    public BuyerAssistantReply ask(long userId, String question) {
        String context = buildContext(userId, question);
        String answer = generate(userId, context, question);
        chatHistory.record(userId, "user", question);
        chatHistory.record(userId, "assistant", answer);

        List<AfterSaleDraft> drafts = List.of();
        if (isRefundIntent(question)) {
            drafts = buildRefundDrafts(userId);
            if (!drafts.isEmpty()) {
                answer += "\n\n我根据你的真实订单预填了退款申请，请核对后点击【提交退款申请】。";
            }
        }
        return new BuyerAssistantReply(answer, drafts);
    }

    public void askStream(long userId, String question, Consumer<String> onChunk) {
        String text = generate(userId, buildContext(userId, question), question);
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

    /** 退款草稿（只识别不记录历史）：流式回答结束后前端单独拉取，供用户确认提交。 */
    public List<AfterSaleDraft> refundDrafts(long userId, String question) {
        return isRefundIntent(question) ? buildRefundDrafts(userId) : List.of();
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

    /** 退款意图识别：问题含“退款/退货”且系统支持，则预填真实订单草稿。 */
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
                sb.append("请优先参考以上平台公开知识回答，并简要说明依据来源。\n");
            }
        }
        return sb.toString();
    }

    private String deterministicAnswer(String context, String question) {
        String q = question == null ? "" : question;
        if (q.contains("退款") || q.contains("退货")) {
            return "退款需要在系统内提交售后工单：我会根据你的真实订单预填申请（订单号、金额、原因），你确认后提交，状态会按“待审核 → 已通过 → 已退款”流转，可在“我的售后单”里查看进度。";
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
        return "我是平台在线客服。关于退款退货、物流发货、发票、订单状态等常见问题都可以问我；更复杂的问题会转人工处理。";
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
            退款/退货必须引导用户提交售后工单（可让 AI 预填），不得承诺系统外流程。
            工单状态流转：待审核（APPLIED）→ 已通过（APPROVED）→ 已退款（REFUNDED）；或被驳回（REJECTED）。
            涉及订单状态/金额时，只引用订单摘要中的真实数据；涉及政策时引用《来源》标题。
            若知识库未收录相关内容，明确说"该问题需要联系人工客服"。
            """;
}
