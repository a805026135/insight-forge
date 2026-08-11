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
import com.insightforge.api.dto.OrderCandidate;
import com.insightforge.api.dto.ProductCandidate;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.AfterSaleMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 买家在线客服（Phase 19d/h）：
 *  - 平台知识 + 按需代理检索"指定商家的公开知识"（商品/活动）；
 *  - 每轮从问题识别商品 → 锁定商家，上下文按商家绑定：一旦提到另一家商品立即切换，
 *    绝不拿上一家商家的 RAG 回答本家问题；
 *  - 商家具体信息优先，但平台红线（退款时效/价格下限）永远优先，冲突时以平台政策为准并明示；
 *  - 无命中 → 商品候选让用户选择，或强制转人工，绝不编造；
 *  - 退款意图 → 真实订单草稿；问答历史持久化。
 */
@Service
public class BuyerAssistantService {

    private final ObjectProvider<DeepSeekModelClient> deepSeekModelClient;
    private final ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final BuyerChatHistoryService chatHistory;
    private final SupportTicketService supportTicketService;

    /** 用户 → 上一轮锁定的商家（用于追问沿用，一旦提到别家商品立即切换） */
    private final Map<Long, Long> lastMerchantByUser = new ConcurrentHashMap<>();
    private final Map<Long, String> lastMerchantNameByUser = new ConcurrentHashMap<>();

    public BuyerAssistantService(ObjectProvider<DeepSeekModelClient> deepSeekModelClient,
                                 ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider,
                                 OrdersMapper ordersMapper,
                                 OrderItemMapper orderItemMapper,
                                 ProductMapper productMapper,
                                 MerchantMapper merchantMapper,
                                 AfterSaleMapper afterSaleMapper,
                                 BuyerChatHistoryService chatHistory,
                                 SupportTicketService supportTicketService) {
        this.deepSeekModelClient = deepSeekModelClient;
        this.knowledgeBaseProvider = knowledgeBaseProvider;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.chatHistory = chatHistory;
        this.supportTicketService = supportTicketService;
    }

    public BuyerAssistantReply ask(long userId, String question, Long explicitMerchantId) {
        IntentData data = computeIntent(userId, question, explicitMerchantId);
        String answer;
        if (data.structuredAnswer() != null) {
            answer = data.structuredAnswer();
        } else if (!data.orderCandidates().isEmpty()) {
            answer = "你问的是哪一笔订单？选择后我按对应商家的信息回答。";
        } else if (!data.candidates().isEmpty()) {
            answer = "你问的是哪款商品？选择后我会为你查询对应商家的资料。";
        } else if (data.needHuman()) {
            answer = "这个问题知识库暂时无法给出可靠答案。为避免误导，建议转人工客服，或直接联系具体商家咨询。";
        } else {
            answer = generate(userId, buildContext(userId, question, data), question);
        }
        chatHistory.record(userId, explicitMerchantId, "user", question);
        chatHistory.record(userId, explicitMerchantId, "assistant", answer);
        return new BuyerAssistantReply(answer, data.needHuman(), data.merchants(), data.drafts(),
                data.candidates(), data.orderCandidates());
    }

    /** 意图识别（不生成、不记录）：无命中 → 转人工/商品候选；退款 → 草稿。 */
    public AssistantIntent intent(long userId, String question, Long explicitMerchantId) {
        IntentData d = computeIntent(userId, question, explicitMerchantId);
        boolean paused = explicitMerchantId != null && supportTicketService.isAgentPaused(userId, explicitMerchantId);
        return new AssistantIntent(d.needHuman(), d.merchants(), d.drafts(),
                d.candidates(), d.orderCandidates(), paused);
    }

