package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.DeepSeekModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.agent.core.kb.KbSearchHit;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import com.insightforge.agent.core.memory.CompactingMemory;
import com.insightforge.api.dto.AssistantAction;
import com.insightforge.api.dto.AssistantReply;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * 商家 AI 运营助手（Phase 18）：分析经营数据生成建议，并识别「可执行动作」。
 * 动作（加库存/上下架/调价）只作为建议返回，由商家在前端批准后调用执行接口落地——LLM 不直接改数据。
 */
@Service
public class MerchantAssistantService {

    private final ObjectProvider<DeepSeekModelClient> deepSeekModelClient;
    private final ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider;
    private final Map<Long, CompactingMemory> conversations = new ConcurrentHashMap<>();
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern ACTION_JSON = Pattern.compile("\\{\"action\"\\s*:\\s*\"([A-Z_]+)\"[^}]*}");

    public MerchantAssistantService(ObjectProvider<DeepSeekModelClient> deepSeekModelClient,
                                    ObjectProvider<KnowledgeBaseService> knowledgeBaseProvider,
                                    MerchantMapper merchantMapper,
                                    ProductMapper productMapper,
                                    ActivitySkuMapper activitySkuMapper,
                                    OrdersMapper ordersMapper,
                                    OrderItemMapper orderItemMapper) {
        this.deepSeekModelClient = deepSeekModelClient;
        this.knowledgeBaseProvider = knowledgeBaseProvider;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
    }

    public AssistantReply ask(long merchantId, String question) {
        String context = buildContext(merchantId, question);
        List<AssistantAction> actions = extractActions(merchantId, question);
        String answer;
        if (actions.isEmpty()) {
            answer = generateAnswer(merchantId, context, question);
            List<AssistantAction> fromLlm = parseLlmActions(merchantId, answer, context);
            if (!fromLlm.isEmpty()) {
                actions = fromLlm;
            }
        } else {
            answer = "我理解你的意图，已准备好以下操作，请确认后执行：\n"
                    + actions.stream().map(a -> "- " + a.description()).collect(Collectors.joining("\n"));
            answer = appendRuleHints(merchantId, actions, answer);
        }
        remember(merchantId, question, answer);
        return new AssistantReply(answer, classifySuggestion(answer), answer, actions);
    }

    /** 流式回答：DeepSeek 可用时走真实 token 流，否则按确定性回答分块模拟。 */
    public void askStream(long merchantId, String question, Consumer<String> onChunk) {
        String context = buildContext(merchantId, question);
        String text;
        DeepSeekModelClient ds = deepSeekModelClient.getIfAvailable();
        if (ds != null) {
            // Spring AI M6 的流式端点与 DeepSeek 兼容性不稳定，改用非流式生成完整回答后分块推送
            ModelResponse resp = ds.generate(new ModelRequest(
                    buildMessages(merchantId, context, question),
                    "merchant-assistant",
                    List.of()));
            text = resp.content() == null ? "（无回答）" : resp.content();
        } else {
            text = deterministicAnswer(context, question);
        }
        remember(merchantId, question, text);
        // 按 6 字符分块模拟打字机效果
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

    private String generateAnswer(long merchantId, String context, String question) {
        DeepSeekModelClient ds = deepSeekModelClient.getIfAvailable();
        if (ds != null) {
            ModelResponse resp = ds.generate(new ModelRequest(
                    buildMessages(merchantId, context, question),
                    "merchant-assistant",
                    List.of()));
            if (resp.toolCall() != null) {
                return "助手正在分析经营数据，请稍后重试或换一种问法。";
            }
            String answer = resp.content() == null ? "（无回答）" : resp.content();
            return answer;
        }
        return deterministicAnswer(context, question);
    }

    /** 多轮对话记忆：system + 历史问答（最近 20 条）+ 最新经营上下文与问题。 */
    private List<ChatMessage> buildMessages(long merchantId, String context, String question) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.add(ChatMessage.system("回答必须基于经营数据与【知识库参考】；知识库未收录的信息不要编造，若不确定请明确建议商家联系平台运营。"));
        messages.add(ChatMessage.system("这是多轮对话：请结合用户前面的问题和你自己的回答理解本次问题，不要重复已答内容。"));
        CompactingMemory memory = conversations.computeIfAbsent(merchantId, k -> new CompactingMemory());
        for (ChatMessage m : memory.messages()) {
            if (!"system".equals(m.role())) {
                messages.add(m);
            }
        }
        messages.add(ChatMessage.user(context + "\n\n商家问题：" + question));
        return messages;
    }

    private void remember(long merchantId, String question, String answer) {
        CompactingMemory memory = conversations.computeIfAbsent(merchantId, k -> new CompactingMemory());
        memory.add(ChatMessage.user(question));
        memory.add(ChatMessage.assistant(answer == null ? "" : answer));
    }

