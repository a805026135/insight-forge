package com.insightforge.agent.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * 生产用模型接入：通过 Spring AI 调 DeepSeek（OpenAI 兼容协议）。
 *
 * 关键点：
 *   - 这就是 spec 要求的「仅用 Spring AI 接模型」——运行时其余部分全是自研，只有这里依赖 Spring AI。
 *   - DeepSeek 提供 OpenAI 兼容端点 {@code https://api.deepseek.com}，所以复用 Spring AI 的 OpenAiApi 即可，无需定制 SDK。
 *   - 默认不装配（{@code insightforge.agent.deepseek.enabled=true} 才启用）；本地/测试用 LocalDeterministicModelClient。
 *   - 工具调用采用「prompt 内约定 + 解析」方式（模型输出 {@code <tool_call>{...}</tool_call>} 块），
 *     与本地模型桩走同一套 ModelResponse.toolCall 契约，运行时无需区分。
 *
 * 注意：本类只在 enabled=true 时被 Spring 实例化；测试环境不会加载，因此不会真正联网。
 */
@Component
@ConditionalOnProperty(name = "insightforge.agent.deepseek.enabled", havingValue = "true")
public class DeepSeekModelClient implements ModelClient {

    private final OpenAiChatModel chatModel;
    private final String modelName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern TOOL_CALL = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);

    public DeepSeekModelClient(
            @Value("${insightforge.agent.deepseek.api-key:}") String apiKey,
            @Value("${insightforge.agent.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${insightforge.agent.deepseek.model:deepseek-chat}") String modelName) {
        this.modelName = modelName;
        // Spring AI 的 OpenAiApi 直接复用：把 DeepSeek 的兼容端点当成 OpenAI 网关即可。
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        this.chatModel = new OpenAiChatModel(api);
    }

    @Override
    public ModelResponse generate(ModelRequest req) {
        int tokenIn = LocalDeterministicModelClient.estimateTokens(req.messages().toString());

        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : req.messages()) {
            switch (m.role()) {
                case "system" -> messages.add(new SystemMessage(m.content()));
                case "tool"   -> messages.add(new AssistantMessage(m.content())); // 工具观察作为上下文回灌
                default       -> messages.add(new UserMessage(m.content()));
            }
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.2)
                .build();
        Prompt prompt = new Prompt(messages, options);

        long t0 = System.nanoTime();
        // 真正的网络调用（仅在生产 enabled 时发生）
        ChatResponse resp = chatModel.call(prompt);
        long latency = (System.nanoTime() - t0) / 1_000_000;

        String text = resp.getResult().getOutput().getText();
        int tokenOut = LocalDeterministicModelClient.estimateTokens(text);
        // DeepSeek/OpenAI 返回 usage 时使用真实 prompt/completion token；只有 provider 未返回 usage 才估算。
        // 这样 Trace、BudgetGate 和评测页不会把真实模型调用误报为 0 token。
        Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
        if (usage != null) {
            Integer promptTokens = usage.getPromptTokens();
            Integer completionTokens = usage.getCompletionTokens();
            if (promptTokens != null && promptTokens >= 0) tokenIn = promptTokens;
            if (completionTokens != null && completionTokens >= 0) tokenOut = completionTokens;
        }

        // 解析工具调用约定块
        Matcher mt = TOOL_CALL.matcher(text);
        if (mt.find()) {
            try {
                JsonNode node = objectMapper.readTree(mt.group(1));
                String name = node.get("name").asText();
                Map<String, Object> args = objectMapper.convertValue(node.get("args"), Map.class);
                return ModelResponse.tool(new ToolCall(name, args), tokenIn, tokenOut, latency);
            } catch (Exception e) {
                // 解析失败：当作普通回答返回（交由 Critic/运行时兜底）
            }
        }
        return ModelResponse.answer(text, tokenIn, tokenOut, latency);
    }

    /** 流式生成：把模型输出按增量块推给 consumer（用于商家助手打字机效果）。 */
    public void streamGenerate(ModelRequest req, Consumer<String> consumer) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : req.messages()) {
            switch (m.role()) {
                case "system" -> messages.add(new SystemMessage(m.content()));
                case "tool"   -> messages.add(new AssistantMessage(m.content()));
                default       -> messages.add(new UserMessage(m.content()));
            }
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.2)
                .build();
        Prompt prompt = new Prompt(messages, options);
        chatModel.stream(prompt)
                .doOnNext(resp -> {
                    String chunk = resp.getResult() != null && resp.getResult().getOutput() != null
                            ? resp.getResult().getOutput().getText() : "";
                    if (chunk != null && !chunk.isEmpty()) {
                        consumer.accept(chunk);
                    }
                })
                .blockLast();
    }
}