    public void askStream(long userId, String question, Long explicitMerchantId, Consumer<String> onChunk) {
        if (explicitMerchantId != null && supportTicketService.isAgentPaused(userId, explicitMerchantId)) {
            chatHistory.record(userId, explicitMerchantId, "user", question);
            String pauseText = "?????" + merchantName(explicitMerchantId) + "??????????Agent ??????"
                    + "???????????????????? Agent????????? 10 ????????";
            chatHistory.record(userId, explicitMerchantId, "assistant", pauseText);
            for (int i = 0; i < pauseText.length(); i += 6) {
                onChunk.accept(pauseText.substring(i, Math.min(i + 6, pauseText.length())));
                try {
                    Thread.sleep(8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            return;
        }
        IntentData data = computeIntent(userId, question, explicitMerchantId);
        String text;
        if (data.structuredAnswer() != null) {
            text = data.structuredAnswer();
        } else if (!data.orderCandidates().isEmpty()) {
            text = "你问的是哪一笔订单？选择后我按对应商家的信息回答。";
        } else if (!data.candidates().isEmpty()) {
            text = "你问的是哪款商品？选择后我会为你查询对应商家的资料。";
        } else if (data.needHuman()) {
            text = "这个问题知识库暂时无法给出可靠答案。为避免误导，建议转人工客服，或直接联系具体商家咨询。";
        } else {
            text = generate(userId, buildContext(userId, question, data), question);
        }
        chatHistory.record(userId, explicitMerchantId, "user", question);
        chatHistory.record(userId, explicitMerchantId, "assistant", text);
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

    private record IntentData(boolean needHuman, List<MerchantContact> merchants,
                              List<AfterSaleDraft> drafts, List<ProductCandidate> candidates,
                              boolean refund, MerchantCtx mc,
                              List<KbSearchHit> platformHits, List<KbSearchHit> merchantHits,
                              List<OrderCandidate> orderCandidates, String structuredAnswer) {
    }

    private record MerchantCtx(Long merchantId, String merchantName, boolean explicit) {
    }

    private IntentData computeIntent(long userId, String question, Long explicitMerchantId) {
        boolean refund = isRefundIntent(question);
        List<AfterSaleDraft> drafts = refund ? buildRefundDrafts(userId) : List.of();
        if (refund) {
            // 退款问题优先走平台政策知识（解释流程）；有可退订单时再附加草稿
            KnowledgeBaseService kb = knowledgeBaseProvider.getIfAvailable();
            List<KbSearchHit> platform = kb == null ? List.of() : kb.search(0L, question, 3);
            if (!platform.isEmpty() || !drafts.isEmpty()) {
                return new IntentData(false, List.of(), drafts, List.of(), true, null,
                        platform, List.of(), List.of(), null);
            }
            return new IntentData(true, recentMerchants(userId), List.of(), List.of(), true, null,
                    List.of(), List.of(), List.of(), null);
        }

        // 电话/发货/售后类问题：先让用户确认是哪一笔订单，再按对应商家回答；
        // 若已显式锁定商家（用户已选定订单），跳过候选直接检索
        if (needsContextIntent(question) && explicitMerchantId == null) {
            List<OrderCandidate> orderCandidates = buildOrderCandidates(userId);
            if (!orderCandidates.isEmpty()) {
                return new IntentData(false, List.of(), List.of(), List.of(),
                        false, null, List.of(), List.of(), orderCandidates, null);
            }
        }

        // 订单状态类问题：结构化生成（不走 LLM，保证排版与真实数据）
        if (isOrderStatusIntent(question)) {
            return new IntentData(false, List.of(), List.of(), List.of(),
                    false, null, List.of(), List.of(), List.of(), buildOrderStatusAnswer(userId));
        }

        MerchantCtx mc = resolveMerchant(userId, question, explicitMerchantId);
        KnowledgeBaseService kb = knowledgeBaseProvider.getIfAvailable();
        List<KbSearchHit> platform = kb == null ? List.of() : kb.search(0L, question, 3);
        // 已锁定商家（显式选择或追问沿用）：放宽该商家检索的置信度门槛——
        // 用户已确认商家，把该商家最相关的知识交给模型即可，模型仍有"不足则转人工"约束。
        List<KbSearchHit> merchant = kb == null || mc.merchantId() == null
                ? List.of() : kb.searchMerchantPublic(mc.merchantId(), question, 3, 0.0);

        if (!platform.isEmpty() || !merchant.isEmpty()) {
            if (mc.merchantId() != null) {
                lastMerchantByUser.put(userId, mc.merchantId());
                lastMerchantNameByUser.put(userId, mc.merchantName());
            }
            return new IntentData(false, List.of(), List.of(), List.of(),
                    false, mc, platform, merchant, List.of(), null);
        }

        // 无命中：疑似商品问题 → 给商品候选；否则转人工
        List<ProductCandidate> candidates = mc.merchantId() == null ? matchCandidates(question) : List.of();
        if (!candidates.isEmpty()) {
            return new IntentData(false, List.of(), List.of(), candidates, false, mc,
                    List.of(), List.of(), List.of(), null);
        }
        List<MerchantContact> contacts = mc.merchantId() == null
                ? recentMerchants(userId)
                : List.of(new MerchantContact(mc.merchantId(), mc.merchantName()));
        return new IntentData(true, contacts, List.of(), List.of(), false, mc,
                List.of(), List.of(), List.of(), null);
    }

    private boolean needsContextIntent(String question) {
        String q = question == null ? "" : question;
        return q.contains("客服电话") || q.contains("联系方式") || q.contains("联系商家")
                || q.contains("怎么联系") || q.contains("售后电话") || q.contains("保修电话")
                || q.contains("电话") || q.contains("发货") || q.contains("物流")
                || q.contains("时效") || q.contains("什么时候到") || q.contains("多久能到")
                || q.contains("售后") || q.contains("保修");
    }

    private boolean isOrderStatusIntent(String question) {
        String q = question == null ? "" : question;
        // "订单状态/我的订单" 类问题走真实订单摘要；"发货要多久"等时效问题仍走平台知识
        return q.contains("订单");
    }

    private String buildOrderStatusAnswer(long userId) {
        List<Orders> orders = ordersMapper.selectList(new QueryWrapper<Orders>()
                .eq("user_id", userId).orderByDesc("created_at").last("LIMIT 5"));
        if (orders.isEmpty()) {
            return "你目前还没有订单，先去「限时秒杀」下一单吧。";
        }
        List<String> orderIds = orders.stream().map(Orders::getOrderId).toList();
        List<OrderItem> items = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().in("order_id", orderIds));
        Map<String, OrderItem> firstByOrder = new LinkedHashMap<>();
        for (OrderItem it : items) {
            firstByOrder.putIfAbsent(it.getOrderId(), it);
        }
        StringBuilder sb = new StringBuilder("你的最近订单状态如下：\n");
        int i = 1;
        for (Orders o : orders) {
            OrderItem it = firstByOrder.get(o.getOrderId());
            String productName = it != null ? it.getProductName() : null;
            if (productName == null && o.getSkuId() != null) {
                Product p = productMapper.selectById(o.getSkuId());
                productName = p == null ? null : p.getName();
            }
            sb.append(i++).append(". 【").append(productName == null ? "未知商品" : productName)
              .append("】（订单#").append(o.getOrderId()).append("）：")
              .append(statusZh(o.getStatus()))
              .append("；时间 ").append(o.getCreatedAt()).append("\n");
        }
        return sb.toString();
    }

    private List<OrderCandidate> buildOrderCandidates(long userId) {
        List<Orders> orders = ordersMapper.selectList(new QueryWrapper<Orders>()
                .eq("user_id", userId).in("status", "PAY", "DONE", "SHIPPED")
                .orderByDesc("created_at").last("LIMIT 5"));
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = orders.stream().map(Orders::getOrderId).toList();
        List<OrderItem> items = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().in("order_id", orderIds));
        Map<String, OrderItem> firstByOrder = new LinkedHashMap<>();
        for (OrderItem it : items) {
            firstByOrder.putIfAbsent(it.getOrderId(), it);
        }
        List<OrderCandidate> out = new ArrayList<>();
        for (Orders o : orders) {
            OrderItem it = firstByOrder.get(o.getOrderId());
            // order_item 可能缺失（秒杀直购路径），用 product 表按 skuId 反查商家/商品名兜底
            Long merchantId = it == null ? null : it.getMerchantId();
            String merchantName = it == null ? null : it.getMerchantName();
            String productName = it == null ? null : it.getProductName();
            if (it == null && o.getSkuId() != null) {
                Product p = productMapper.selectById(o.getSkuId());
                if (p != null) {
                    productName = p.getName();
                    merchantId = p.getMerchantId();
                    if (merchantId != null) {
                        Merchant m = merchantMapper.selectById(merchantId);
                        merchantName = m == null ? "商家" + merchantId : m.getName();
                    }
                }
            }
            out.add(new OrderCandidate(o.getOrderId(),
                    merchantId, merchantName, productName,
                    o.getTotalAmount()));
        }
        return out;
    }

    /** 商品识别 → 锁定商家；追问（短句/指代词）沿用上一轮商家；提到别家商品立即切换。 */
    private MerchantCtx resolveMerchant(long userId, String question, Long explicitMerchantId) {
        // 用户在前端选择了具体商品 → 显式锁定对应商家（结构化，而非把商家名拼进文本）
        if (explicitMerchantId != null && explicitMerchantId > 0) {
            Merchant m = merchantMapper.selectById(explicitMerchantId);
            return new MerchantCtx(explicitMerchantId,
                    m == null ? "商家" + explicitMerchantId : m.getName(), true);
        }
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("status", "ON_SALE"));
        List<Product> hits = products.stream()
                .filter(p -> p.getName() != null && question.contains(p.getName()))
                .toList();
        if (hits.isEmpty()) {
            hits = products.stream()
                    .filter(p -> p.getName() != null && p.getName().length() >= 6
                            && question.contains(p.getName().substring(0, 6)))
                    .toList();
        }
        Set<Long> merchants = hits.stream().map(Product::getMerchantId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        // 恰好属于一家商家 → 直接锁定；多商家同名商品 → 返回 null，由 matchCandidates 让用户选择
        if (merchants.size() == 1) {
            Long mid = merchants.iterator().next();
            Merchant m = merchantMapper.selectById(mid);
            return new MerchantCtx(mid, m == null ? "商家" + mid : m.getName(), true);
        }
        if (isFollowUp(question) && lastMerchantByUser.containsKey(userId)) {
            return new MerchantCtx(lastMerchantByUser.get(userId),
                    lastMerchantNameByUser.getOrDefault(userId, "商家" + lastMerchantByUser.get(userId)), false);
        }
        return new MerchantCtx(null, null, false);
    }

    private boolean isFollowUp(String question) {
        String q = question == null ? "" : question.trim();
        return q.length() <= 12 && (q.contains("那") || q.contains("它") || q.contains("这")
                || q.contains("库存") || q.contains("价格") || q.contains("卖点")
                || q.contains("活动") || q.contains("保修") || q.contains("售后"));
    }

    private List<ProductCandidate> matchCandidates(String question) {
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("status", "ON_SALE"));
        List<ProductCandidate> out = new ArrayList<>();
        for (Product p : products) {
            if (p.getName() == null) {
                continue;
            }
            boolean hit = question.contains(p.getName())
                    || (p.getName().length() >= 6 && question.contains(p.getName().substring(0, 6)));
            if (hit && p.getMerchantId() != null) {
                Merchant m = merchantMapper.selectById(p.getMerchantId());
                out.add(new ProductCandidate(p.getId(), p.getName(), p.getMerchantId(),
                        m == null ? "商家" + p.getMerchantId() : m.getName(), p.getImageUrl()));
            }
        }
        return out.stream().distinct().limit(5).collect(Collectors.toList());
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
                distinct.putIfAbsent(it.getMerchantId(),
                        it.getMerchantName() == null ? "商家" + it.getMerchantId() : it.getMerchantName());
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

    private String buildContext(long userId, String question, IntentData data) {
        StringBuilder sb = new StringBuilder();
        // 多轮上下文：注入最近 4 轮问答，让"那价格呢"这类追问能接上上文
        List<com.insightforge.persistence.entity.BuyerChatMessage> history =
                chatHistory.history(userId, data.mc() == null ? null : data.mc().merchantId(), 8);
        if (history.size() >= 2) {
            sb.append("【对话历史】（最近几轮，仅作上下文参考）\n");
            int start = Math.max(0, history.size() - 8);
            for (int i = start; i < history.size(); i++) {
                var m = history.get(i);
                String prefix = "user".equals(m.getRole()) ? "问" : "答";
                String content = m.getContent() == null ? "" : m.getContent();
                sb.append(prefix).append("：")
                  .append(content.length() > 200 ? content.substring(0, 200) + "…" : content)
                  .append("\n");
            }
        }
        List<Orders> orders = ordersMapper.selectList(
                new QueryWrapper<Orders>().eq("user_id", userId)
                        .orderByDesc("created_at").last("LIMIT 5"));
        if (orders.isEmpty()) {
            sb.append("【我的最近订单】暂无订单。\n");
        } else {
            List<String> orderIds = orders.stream().map(Orders::getOrderId).toList();
            List<OrderItem> items = orderItemMapper.selectList(
                    new QueryWrapper<OrderItem>().in("order_id", orderIds));
            Map<String, OrderItem> firstByOrder = new LinkedHashMap<>();
            for (OrderItem it : items) {
                firstByOrder.putIfAbsent(it.getOrderId(), it);
            }
            sb.append("【我的最近订单】（最多 5 单，收货信息已脱敏）\n");
            for (Orders o : orders) {
                OrderItem it = firstByOrder.get(o.getOrderId());
                String productName = null;
                if (it != null) {
                    productName = it.getProductName();
                } else if (o.getSkuId() != null) {
                    Product p = productMapper.selectById(o.getSkuId());
                    productName = p == null ? null : p.getName();
                }
                sb.append("- 商品【").append(productName == null ? "未知商品" : productName)
                  .append("】（订单#").append(o.getOrderId())
                  .append("）：状态 ").append(statusZh(o.getStatus()))
                  .append("；金额 ¥").append(o.getTotalAmount())
                  .append("；时间 ").append(o.getCreatedAt())
                  .append("；收货人 ").append(mask(o.getReceiver()))
                  .append("；电话 ").append(maskPhone(o.getPhone()))
                  .append("；地址 ").append(maskAddress(o.getAddressSnapshot()))
                  .append("\n");
            }
        }
        if (data.merchantHits() != null && !data.merchantHits().isEmpty()) {
            sb.append("\n【商家公开知识】（检索自《").append(data.mc().merchantName()).append("》的知识库）\n");
            for (KbSearchHit h : data.merchantHits()) {
                sb.append("- 《").append(data.mc().merchantName()).append("·").append(h.title()).append("》")
                  .append(h.content().replace("\n", " ")).append("\n");
            }
        }
        if (data.platformHits() != null && !data.platformHits().isEmpty()) {
            sb.append("\n【平台公开知识】（检索自平台知识库）\n");
            for (KbSearchHit h : data.platformHits()) {
                sb.append("- 《平台·").append(h.title()).append("》")
                  .append(h.content().replace("\n", " ")).append("\n");
            }
        }
        sb.append("回答规则：\n")
          .append("1) 只使用当前问题对应商家的资料（若涉及商品，商家以【商家公开知识】标注的为准），不得用其他商家的信息回答本问题；\n")
          .append("2) 若商家说明与平台政策冲突，以平台政策为准并指出差异；\n")
          .append("3) 回答标注《来源》标题时必须带前缀完整标题：《平台·xxx》或《商家名·xxx》；资料不足时明确说\"知识库未收录，请转人工客服\"，绝不自行推断。\n");
        return sb.toString();
    }

    private String statusZh(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "CREATE_ORDER" -> "待支付";
            case "PAY" -> "待发货";
            case "SHIPPED" -> "已发货";
            case "DONE" -> "已完成";
            case "CANCEL" -> "已取消";
            default -> status;
        };
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

    private String merchantName(long merchantId) {
        Merchant m = merchantMapper.selectById(merchantId);
        return m == null || m.getName() == null ? "??" + merchantId : m.getName();
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
        return "这个问题知识库暂时无法给出可靠答案。为避免误导，建议转人工客服，或直接联系具体商家咨询。";
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
            只能基于【我的最近订单】【商家公开知识】【平台公开知识】回答。
            只使用当前问题对应商家的资料回答，不得混用其他商家的信息。
            若商家说明与平台政策冲突，以平台政策为准并指出差异。
            订单状态用中文描述（待支付/待发货/已发货/已完成/已取消），并说明对应商品。
            回答必须标注带前缀的《来源》完整标题（《平台·xxx》或《商家名·xxx》）；资料不足以回答时，必须说"知识库未收录，请转人工客服"，绝不编造政策条款、价格或订单信息。
            退款/退货引导用户提交售后工单；工单状态：待审核（APPLIED）→ 已通过（APPROVED）→ 已退款（REFUNDED）；或被驳回（REJECTED）。
            """;
}