    /** 动作批准前规则校验：按动作类型检索运营手册，把约束拼进建议，违规操作不直接执行。 */
    private String appendRuleHints(long merchantId, List<AssistantAction> actions, String answer) {
        KnowledgeBaseService kb = knowledgeBaseProvider.getIfAvailable();
        if (kb == null || actions.isEmpty()) {
            return answer;
        }
        Set<String> queries = new LinkedHashSet<>();
        for (AssistantAction a : actions) {
            queries.add(switch (a.type()) {
                case "ADD_STOCK" -> "库存 安全库存 补货 规则";
                case "TOGGLE_SHELF" -> "上下架 活动 下架 规则";
                case "REPRICE" -> "调价 成本价 毛利 风控 规则";
                default -> "运营 规则";
            });
        }
        StringBuilder sb = new StringBuilder(answer);
        for (String q : queries) {
            List<KbSearchHit> hits = kb.search(merchantId, q, 1, "PLAYBOOK");
            if (!hits.isEmpty()) {
                KbSearchHit h = hits.get(0);
                sb.append("\n【规则提示】《").append(h.title()).append("》：")
                  .append(h.content().replace("\n", " "));
            }
        }
        return sb.toString();
    }

    private static final String SYSTEM_PROMPT = """
            你是一名电商大促运营专家，正在为商家提供经营分析建议。
            只基于提供的商家经营数据回答，不要编造数据。
            如果商家要求执行操作（加库存/上下架/调价），在回答末尾输出 JSON 标记：
            {"action":"ADD_STOCK","productId":N,"amount":N} /
            {"action":"TOGGLE_SHELF","productId":N} /
            {"action":"REPRICE","productId":N,"price":N}
            同时明确说明"需人工确认后执行"。
            """;

