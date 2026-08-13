package com.insightforge.agent.core.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.agent.core.memory.Artifact;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.ModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真 LLM 角色 Agent（Phase 14.2：deepseek.enabled=true 时启用）。
 *
 * 与 {@link LocalRoleAgent} 同一接口：每个角色（选品/定价/预热/风控）把子任务
 * 翻译成一段 prompt 调 DeepSeek，解析返回的 JSON 作为结构化产物 Artifact，
 * 交给 GuardrailPolicyAgent 汇编 DSL —— 与本地桩产出格式完全一致。
 *
 * 可靠性设计（LLM 进不了热路径，但也不能让离线规划裸奔）：
 *   - 只输出 JSON 的强约束 prompt + 去除 markdown 代码围栏后解析；
 *   - 解析失败/网络抖动 → 降级到确定性启发式产物（与 LocalRoleAgent 相同），
 *     后续 DslCompiler + IndependentCritic 仍做两道硬校验（Fail-Close）；
 *   - forceInvalid 测试开关保持语义：故意给低于成本的售价，驱动 Critic 拒绝路径。
 */
@Component
@ConditionalOnProperty(name = "insightforge.agent.deepseek.enabled", havingValue = "true")
public class DeepSeekRoleAgent implements RoleAgent {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekRoleAgent.class);

    private static final Pattern FENCE = Pattern.compile("(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String modelName;

    public DeepSeekRoleAgent(ModelClient modelClient,
                             @Value("${insightforge.agent.deepseek.model:deepseek-chat}") String modelName) {
        this.modelClient = modelClient;
        this.modelName = modelName;
    }

    @Override
    public Artifact run(SubTask task, PlanningContext ctx) {
        return runMeasured(task, ctx).artifact();
    }

    @Override
    public RoleAgentResult runMeasured(SubTask task, PlanningContext ctx) {
        try {
            ModelRequest req = new ModelRequest(List.of(
                    ChatMessage.system("你是 InsightForge 的离线运营规划角色 Agent。"
                            + "只输出一个合法 JSON 对象，不要 markdown 代码块，不要任何多余文字。"),
                    ChatMessage.user(buildPrompt(task, ctx))), modelName, List.of());
            ModelResponse resp = modelClient.generate(req);
            JsonNode node = objectMapper.readTree(stripFences(resp.content()));
            Map<String, Object> out = toMap(node);
            log.info("DeepSeekRoleAgent[{}] 模型 {} 产出: {}", task.role(), modelName, out);
            Artifact artifact = Artifact.of(task.role().name().toLowerCase() + "-" + task.activityId(),
                    task.role().name().toLowerCase(), out);
            return new RoleAgentResult(artifact, resp.tokenIn(), resp.tokenOut(), resp.latencyMs());
        } catch (Exception e) {
            // LLM 抖动 / 输出不合规 JSON：降级确定性启发式（DSL 编译与 Critic 仍兜底，绝不裸奔）
            log.warn("DeepSeekRoleAgent[{}] 调用或解析失败，降级确定性产物: {}",
                    task.role(), e.getMessage());
            return new RoleAgentResult(deterministicFallback(task, ctx), 0, 0, 0);
        }
    }

    private String buildPrompt(SubTask task, PlanningContext ctx) {
        return switch (task.role()) {
            case SELECTOR -> """
                    活动 id=%d，目标：%s。
                    请产出选品建议，JSON 形如：
                    {"includeSkus":[101,102],"stockRatioThreshold":0.2}
                    """.formatted(task.activityId(), ctx.goal());
            case PRICER -> {
                if (ctx.forceInvalid()) {
                    yield "成本 costFloor=%.2f。请产出定价 JSON，故意让 repriceTo < costFloor（测试护栏拦截用）。"
                            .formatted(ctx.costFloor());
                }
                yield """
                        成本 costFloor=%.2f。
                        请产出定价 JSON：{"repriceTo": <建议售价，不得低于 costFloor，默认成本上浮10%%>,"costFloor":%.2f}
                        只输出 JSON。
                        """.formatted(ctx.costFloor(), ctx.costFloor());
            }
            case WARMUP -> """
                    活动 id=%d。请产出库存预热/调拨预案 JSON：
                    {"reallocateAmount":50,"targetSegment":"seg_hot"}
                    （amount 必须 >= 0）
                    """.formatted(task.activityId());
            case RISK -> """
                    活动 id=%d。请产出风控规则 JSON：
                    {"alertOnVelocityDrop":true}
                    """.formatted(task.activityId());
            default -> "{}";
        };
    }

    /** 与 LocalRoleAgent 完全一致的确定性兜底（保证产物格式与本地桩相同）。 */
    private Artifact deterministicFallback(SubTask task, PlanningContext ctx) {
        return switch (task.role()) {
            case SELECTOR -> Artifact.of("sel-" + task.activityId(), "selection",
                    Map.of("includeSkus", List.of(101, 102), "stockRatioThreshold", 0.2));
            case PRICER -> {
                BigDecimal cost = ctx.costFloor();
                BigDecimal reprice = ctx.forceInvalid()
                        ? cost.subtract(BigDecimal.valueOf(500))
                        : cost.multiply(BigDecimal.valueOf(1.10));
                yield Artifact.of("price-" + task.activityId(), "pricing",
                        Map.of("repriceTo", reprice, "costFloor", cost));
            }
            case WARMUP -> Artifact.of("warm-" + task.activityId(), "warmup",
                    Map.of("reallocateAmount", 50, "targetSegment", "seg_hot"));
            case RISK -> Artifact.of("risk-" + task.activityId(), "risk",
                    Map.of("alertOnVelocityDrop", true));
            default -> null;
        };
    }

    private String stripFences(String text) {
        if (text == null) {
            throw new IllegalArgumentException("模型返回为空");
        }
        Matcher m = FENCE.matcher(text.trim());
        return m.matches() ? m.group(1).trim() : text.trim();
    }

    private Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            map.put(e.getKey(), value(e.getValue()));
        }
        return map;
    }

    private Object value(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(x -> list.add(value(x)));
            return list;
        }
        if (n.isObject()) {
            return toMap(n);
        }
        return n.asText();
    }
}
