package com.insightforge.agent.core.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地确定性模型（离线桩）——默认 ModelClient。
 *
 * 为什么需要它：Agent 运行时是一套「调度/工具/记忆/反思」的通用骨架，
 * 真正调谁（DeepSeek / GPT / 本地模型）只是 ModelClient 一个实现。
 * 为了让整条管线在「无网络、无 API Key」的本地/测试环境也能跑通并演示完整闭环，
 * 我们提供一个确定性的本地实现：它不联网，而是按固定启发式产出合法的工具调用与方案。
 *
 * 行为（状态无关，靠 prompt 内容判断阶段）：
 *   1) 还没看到工具结果 → 请求调用 {@code historical_sales_analyzer}（演示 ReAct 的「先取数再决策」）。
 *   2) 已看到 TOOL_RESULT → 产出最终方案 JSON。
 *   若 {@code forceInvalidPlan=true}（仅测试用），则故意把售价定得低于成本，触发 Critic 拒绝 → 反思重试 → 正确失败。
 *
 * 这不是「假数据」：它真实驱动了工具执行、Critic 校验、重试、Trace 落库——
 * 只是把不确定的 LLM 推理换成了确定的脚本，方便教学与离线验证。生产换成 DeepSeekModelClient 即可。
 */
@Component
@Primary
@ConditionalOnProperty(name = "insightforge.agent.deepseek.enabled", havingValue = "false", matchIfMissing = true)
public class LocalDeterministicModelClient implements ModelClient {

    /** 测试开关：true 时强制产出不合规方案（售价<成本），用于演示 Critic 拒绝路径。 */
    @Value("${insightforge.agent.local.force-invalid-plan:false}")
    private boolean forceInvalidPlan;

    private static final Pattern ACTIVITY_ID = Pattern.compile("(?i)activity[_ ]?id\\s*[:=]\\s*(\\d+)");

    @Override
    public ModelResponse generate(ModelRequest req) {
        int tokenIn = estimateTokens(req.messages());
        boolean sawToolResult = req.messages().stream()
                .anyMatch(m -> m.content().contains("TOOL_RESULT:"));

        if (!sawToolResult) {
            // 第一轮：先调历史销量分析工具（体现「Agent 会先取数再决策」）
            long activityId = parseActivityId(req);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("activityId", activityId);
            args.put("lookbackDays", 30);
            return ModelResponse.tool(new ToolCall("historical_sales_analyzer", args), tokenIn, 30, 40);
        }

        // 工具结果已就位：产出结构化方案（JSON）。
        String plan = buildPlan(req);
        return ModelResponse.answer(plan, tokenIn, estimateTokens(plan), 40);
    }

    /** 构造最终方案 JSON。happy 路径售价≥成本；forceInvalidPlan 时售价<成本。 */
    private String buildPlan(ModelRequest req) {
        long activityId = parseActivityId(req);
        double costFloor = 5999.00;          // 真实场景由 memory/analyzer 拿到成本
        double repriceTo = forceInvalidPlan ? 5000.00 : 6299.00; // 不合规：低于成本
        return """
                {
                  "activityId": %d,
                  "pricing": { "repriceTo": %.2f, "costFloor": %.2f },
                  "reallocation": { "fromPool": "B", "toPool": "A", "amount": 50 },
                  "reasoning": "基于历史销量与竞品价，给出控盘预案"
                }
                """.formatted(activityId, repriceTo, costFloor);
    }

    private long parseActivityId(ModelRequest req) {
        for (ChatMessage m : req.messages()) {
            Matcher mt = ACTIVITY_ID.matcher(m.content());
            if (mt.find()) {
                return Long.parseLong(mt.group(1));
            }
        }
        return 1L; // 测试种子默认活动 id
    }

    /** 粗略 token 估算（中文按字符计，便于预算闸门演示；真实 DeepSeek 走 usage 字段）。 */
    static int estimateTokens(String text) {
        return Math.max(1, text.length() / 2);
    }

    private int estimateTokens(java.util.List<ChatMessage> messages) {
        int n = 0;
        for (ChatMessage m : messages) {
            n += estimateTokens(m.content());
        }
        return n;
    }
}
