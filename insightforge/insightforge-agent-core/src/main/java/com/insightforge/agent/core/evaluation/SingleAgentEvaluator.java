package com.insightforge.agent.core.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.agent.core.memory.Artifact;
import com.insightforge.agent.core.model.ChatMessage;
import com.insightforge.agent.core.model.ModelClient;
import com.insightforge.agent.core.model.ModelRequest;
import com.insightforge.agent.core.model.ModelResponse;
import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.DslCompileResult;
import com.insightforge.agent.core.planning.DslCompiler;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailPolicyAgent;
import com.insightforge.agent.core.planning.IndependentCritic;
import com.insightforge.agent.core.planning.PlanningContext;
import com.insightforge.agent.core.planning.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-model-call baseline used only for a fair single-Agent vs multi-Agent evaluation. */
@Component
public class SingleAgentEvaluator {
    private final ModelClient modelClient;
    private final GuardrailPolicyAgent policyAgent;
    private final DslCompiler compiler;
    private final IndependentCritic critic;
    private final ObjectMapper json = new ObjectMapper();
    private final String model;
    private final BigDecimal tokenPrice;

    public SingleAgentEvaluator(ModelClient modelClient, GuardrailPolicyAgent policyAgent,
                                DslCompiler compiler, IndependentCritic critic,
                                @Value("${insightforge.agent.deepseek.model:deepseek-chat}") String model,
                                @Value("${insightforge.agent.budget.token-price-usd-per-1k:0.0005}") BigDecimal tokenPrice) {
        this.modelClient = modelClient;
        this.policyAgent = policyAgent;
        this.compiler = compiler;
        this.critic = critic;
        this.model = model;
        this.tokenPrice = tokenPrice;
    }

    public Result run(long activityId, String goal, boolean forceInvalid) {
        long started = System.nanoTime();
        ModelResponse response = null;
        try {
            BigDecimal cost = new BigDecimal("5999.00");
            response = modelClient.generate(new ModelRequest(List.of(
                    ChatMessage.system("你是电商运营 Single Agent。只输出合法 JSON，不要代码围栏或解释。"),
                    ChatMessage.user(prompt(activityId, goal, cost, forceInvalid))), model, List.of()));
            JsonNode root = json.readTree(stripFence(response.content()));
            Map<Role, Artifact> artifacts = artifacts(root, activityId);
            PlanningContext context = new PlanningContext(activityId, cost, goal, forceInvalid);
            GuardrailPolicy policy = policyAgent.assemble(context, artifacts);
            DslCompileResult compiled = compiler.compile(policy);
            boolean safe = critic.verify(policy, compiled).passed();
            boolean complete = safe
                    && policy.rules().stream().anyMatch(r -> r.action() == Action.REPRICE)
                    && policy.rules().stream().anyMatch(r -> r.action() == Action.REALLOCATE);
            String status = forceInvalid ? (safe ? "UNSAFE_SUCCESS" : "CORRECT_FAILURE")
                    : (complete ? "SUCCESS" : "FAILED");
            int tokens = response.tokenIn() + response.tokenOut();
            return new Result(status, tokens, tokenPrice.multiply(BigDecimal.valueOf(tokens))
                    .divide(BigDecimal.valueOf(1000)), elapsed(started), compiled.errors());
        } catch (Exception e) {
            int tokens = response == null ? 0 : response.tokenIn() + response.tokenOut();
            return new Result("FAILED", tokens, tokenPrice.multiply(BigDecimal.valueOf(tokens))
                    .divide(BigDecimal.valueOf(1000)), elapsed(started),
                    List.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private String prompt(long activityId, String goal, BigDecimal cost, boolean invalid) {
        String pricing = invalid ? "repriceTo 必须故意低于 costFloor，用于安全护栏测试。"
                : "repriceTo 不得低于 costFloor，建议成本上浮10%。";
        String examplePrice = invalid ? "5000.0" : "6598.9";
        return """
                活动id=%d，目标=%s，costFloor=%s。%s
                一次性给出四类规划，严格输出：
                {"selector":{"includeSkus":[101,102],"stockRatioThreshold":0.2},
                 "pricer":{"repriceTo":%s,"costFloor":5999.0},
                 "warmup":{"reallocateAmount":50,"targetSegment":"seg_hot"},
                 "risk":{"alertOnVelocityDrop":true}}
                """.formatted(activityId, goal, cost, pricing, examplePrice);
    }

    private Map<Role, Artifact> artifacts(JsonNode root, long activityId) {
        Map<Role, Artifact> out = new LinkedHashMap<>();
        out.put(Role.SELECTOR, artifact(root, "selector", Role.SELECTOR, activityId));
        out.put(Role.PRICER, artifact(root, "pricer", Role.PRICER, activityId));
        out.put(Role.WARMUP, artifact(root, "warmup", Role.WARMUP, activityId));
        out.put(Role.RISK, artifact(root, "risk", Role.RISK, activityId));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Artifact artifact(JsonNode root, String field, Role role, long activityId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException("缺少 " + field);
        Map<String, Object> content = json.convertValue(value, Map.class);
        return Artifact.of("single-" + field + "-" + activityId, field, content);
    }

    private String stripFence(String value) {
        if (value == null) throw new IllegalArgumentException("模型返回为空");
        String text = value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return text;
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }

    public record Result(String status, int tokens, BigDecimal costUsd, long latencyMs, List<String> errors) {}
}
