package com.insightforge.agent.core.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.agent.core.runtime.Span;
import com.insightforge.agent.core.runtime.Trace;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, fail-open OTLP/HTTP exporter for Langfuse Cloud. MySQL remains the source of truth. */
@Component
public class LangfuseTraceExporter {
    private static final Logger log = LoggerFactory.getLogger(LangfuseTraceExporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final String endpoint;
    private final String authorization;
    private final String environment;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "langfuse-otlp-exporter");
        t.setDaemon(true);
        return t;
    });

    public LangfuseTraceExporter(
            @Value("${insightforge.agent.langfuse.enabled:false}") boolean enabled,
            @Value("${insightforge.agent.langfuse.base-url:https://cloud.langfuse.com}") String baseUrl,
            @Value("${insightforge.agent.langfuse.public-key:}") String publicKey,
            @Value("${insightforge.agent.langfuse.secret-key:}") String secretKey,
            @Value("${insightforge.agent.langfuse.environment:production}") String environment) {
        this.enabled = enabled && !publicKey.isBlank() && !secretKey.isBlank();
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        this.environment = environment;
        if (enabled && !this.enabled) log.warn("Langfuse enabled but credentials are missing; exporter disabled");
    }

    public void export(Trace trace) {
        if (!enabled) return;
        executor.submit(() -> send(trace));
    }

    private void send(Trace trace) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .header("x-langfuse-ingestion-version", "4")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload(trace))))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Langfuse OTLP export failed: status={}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Langfuse OTLP export failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> payload(Trace trace) {
        String traceId = hex(trace.traceId(), 32);
        String rootSpanId = hex(trace.traceId() + "root", 16);
        long start = trace.startedAt().toInstant(ZoneOffset.UTC).toEpochMilli() * 1_000_000L;
        long end = trace.finishedAt().toInstant(ZoneOffset.UTC).toEpochMilli() * 1_000_000L;
        List<Object> spans = new ArrayList<>();
        spans.add(otelSpan(traceId, rootSpanId, null, "insightforge.agent.run", start, end,
                List.of(
                        attr("langfuse.observation.type", "span"),
                        attr("langfuse.environment", environment),
                        attr("langfuse.version", trace.promptVersion()),
                        attr("langfuse.trace.metadata.model", trace.modelVersion()),
                        attr("langfuse.trace.metadata.status", trace.status().name()),
                        attr("langfuse.trace.metadata.tokens", trace.budgetTokens()),
                        attr("langfuse.trace.metadata.cost_usd", decimal(trace.budgetCostUsd())))));
        long cursor = start;
        for (Span span : trace.spans()) {
            long duration = Math.max(1, span.latencyMs()) * 1_000_000L;
            List<Object> attributes = new ArrayList<>();
            boolean generation = span.tokenIn() + span.tokenOut() > 0;
            attributes.add(attr("langfuse.observation.type", generation ? "generation" : "span"));
            attributes.add(attr("langfuse.environment", environment));
            attributes.add(attr("langfuse.observation.metadata.role", span.role()));
            attributes.add(attr("langfuse.observation.metadata.status", span.status().name()));
            if (generation) {
                attributes.add(attr("langfuse.observation.model.name", trace.modelVersion()));
                attributes.add(attr("langfuse.observation.usage_details", JSON.valueToTree(Map.of(
                        "input", span.tokenIn(), "output", span.tokenOut(),
                        "total", span.tokenIn() + span.tokenOut())).toString()));
            }
            spans.add(otelSpan(traceId, hex(span.spanId(), 16), rootSpanId,
                    span.role() + "." + span.stepName(), cursor, Math.min(end, cursor + duration), attributes));
            cursor = Math.min(end, cursor + duration);
        }
        Map<String, Object> scopeSpans = Map.of("scope", Map.of("name", "insightforge-agent", "version", trace.promptVersion()), "spans", spans);
        Map<String, Object> resourceSpans = Map.of("resource", Map.of("attributes", List.of(
                attr("service.name", "insightforge-api"), attr("deployment.environment.name", environment))),
                "scopeSpans", List.of(scopeSpans));
        return Map.of("resourceSpans", List.of(resourceSpans));
    }

    private Map<String, Object> otelSpan(String traceId, String spanId, String parentSpanId,
                                         String name, long start, long end, List<Object> attributes) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", traceId); span.put("spanId", spanId);
        if (parentSpanId != null) span.put("parentSpanId", parentSpanId);
        span.put("name", name); span.put("kind", 1);
        span.put("startTimeUnixNano", Long.toString(start));
        span.put("endTimeUnixNano", Long.toString(Math.max(start + 1, end)));
        span.put("attributes", attributes);
        span.put("status", Map.of("code", 1));
        return span;
    }

    private Map<String, Object> attr(String key, Object value) {
        Map<String, Object> typed;
        if (value instanceof Integer || value instanceof Long) typed = Map.of("intValue", value.toString());
        else if (value instanceof Double || value instanceof BigDecimal) typed = Map.of("doubleValue", value);
        else typed = Map.of("stringValue", value == null ? "" : value.toString());
        return Map.of("key", key, "value", typed);
    }

    private String hex(String value, int length) {
        String cleaned = value == null ? "" : value.replaceAll("[^0-9a-fA-F]", "");
        String padded = (cleaned + "0".repeat(length));
        return padded.substring(0, length).toLowerCase();
    }

    private double decimal(BigDecimal value) { return value == null ? 0d : value.doubleValue(); }

    @PreDestroy
    public void close() { executor.shutdown(); }
}
