package com.insightforge.agent.core.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地长期记忆（默认实现，零依赖）。
 *
 * 诚实边界：它不做向量检索，而是「关键词重合度打分」的最简召回——
 * 足以演示「Agent 能从历史活动里找相似方案」的链路，但语义检索精度远低于 Milvus。
 * 生产换成 {@link MilvusMemoryStore} 即可（Embedding + 向量相似度），接口不变。
 */
@Component
@Primary
@ConditionalOnProperty(name = "insightforge.agent.memory.milvus-enabled", havingValue = "false", matchIfMissing = true)
public class LocalMemoryStore implements MemoryStore {

    private final List<MemoryRecord> store = new CopyOnWriteArrayList<>();

    @Override
    public void save(MemoryRecord record) {
        store.add(record);
    }

    @Override
    public List<MemoryRecord> search(String query, int topK) {
        String q = (query == null ? "" : query).toLowerCase();
        List<String> qTokens = tokenize(q);
        return store.stream()
                .map(r -> new Scored(r, score(r.text().toLowerCase(), qTokens)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())
                .limit(topK)
                .map(s -> s.rec)
                .toList();
    }

    private int score(String text, List<String> qTokens) {
        if (qTokens.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String t : qTokens) {
            if (text.contains(t)) {
                hit++;
            }
        }
        return hit;
    }

    private List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.split("\\s+|,|，|:|：|;|；|\\.|。|、|\\(|\\)|\\[|\\]")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }

    private record Scored(MemoryRecord rec, int score) {
    }
}