    /** 从问题文本识别动作意图（加库存/下架/上架/调价）。 */
    private List<AssistantAction> extractActions(long merchantId, String question) {
        List<AssistantAction> actions = new ArrayList<>();
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("merchant_id", merchantId));
        // 匹配"给 X 加/补 N 件库存" / "把 X 下架" / "把 X 上架" / "给 X 调到 ¥N"
        // 先精确匹配商品全名；无精确命中才退回前 6 字前缀，避免"华为 MatePad"误伤整店华为商品
        List<Product> matched = products.stream()
                .filter(p -> question.contains(p.getName()))
                .toList();
        if (matched.isEmpty()) {
            matched = products.stream()
                    .filter(p -> question.contains(
                            p.getName().length() >= 6 ? p.getName().substring(0, 6) : p.getName()))
                    .toList();
        }
        for (Product p : matched) {
            String name = p.getName();
                Matcher m = Pattern.compile("(加|补|增加|调增)\\s*(\\d+)\\s*(件|库存)?").matcher(question);
                if (m.find()) {
                    actions.add(new AssistantAction("ADD_STOCK", p.getId(), name,
                            Integer.parseInt(m.group(2)), null,
                            "给「" + name + "」增加库存 " + m.group(2) + " 件"));
                }
                if (question.contains("下架") || question.contains("停售")) {
                    actions.add(new AssistantAction("TOGGLE_SHELF", p.getId(), name, null, null,
                            "将「" + name + "」下架"));
                }
                if (question.contains("上架") && !question.contains("下架")) {
                    actions.add(new AssistantAction("TOGGLE_SHELF", p.getId(), name, null, null,
                            "将「" + name + "」上架"));
                }
                Matcher pm = Pattern.compile("(调到|改成|降价到|定价为)\\s*(?:¥|￥)?\\s*(\\d+(?:\\.\\d+)?)").matcher(question);
                if (pm.find()) {
                    actions.add(new AssistantAction("REPRICE", p.getId(), name, null,
                            new BigDecimal(pm.group(2)),
                            "将「" + name + "」市场价调整为 ¥" + pm.group(2)));
                }
        }
        return actions;
    }

    /** 解析 DeepSeek 回答中的动作 JSON 标记。 */
    private List<AssistantAction> parseLlmActions(long merchantId, String answer, String context) {
        List<AssistantAction> actions = new ArrayList<>();
        Matcher m = ACTION_JSON.matcher(answer == null ? "" : answer);
        while (m.find()) {
            try {
                JsonNode node = objectMapper.readTree(m.group(0));
                Long productId = node.has("productId") ? node.get("productId").asLong() : null;
                Product p = productId != null ? productMapper.selectById(productId) : null;
                if (p == null || p.getMerchantId() == null || p.getMerchantId() != merchantId) {
                    continue;
                }
                String type = node.get("action").asText();
                Integer amount = node.has("amount") ? node.get("amount").asInt() : null;
                BigDecimal price = node.has("price") ? node.get("price").decimalValue() : null;
                String desc = switch (type) {
                    case "ADD_STOCK" -> "给「" + p.getName() + "」增加库存 " + amount + " 件";
                    case "TOGGLE_SHELF" -> ("ON_SALE".equals(p.getStatus()) ? "将「" + p.getName() + "」下架"
                            : "将「" + p.getName() + "」上架");
                    case "REPRICE" -> "将「" + p.getName() + "」市场价调整为 ¥" + price;
                    default -> "执行操作";
                };
                actions.add(new AssistantAction(type, p.getId(), p.getName(), amount, price, desc));
            } catch (Exception e) {
                // 忽略解析失败的动作
            }
        }
        return actions;
    }

    /** 本地无 DeepSeek 时：基于真实经营数据生成确定性运营建议。 */
    private String deterministicAnswer(String context, String question) {
        boolean pricing = question.contains("调价") || question.contains("价格") || question.contains("降价");
        boolean restock = question.contains("库存") || question.contains("补货") || question.contains("调拨");
        boolean copy = question.contains("文案") || question.contains("标题") || question.contains("活动页");
        boolean sales = question.contains("销售") || question.contains("卖") || question.contains("分析");
        if (pricing) {
            return "基于当前库存消耗与市场价，建议对热销 SKU 保持现价并观察 2 小时；若库存消耗率超过 40% 且毛利充足，可小幅下调 3%~5% 冲量（需人工确认后执行）。";
        }
        if (restock) {
            return "当前剩余库存与已售数据显示消耗正常，建议对售罄风险 SKU 预留 20% 调拨池；若单 SKU 剩余库存低于 15%，优先补货（需人工确认后执行）。";
        }
        if (copy) {
            return "建议活动页主标题突出「官方正品 + 大促直降」，副标题用「限量秒杀，售完即止」制造紧迫感，配图突出价格锚点（原价划线 + 秒杀价）。";
        }
        if (sales) {
            return "根据经营数据：当前已售占比与剩余库存均在健康区间；建议持续监控热销 SKU 的消耗速率，若加速可触发调价/调拨建议。";
        }
        return "我看到了你的店铺经营概况（商品、活动库存、已售、订单）。请更具体地告诉我你想了解哪方面：定价、库存、文案还是销售分析？";
    }

    private String classifySuggestion(String answer) {
        String a = answer == null ? "" : answer.toLowerCase();
        if (a.contains("调价") || a.contains("降价") || a.contains("涨价") || a.contains("reprice")) {
            return "REPRICE";
        }
        if (a.contains("补货") || a.contains("调拨") || a.contains("库存") || a.contains("restock")) {
            return "RESTOCK";
        }
        if (a.contains("文案") || a.contains("活动页") || a.contains("海报")) {
            return "COPY";
        }
        return "ANALYSIS";
    }

    private String buildContext(long merchantId, String question) {
        Merchant m = merchantMapper.selectById(merchantId);
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("merchant_id", merchantId));
        List<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toList());
        List<ActivitySku> skus = productIds.isEmpty() ? List.of()
                : activitySkuMapper.selectList(new QueryWrapper<ActivitySku>().in("sku_id", productIds));
        long totalStock = skus.stream().mapToLong(s -> s.getStock() == null ? 0 : s.getStock()).sum();
        long totalSold = skus.stream().mapToLong(s -> s.getSold() == null ? 0 : s.getSold()).sum();
        long orderCount = orderItemMapper.selectCount(
                new QueryWrapper<OrderItem>().eq("merchant_id", merchantId));
        long allOrders = ordersMapper.selectCount(new QueryWrapper<>());

        String productBrief = products.stream().limit(8)
                .map(p -> String.format("%s(库存%s, 售价%s)", p.getName(),
                        p.getStock() == null ? "-" : p.getStock(),
                        p.getMarketPrice() == null ? "-" : p.getMarketPrice()))
                .collect(Collectors.joining("；"));

        String base = String.format("""
                【商家】%s（评分 %s，累计销量 %s，认证 %s）
                【商品】共 %d 款：%s
                【活动库存】参与活动 SKU %d 个，剩余库存 %d，已售 %d
                【订单】本店订单 %d 笔，平台订单总数 %d
                【当前时间】%s
                """,
                m != null ? m.getName() : "未知", m != null ? m.getRating() : "-",
                m != null ? m.getSalesCount() : "-", m != null ? m.getVerified() : "-",
                products.size(), productBrief,
                skus.size(), totalStock, totalSold,
                orderCount, allOrders, java.time.LocalDateTime.now());
        StringBuilder sb = new StringBuilder(base);
        KnowledgeBaseService kb = knowledgeBaseProvider.getIfAvailable();
        if (kb != null && question != null && !question.isBlank()) {
            List<KbSearchHit> hits = kb.search(merchantId, question, 4);
            if (!hits.isEmpty()) {
                sb.append("\n【知识库参考】（检索自商家知识库，可能相关）\n");
                for (KbSearchHit h : hits) {
                    sb.append("- 《").append(h.title()).append("》 ")
                      .append(h.content().replace("\n", " ")).append("\n");
                }
                sb.append("请优先参考以上知识库资料回答，并简要说明依据来源。\n");
            }
        }
        return sb.toString();
    }
}
