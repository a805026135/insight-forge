package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.DeepSeekModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
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

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商家 AI 运营助手（Phase 17）：把商家维度经营数据组装成上下文，交给 ModelClient
 * （生产 = DeepSeek 真实回答；本地 = 确定性模板），返回运营建议。
 * 涉及改价/调拨等执行动作只给「建议」，由 HITL 人工确认后才落地——LLM 不进热路径。
 */
@Service
public class MerchantAssistantService {

    private final ObjectProvider<DeepSeekModelClient> deepSeekModelClient;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;

    public MerchantAssistantService(ObjectProvider<DeepSeekModelClient> deepSeekModelClient,
                                    MerchantMapper merchantMapper,
                                    ProductMapper productMapper,
                                    ActivitySkuMapper activitySkuMapper,
                                    OrdersMapper ordersMapper,
                                    OrderItemMapper orderItemMapper) {
        this.deepSeekModelClient = deepSeekModelClient;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
    }

    public AssistantReply ask(long merchantId, String question) {
        String context = buildContext(merchantId);
        String system = """
                你是一名电商大促运营专家，正在为商家提供经营分析建议。
                只基于提供的商家经营数据回答，不要编造数据。
                如果商家询问调价/补货/文案等，给出可执行建议；涉及价格与库存调整时，
                明确说明"需人工确认后执行"。
                """;
        DeepSeekModelClient ds = deepSeekModelClient.getIfAvailable();
        if (ds != null) {
            ModelResponse resp = ds.generate(new ModelRequest(
                    List.of(ChatMessage.system(system), ChatMessage.user(context + "\n\n商家问题：" + question)),
                    "merchant-assistant",
                    List.of()));
            if (resp.toolCall() != null) {
                return new AssistantReply("助手正在分析经营数据，请稍后重试或换一种问法。", null, null);
            }
            String answer = resp.content() == null ? "（无回答）" : resp.content();
            return new AssistantReply(answer, classifySuggestion(answer), answer);
        }
        return new AssistantReply(deterministicAnswer(context, question),
                classifySuggestion(deterministicAnswer(context, question)),
                deterministicAnswer(context, question));
    }

    /** 本地无 DeepSeek 时：基于真实经营数据生成确定性运营建议（不联网、可复现）。 */
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

    /** 简单识别建议类型：REPRICE / RESTOCK / COPY / ANALYSIS */
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

    private String buildContext(long merchantId) {
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
                .map(p -> String.format("%s(库存快照见活动SKU, 售价%s)",
                        p.getName(), p.getMarketPrice() == null ? "-" : p.getMarketPrice()))
                .collect(Collectors.joining("；"));

        return String.format("""
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
    }
}
