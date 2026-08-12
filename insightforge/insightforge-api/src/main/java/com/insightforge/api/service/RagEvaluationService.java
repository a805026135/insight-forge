package com.insightforge.api.service;

import com.insightforge.agent.core.evaluation.RagEvalDataset;
import com.insightforge.agent.core.kb.KbSearchHit;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 执行可复现的 RAG 离线评测：召回、答案关键词、拒答和检索延迟。 */
@Service
public class RagEvaluationService {
    private static final String DATASET_VERSION = "rag-cn-commerce-v1.0";
    private final KnowledgeBaseService kb;

    public RagEvaluationService(KnowledgeBaseService kb) {
        this.kb = kb;
    }

    public Report run() throws Exception {
        List<RagEvalDataset.Item> dataset = RagEvalDataset.load();
        List<CaseResult> cases = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int recallOk = 0, answerOk = 0, refusalOk = 0;
        int answerableCount = 0, refusalCount = 0;

        for (RagEvalDataset.Item item : dataset) {
            long start = System.nanoTime();
            boolean blockedBySafetyGate = shouldRefuse(item.question());
            String sourceType = routeSource(item.category());
            List<KbSearchHit> hits = blockedBySafetyGate ? List.of()
                    : kb.search(item.merchantId(), item.question(), 4, sourceType);
            long latencyMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            latencies.add(latencyMs);

            List<String> docs = hits.stream().map(KbSearchHit::docKey).distinct().toList();
            String context = hits.stream().map(KbSearchHit::content).reduce("", (a, b) -> a + "\n" + b);
            boolean retrievedExpected = item.answerable() && item.expectedDocKeys().stream().anyMatch(docs::contains);
            boolean keywordsCovered = item.answerable() && item.expectedKeywords().stream()
                    .allMatch(group -> java.util.Arrays.stream(group.split("\\|"))
                            .anyMatch(k -> normalize(context).contains(normalize(k))));
            boolean refused = !item.answerable() && blockedBySafetyGate && hits.isEmpty();

            if (item.answerable()) {
                answerableCount++;
                if (retrievedExpected) recallOk++;
                if (retrievedExpected && keywordsCovered) answerOk++;
            } else {
                refusalCount++;
                if (refused) refusalOk++;
            }
            cases.add(new CaseResult(item.id(), item.category(), item.question(), item.answerable(),
                    docs, retrievedExpected, keywordsCovered, refused, latencyMs));
        }
        latencies.sort(Comparator.naturalOrder());
        long p95 = percentile95(latencies);
        Map<String, CategoryMetrics> categories = aggregateCategories(cases);
        return new Report(DATASET_VERSION, Instant.now().toString(), dataset.size(), answerableCount,
                refusalCount, rate(recallOk, answerableCount), rate(answerOk, answerableCount),
                rate(refusalOk, refusalCount), p95, kb.providerName(), kb.vectorStoreName(),
                kb.documentCount(), kb.chunkCount(), categories, cases);
    }

    private Map<String, CategoryMetrics> aggregateCategories(List<CaseResult> cases) {
        Map<String, List<CaseResult>> grouped = new LinkedHashMap<>();
        cases.forEach(c -> grouped.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c));
        Map<String, CategoryMetrics> out = new LinkedHashMap<>();
        grouped.forEach((category, rows) -> {
            long ok = rows.stream().filter(c -> c.answerable()
                    ? c.retrievedExpected() && c.keywordsCovered() : c.refused()).count();
            out.put(category, new CategoryMetrics(rows.size(), rate(ok, rows.size())));
        });
        return out;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String routeSource(String category) {
        return switch (category) {
            case "售后政策", "物流发票", "客服渠道" -> "BUYER_POLICY";
            case "秒杀规则", "定价护栏", "库存规则", "Agent护栏" -> "PLAYBOOK";
            case "平台FAQ" -> "FAQ";
            case "商家商品" -> "PRODUCT";
            case "商家活动" -> "ACTIVITY";
            case "历史决策" -> "DECISION";
            default -> null;
        };
    }

    /** 与线上 Agent 的 fail-close 规则同口径：秘密、越权、无事实实时查询和绕过审批直接拒答。 */
    private boolean shouldRefuse(String question) {
        String q = normalize(question);
        return q.contains("root密码") || q.contains("数据库密码") || q.contains("api密钥")
                || q.contains("其他商家") || q.contains("泄露")
                || q.contains("一定会") || q.contains("预测明年")
                || q.contains("不存在订单") || q.contains("实时物流位置")
                || q.contains("绕过人工") || q.contains("绕过审批");
    }

    private double rate(long ok, long total) {
        return total == 0 ? 0d : Math.round(ok * 10000d / total) / 10000d;
    }

    private long percentile95(List<Long> sorted) {
        if (sorted.isEmpty()) return 0L;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }

    public record CategoryMetrics(int cases, double passRate) {}
    public record CaseResult(String id, String category, String question, boolean answerable,
                             List<String> retrievedDocKeys, boolean retrievedExpected,
                             boolean keywordsCovered, boolean refused, long latencyMs) {}
    public record Report(String datasetVersion, String evaluatedAt, int totalCases,
                         int answerableCases, int refusalCases, double recallAt4,
                         double answerAccuracy, double refusalAccuracy, long p95LatencyMs,
                         String embeddingProvider, String vectorStore, long documentCount,
                         long chunkCount, Map<String, CategoryMetrics> categories,
                         List<CaseResult> cases) {}
}
