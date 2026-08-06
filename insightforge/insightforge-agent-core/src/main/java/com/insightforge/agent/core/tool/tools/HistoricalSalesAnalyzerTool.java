package com.insightforge.agent.core.tool.tools;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.agent.core.memory.Artifact;
import com.insightforge.agent.core.tool.Tool;
import com.insightforge.agent.core.tool.ToolContext;
import com.insightforge.agent.core.tool.ToolResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史销量分析工具（设计文档 §5.1 内置工具 historical_sales_analyzer）。
 *
 * 这是「Agent 真正碰共享可变状态（只读）」的代表：它查 orders / activity_sku，
 * 算出累计销量、GMV、售罄率、流速，供下游定价/库存决策——也是 Critic 校验的数据基座。
 * 注意：只读，不改写库存（写命脉永远留给热路径 + 护栏规则引擎）。
 */
@Component
public class HistoricalSalesAnalyzerTool implements Tool {

    private final OrdersMapper ordersMapper;
    private final ActivitySkuMapper skuMapper;

    public HistoricalSalesAnalyzerTool(OrdersMapper ordersMapper, ActivitySkuMapper skuMapper) {
        this.ordersMapper = ordersMapper;
        this.skuMapper = skuMapper;
    }

    @Override
    public String name() {
        return "historical_sales_analyzer";
    }

    @Override
    public String description() {
        return "分析某活动的历史销量：累计销量、GMV、售罄率、流速，供定价/库存决策参考";
    }

    @Override
    public String parametersJson() {
        // 标准 JSON Schema：activityId / lookbackDays 必填整数
        return """
                {
                  "type": "object",
                  "properties": {
                    "activityId":   { "type": "integer", "description": "活动 id" },
                    "lookbackDays": { "type": "integer", "description": "回溯天数（演示用，不参与过滤）" }
                  },
                  "required": ["activityId", "lookbackDays"]
                }""";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long activityId = ((Number) args.get("activityId")).longValue();

        // 查该活动的历史订单与 SKU（真实 DB 读）
        List<Orders> orders = ordersMapper.selectList(
                new QueryWrapper<Orders>().eq("activity_id", activityId));
        List<ActivitySku> skus = skuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("activity_id", activityId));

        int historicalQty = orders.stream().mapToInt(Orders::getQty).sum();
        double avgPrice = skus.isEmpty() ? 0 : skus.get(0).getSeckillPrice().doubleValue();
        double historicalGmv = historicalQty * avgPrice;
        int totalStock = skus.stream().mapToInt(ActivitySku::getTotalStock).sum();
        int sold = skus.stream().mapToInt(ActivitySku::getSold).sum();
        double selloutRate = totalStock == 0 ? 0 : (double) sold / totalStock;

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("activityId", activityId);
        analysis.put("historicalOrders", orders.size());
        analysis.put("historicalQty", historicalQty);
        analysis.put("avgPrice", avgPrice);
        analysis.put("historicalGmv", Math.round(historicalGmv * 100.0) / 100.0);
        analysis.put("totalStock", totalStock);
        analysis.put("sold", sold);
        analysis.put("selloutRate", Math.round(selloutRate * 1000.0) / 10.0); // 百分比，1 位小数

        // 把分析结果存为 Artifact，下游 Agent / 规则引擎可复用（设计文档 §5.2 跨 Agent Artifact）
        ctx.artifactStore().put(Artifact.of("analysis-" + activityId, "sales-analysis", analysis));

        return ToolResult.ok(analysis);
    }
}
